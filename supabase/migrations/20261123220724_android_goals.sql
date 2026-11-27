begin;
create table "Task".goals (
 id uuid primary key, user_id uuid not null references auth.users(id) on delete cascade,
 title text not null check (btrim(title)<>''), period text not null check (period in ('WEEK','MONTH','YEAR')),
 starts_on date not null check(starts_on between date '0001-01-01' and date '9999-12-31'), target numeric check (target>0 and target<1e100), unit text not null default '', achieved boolean not null default false,
 unique(id,user_id),
 check(starts_on = case period when 'WEEK' then date_trunc('week',starts_on)::date when 'MONTH' then date_trunc('month',starts_on)::date else date_trunc('year',starts_on)::date end)
);
create table "Task".goal_reviews (
 id uuid primary key, user_id uuid not null references auth.users(id) on delete cascade,
 goal_id uuid not null, recorded_at timestamptz not null, amount numeric check(amount>=0 and amount<1e100), note text not null default '',
 foreign key(goal_id,user_id) references "Task".goals(id,user_id) on delete cascade
);
create table "Task".goal_schedules (
 id uuid primary key, user_id uuid not null references auth.users(id) on delete cascade,
 period text not null check(period in ('WEEK','MONTH','YEAR')), enabled boolean not null default false,
 frequency text not null check(frequency in ('DAILY','WEEKLY','MONTHLY')), time time not null,
 weekday integer not null check(weekday between 1 and 7), month_day integer not null check(month_day between 1 and 31), unique(user_id,period)
);
create index goals_owner_idx on "Task".goals(user_id);
create index goal_reviews_owner_goal_idx on "Task".goal_reviews(user_id,goal_id);
do $$ declare t text; begin
 foreach t in array array['goals','goal_reviews','goal_schedules'] loop
  execute format('alter table "Task".%I enable row level security',t);
  execute format('revoke all on "Task".%I from public,anon,authenticated',t);
  execute format('grant select on "Task".%I to authenticated',t);
  execute format('create policy read_own on "Task".%I for select to authenticated using (user_id=(select auth.uid()))',t);
  execute format('create trigger a_sync_guard before insert or update or delete on "Task".%I for each row execute function "Task".sync_guard()',t);
  execute format('create trigger z_sync_capture after insert or update or delete on "Task".%I for each row execute function "Task".sync_capture()',t);
 end loop;
end $$;
create or replace function "Task".sync_snapshot() returns jsonb
language plpgsql volatile security invoker set search_path=pg_catalog as $$
declare uid uuid:=auth.uid(); t text; data jsonb:='{}'; records jsonb; prefs jsonb; day_data jsonb;
begin
  if uid is null then raise exception 'Authentication required'; end if;
  perform pg_advisory_xact_lock(hashtextextended(uid::text,4781));
  foreach t in array array['goals','goal_reviews','goal_schedules','category','projects','project_features','project_feature_links','tasks','routine','routine_period','routine_completion','user_settings'] loop
    select coalesce(jsonb_agg(s.state || jsonb_build_object('_sync',jsonb_build_object('revision',s.revision,'state',s.state)) order by s.id),'[]'::jsonb)
      into records from "Task".sync_entities s where s.user_id=uid and s.entity=t and not s.deleted;
    data:=data||jsonb_build_object(t,records);
  end loop;
  select state into prefs from "Task".sync_entities where user_id=uid and entity='user_settings' and not deleted;
  prefs:=coalesce(prefs,jsonb_build_object('user_id',uid,'routine_reset_time','00:00','calendar_start_time','00:00','calendar_end_time','24:00','week_start','monday'));
  day_data:="Task".sync_day(clock_timestamp(),prefs->>'routine_reset_time');
  return jsonb_build_object('protocol',1,'owner',uid,'data',data,'downloadedAt',clock_timestamp(),'day',day_data,
    'settings',prefs||jsonb_build_object('timezone','Europe/Madrid'),
    'routines',coalesce((select jsonb_agg(to_jsonb(r)||jsonb_build_object('_sync',jsonb_build_object('revision',s.revision,'state',s.state))) from "Task".routine_summaries((day_data->>'date')::date) r join "Task".sync_entities s on s.user_id=uid and s.entity='routine' and s.id=r.id),'[]'::jsonb));
end $$;
revoke all on function "Task".sync_snapshot() from public,anon;
grant execute on function "Task".sync_snapshot() to authenticated;

create or replace function "Task".sync_apply(p_operation jsonb) returns jsonb
language plpgsql security definer set search_path=pg_catalog as $$
<<op>>
declare uid uuid:=auth.uid(); op_id uuid; item uuid; entity text; action text; base jsonb; patch jsonb; context jsonb;
  old_receipt "Task".sync_operations; current_row "Task".sync_entities; remote jsonb; result jsonb;
  allowed text[]; k text; conflict_fields text[]:='{}'; sql_sets text; row_result jsonb;
  reset text; day_data jsonb; target_day date; recorded timestamptz; historic jsonb; known_revision bigint; routine_id uuid;
  was_active boolean; start_day date; stored_id uuid; diagnostic text; failed_constraint text; known_state jsonb; activation_receipt "Task".sync_operations;
begin
  if uid is null then raise exception 'Authentication required'; end if;
  if p_operation->>'protocol' is distinct from '1' then raise exception 'Actualiza la aplicación: protocolo no compatible'; end if;
  op_id:=(p_operation->>'operation_id')::uuid; item:=(p_operation->>'id')::uuid;
  entity:=p_operation->>'entity'; action:=p_operation->>'action'; base:=p_operation->'base'; patch:=coalesce(p_operation->'changes','{}'); context:=coalesce(p_operation->'context','{}');
  if op_id is null or item is null or entity is null or action is null or entity not in ('goals','goal_reviews','goal_schedules','tasks','projects','project_features','project_feature_links','category','routine','routine_completion','user_settings') or action not in ('create','update','delete','complete') or jsonb_typeof(patch)<>'object' then raise exception 'Operación no válida'; end if;
  if (entity='routine_completion' and action<>'complete') or (entity<>'routine_completion' and action='complete') or (entity='user_settings' and action<>'update') or (entity='project_feature_links' and action not in ('create','delete')) then raise exception 'Acción no válida para este elemento'; end if;
  if entity='goal_reviews' and action<>'create' then raise exception 'Las revisiones son registros permanentes'; end if;
  perform pg_advisory_xact_lock(hashtextextended(uid::text,4781));
  select * into old_receipt from "Task".sync_operations where user_id=uid and operation_id=op_id;
  if found then
    if old_receipt.request<>p_operation then raise exception 'El identificador de operación ya se utilizó con otro contenido'; end if;
    return old_receipt.result;
  end if;
  perform set_config('studycalendar.sync_user',uid::text,true);
  begin
    allowed:=case entity
      when 'goals' then array['title','period','starts_on','target','unit','achieved']
      when 'goal_reviews' then array['goal_id','recorded_at','amount','note']
      when 'goal_schedules' then array['period','enabled','frequency','time','weekday','month_day']
      when 'tasks' then array['title','date','start_time','end_time','all_day','completed','category_id','project_id','task_type','color','prerequisite_ids','feature_id']
      when 'project_feature_links' then case when action='create' then array['project_id','source_feature_id','target_feature_id'] else array[]::text[] end
      when 'project_features' then case when action='create' then array['project_id','name','description'] else array['name','description'] end
      when 'projects' then array['name','color','status'] when 'category' then array['name','color']
      when 'routine' then array['title','color','active'] when 'routine_completion' then array['completed']
      else array['routine_reset_time','calendar_start_time','calendar_end_time','week_start'] end;
    for k in select jsonb_object_keys(patch) loop
      if not(k=any(allowed)) then raise exception 'Campo no permitido: %',k; end if;
      if patch->k='null'::jsonb and k not in ('date','start_time','end_time','category_id','project_id','feature_id','description','target','amount') then raise exception 'El campo % no admite null',k; end if;
      if k in ('completed','all_day','active','achieved','enabled') and jsonb_typeof(patch->k)<>'boolean' then raise exception 'El campo % debe ser booleano',k; end if;
      if k in ('start_time','end_time','time') and patch->k<>'null'::jsonb then patch:=jsonb_set(patch,array[k],to_jsonb((patch->>k)::time)); end if;
    end loop;
    if entity='project_features' then
      if (action='create' or patch ? 'name') and (jsonb_typeof(patch->'name') is distinct from 'string' or btrim(patch->>'name')='') then
        raise exception 'Introduce un nombre para la funcionalidad';
      end if;
      if patch ? 'name' then patch:=jsonb_set(patch,'{name}',to_jsonb(btrim(patch->>'name'))); end if;
      if patch ? 'description' and jsonb_typeof(patch->'description') not in ('string','null') then
        raise exception 'La descripción debe ser texto';
      end if;
      if jsonb_typeof(patch->'description')='string' then
        patch:=jsonb_set(patch,'{description}',coalesce(to_jsonb(nullif(btrim(patch->>'description'),'')),'null'::jsonb));
      end if;
      if action='create' and not exists(select 1 from "Task".projects where id=(patch->>'project_id')::uuid and user_id=uid) then
        raise exception 'Proyecto no disponible';
      end if;
    end if;
    select * into current_row from "Task".sync_entities where user_id=uid and "sync_entities".entity=op.entity and id=item;
    remote:=case when current_row.deleted then null else current_row.state end;
    if entity='routine_completion' then
      if action<>'complete' then raise exception 'Acción de hábito no válida'; end if;
      routine_id:=item; target_day:="Task".sync_context_day(uid,context); recorded:=(context->>'recorded_at')::timestamptz;
      if target_day is null or recorded is null or recorded>clock_timestamp()+interval '5 minutes' then raise exception 'Contexto temporal no válido'; end if;
      select state into historic from "Task".sync_history where user_id=uid and "sync_history".entity='routine' and id=routine_id and revision=(context->>'routine_revision')::bigint and not deleted;
      known_state:=historic;
      if historic is null or not (historic->>'active')::boolean or not exists(select 1 from jsonb_array_elements(historic->'periods') p where (p->>'start')::date<=target_day and (p->>'end' is null or (p->>'end')::date>=target_day)) then raise exception 'La rutina no estaba disponible en el contexto original'; end if;
      if not exists(select 1 from "Task".routine where id=routine_id and user_id=uid) then
        result:=jsonb_build_object('status','conflict','reason','deleted','fields',jsonb_build_array('completed'),'remote',null); 
      else
        reset:=context->>'reset_time';
        if reset !~ '^([01][0-9]|2[0-3]):[0-5][0-9]$' then raise exception 'Hora de reinicio no válida'; end if;
        if target_day<>("Task".sync_day(recorded,reset)->>'date')::date then raise exception 'El día no coincide con el contexto original'; end if;
        select state into historic from "Task".sync_history where user_id=uid and "sync_history".entity='user_settings' and id=uid and revision=(context->>'settings_revision')::bigint and not deleted;
        if (historic is null and (coalesce((context->>'settings_revision')::bigint,0)<>0 or reset<>'00:00')) or (historic is not null and historic->>'routine_reset_time'<>reset) then raise exception 'Ajustes originales no válidos'; end if;
        -- Check availability at the original recording instant, preserving an
        -- action recorded before a later same-day pause or reset change.
        select state into historic from "Task".sync_history where user_id=uid and "sync_history".entity='routine' and id=routine_id and changed_at<=recorded order by changed_at desc,revision desc limit 1;
        if context ? 'routine_operation_id' then
          select * into activation_receipt from "Task".sync_operations where user_id=uid and operation_id=(context->>'routine_operation_id')::uuid;
          if activation_receipt.result->>'status'='applied' and activation_receipt.request->>'entity'='routine' and (activation_receipt.request->>'id')::uuid=routine_id
            and activation_receipt.request->'changes'->>'active'='true'
            and (activation_receipt.request->'context'->>'recorded_at')::timestamptz<=recorded
            and (activation_receipt.result->'row'->'_sync'->>'revision')::bigint=(context->>'routine_revision')::bigint then historic:=known_state; end if;
        end if;
        if historic is null or not coalesce((historic->>'active')::boolean,false) or not exists(select 1 from jsonb_array_elements(historic->'periods') p where (p->>'start')::date<=target_day and (p->>'end' is null or (p->>'end')::date>=target_day)) then
          result:=jsonb_build_object('status','conflict','reason','availability','fields',jsonb_build_array('completed'),'remote',null);
        else
          select id,jsonb_build_object('completed',completed) into stored_id,remote from "Task".routine_completion where user_id=uid and "routine_completion".routine_id=op.routine_id and occurrence_date=target_day;
          remote:=coalesce(remote,'{"completed":false}'::jsonb);
        end if;
      end if;
    elsif action='create' then
      if current_row.revision is not null then result:=jsonb_build_object('status','conflict','reason',case when current_row.deleted then 'deleted' else 'exists' end,'remote',to_jsonb(current_row)); end if;
    elsif entity='user_settings' and remote is null and item=uid then
      remote:=jsonb_build_object('user_id',uid,'routine_reset_time','00:00','calendar_start_time','00:00','calendar_end_time','24:00','week_start','monday');
    elsif remote is null then
      result:=jsonb_build_object('status','conflict','reason','deleted','remote',null);
    end if;
    if result is null and action<>'create' then
      if jsonb_typeof(base->'state') is distinct from 'object' then raise exception 'Falta la versión base de la edición'; end if;
      if action='delete' then
        if (base->>'revision')::bigint is distinct from current_row.revision then conflict_fields:=array['delete']; end if;
      else
        if entity='routine' and patch ? 'active' and base->'state'->'periods' is distinct from remote->'periods' then conflict_fields:=array['active']; end if;
        for k in select jsonb_object_keys(patch) loop
          if entity='tasks' and k='feature_id' then
            if (base->'state'->>k) is distinct from (remote->>k) and (patch->>k) is distinct from (remote->>k) then
              conflict_fields:=array_append(conflict_fields,k);
            end if;
            continue;
          end if;
          if not(base->'state' ? k) then raise exception 'Falta el valor original de %',k; end if;
          if base->'state'->k is distinct from remote->k and patch->k is distinct from remote->k then conflict_fields:=array_append(conflict_fields,k); end if;
        end loop;
      end if;
      if action='delete' and entity in ('projects','category') then
        remote:=remote||jsonb_build_object('affected_tasks',coalesce((select jsonb_agg(jsonb_build_object('title',t.title,'date',t.date,'completed',t.completed) order by t.title,t.id) from "Task".tasks t where t.user_id=uid and ((entity='projects' and t.project_id=item) or (entity='category' and t.category_id=item))),'[]'::jsonb));
      elsif action='delete' and entity='routine' then
        remote:=remote||jsonb_build_object('completion_count',(select count(*) from "Task".routine_completion c where c.user_id=uid and c.routine_id=item and c.completed));
      end if;
      if cardinality(conflict_fields)>0 then result:=jsonb_build_object('status','conflict','reason','fields','fields',to_jsonb(conflict_fields),'remote',jsonb_build_object('revision',current_row.revision,'state',remote)); end if;
    end if;
    if result is null then
      -- A legacy move to a different project cannot carry its old membership.
      if entity='tasks' and action='update' and not (patch ? 'feature_id')
        and remote->>'feature_id' is not null
        and ((patch ? 'project_id' and (patch->>'project_id') is distinct from (remote->>'project_id'))
          or (patch ? 'task_type' and patch->>'task_type'<>'project')) then
        patch:=patch||'{"feature_id":null}'::jsonb;
      end if;
      if entity='user_settings' and item<>uid then raise exception 'Ajustes de otra cuenta'; end if;
      if entity in ('goals','goal_reviews','goal_schedules','tasks','projects','project_features','project_feature_links','category','routine') then
        if action='create' then
          if entity='goals' then
            insert into "Task".goals(id,user_id,title,period,starts_on,target,unit,achieved)
            values(item,uid,btrim(patch->>'title'),patch->>'period',(patch->>'starts_on')::date,(patch->>'target')::numeric,patch->>'unit',(patch->>'achieved')::boolean);
          elsif entity='goal_reviews' then
            insert into "Task".goal_reviews(id,user_id,goal_id,recorded_at,amount,note)
            values(item,uid,(patch->>'goal_id')::uuid,(patch->>'recorded_at')::timestamptz,(patch->>'amount')::numeric,patch->>'note');
          elsif entity='goal_schedules' then
            insert into "Task".goal_schedules(id,user_id,period,enabled,frequency,time,weekday,month_day)
            values(item,uid,patch->>'period',(patch->>'enabled')::boolean,patch->>'frequency',(patch->>'time')::time,(patch->>'weekday')::integer,(patch->>'month_day')::integer);
          elsif entity='tasks' then
            insert into "Task".tasks(id,user_id,title,date,start_time,end_time,color,completed,category_id,project_id,task_type,all_day,prerequisite_ids,feature_id)
            values(item,uid,trim(patch->>'title'),(patch->>'date')::date,(patch->>'start_time')::time,(patch->>'end_time')::time,coalesce(patch->>'color','#4F46E5'),coalesce((patch->>'completed')::boolean,false),(patch->>'category_id')::uuid,(patch->>'project_id')::uuid,coalesce(patch->>'task_type','daily'),coalesce((patch->>'all_day')::boolean,false),coalesce(array(select jsonb_array_elements_text(patch->'prerequisite_ids')::uuid),'{}'),(patch->>'feature_id')::uuid);
          elsif entity='project_feature_links' then
            insert into "Task".project_feature_links(id,user_id,project_id,source_feature_id,target_feature_id)
              values(item,uid,(patch->>'project_id')::uuid,(patch->>'source_feature_id')::uuid,(patch->>'target_feature_id')::uuid);
          elsif entity='project_features' then
            insert into "Task".project_features(id,user_id,project_id,name,description)
              values(item,uid,(patch->>'project_id')::uuid,patch->>'name',patch->>'description');
          elsif entity='projects' then insert into "Task".projects(id,user_id,name,color,status) values(item,uid,trim(patch->>'name'),coalesce(patch->>'color','#4F46E5'),coalesce(patch->>'status','active'));
          elsif entity='category' then insert into "Task".category(id,user_id,name,color) values(item,uid,trim(patch->>'name'),coalesce(patch->>'color','#4F46E5'));
          else
            start_day:=(context->>'starts_on')::date;
            select coalesce(routine_reset_time,'00:00') into reset from "Task".user_settings where user_id=uid;
            start_day:=coalesce(start_day,("Task".sync_day(clock_timestamp(),coalesce(reset,'00:00'))->>'date')::date);
            insert into "Task".routine(id,user_id,title,color,active) values(item,uid,trim(patch->>'title'),coalesce(patch->>'color','#16A085'),coalesce((patch->>'active')::boolean,true));
            if coalesce((patch->>'active')::boolean,true) then insert into "Task".routine_period(routine_id,user_id,starts_on) values(item,uid,start_day); end if;
          end if;
        elsif action='delete' then execute format('delete from "Task".%I where id=$1 and user_id=$2',entity) using item,uid;
        else
          if entity='routine' then was_active:=(remote->>'active')::boolean; end if;
          select string_agg(format('%I=(jsonb_populate_record(null::"Task".%I,$1)).%I',key,entity,key),',') into sql_sets from jsonb_object_keys(patch) key;
          if sql_sets is not null then execute format('update "Task".%I set %s where id=$2 and user_id=$3',entity,sql_sets) using patch,item,uid; end if;
          if entity='routine' and patch ? 'active' and was_active<>(patch->>'active')::boolean then
            select coalesce(routine_reset_time,'00:00') into reset from "Task".user_settings where user_id=uid;
            start_day:="Task".sync_context_day(uid,context);
            if (patch->>'active')::boolean then
              if exists(select 1 from "Task".routine_period rp where rp.routine_id=item and rp.user_id=uid and rp.ends_on>=start_day) then raise exception 'La activación se solapa con un período que cambió. Revisa la rutina.'; end if;
              if not exists(select 1 from "Task".routine_period rp where rp.routine_id=item and rp.user_id=uid and rp.ends_on is null) then insert into "Task".routine_period(routine_id,user_id,starts_on) values(item,uid,start_day); end if;
            else
              delete from "Task".routine_period rp where rp.routine_id=item and rp.user_id=uid and rp.ends_on is null and starts_on>=start_day;
              update "Task".routine_period rp set ends_on=start_day-1 where rp.routine_id=item and rp.user_id=uid and rp.ends_on is null and starts_on<start_day;
            end if;
          elsif entity='projects' and patch ? 'color' then update "Task".tasks set color=patch->>'color' where project_id=item and user_id=uid;
          elsif entity='category' and patch ? 'color' then update "Task".tasks set color=patch->>'color' where category_id=item and user_id=uid; end if;
        end if;
      elsif entity='routine_completion' then
        if stored_id is null then insert into "Task".routine_completion(routine_id,user_id,occurrence_date,completed) values(routine_id,uid,target_day,(patch->>'completed')::boolean);
        else update "Task".routine_completion set completed=(patch->>'completed')::boolean where id=stored_id and user_id=uid; end if;
      else
        insert into "Task".user_settings(user_id) values(uid) on conflict(user_id) do nothing;
        select string_agg(format('%I=(jsonb_populate_record(null::"Task".user_settings,$1)).%I',key,key),',') into sql_sets from jsonb_object_keys(patch) key;
        if sql_sets is not null then execute format('update "Task".user_settings set %s where user_id=$2',sql_sets) using patch,uid; end if;
      end if;
      set constraints all immediate;
      set constraints all deferred;
      select coalesce(routine_reset_time,'00:00') into reset from "Task".user_settings where user_id=uid;
      day_data:="Task".sync_day(clock_timestamp(),coalesce(reset,'00:00'));
      select state || jsonb_build_object('_sync',jsonb_build_object('revision',revision,'state',state)) into row_result from "Task".sync_entities where user_id=uid and "sync_entities".entity=op.entity and id=item and not deleted;
      if entity='routine' or entity='routine_completion' then
        select to_jsonb(r)||jsonb_build_object('_sync',jsonb_build_object('revision',s.revision,'state',s.state)) into row_result from "Task".routine_summaries((day_data->>'date')::date) r join "Task".sync_entities s on s.user_id=uid and s.entity='routine' and s.id=r.id where r.id=item;
      end if;
      result:=jsonb_build_object('status','applied','row',row_result,'day',day_data,'deleted',case when action='delete' then item else null end);
    end if;
  exception when others then
    get stacked diagnostics failed_constraint = constraint_name;
    if SQLSTATE='P0001' then diagnostic:=SQLERRM;
    elsif entity='project_feature_links' then
      diagnostic:=case failed_constraint
        when 'project_feature_links_unique_pair' then 'Las funcionalidades ya están conectadas'
        when 'project_feature_links_distinct_endpoints' then 'Una funcionalidad no puede depender de sí misma'
        else 'Selecciona dos funcionalidades del mismo proyecto disponibles'
      end;
    elsif failed_constraint='project_features_project_name_idx' then diagnostic:='Ya existe una funcionalidad con ese nombre';
    elsif failed_constraint in ('tasks_feature_membership_fkey','tasks_feature_requires_project') then
      diagnostic:='La funcionalidad no pertenece al proyecto de la tarea';
    else diagnostic:='La combinación no cumple las reglas. Revisa títulos, nombres, fechas, horarios y asociaciones.'; end if;
    result:=jsonb_build_object('status','rejected','reason',
      case when failed_constraint='project_features_project_name_idx' then 'duplicate_feature_name' else 'validation' end,
      'message',diagnostic);
  end;
  insert into "Task".sync_operations(user_id,operation_id,request,result) values(uid,op_id,p_operation,result);
  perform set_config('studycalendar.sync_user','',true);
  return result;
end $$;
revoke all on function "Task".sync_apply(jsonb) from public,anon;
grant execute on function "Task".sync_apply(jsonb) to authenticated;

notify pgrst, 'reload schema';

commit;
