-- Prepared locally. Requires an isolated PostgreSQL check, a private backup,
-- and coordinated web/backend rollout. Never apply automatically.
lock table "Task".category,"Task".projects,"Task".tasks,"Task".routine,"Task".routine_period,"Task".routine_completion,"Task".user_settings in share row exclusive mode;

-- Fail without changing business data if legacy cross-owner links exist.
do $$ begin
  if exists(select 1 from "Task".tasks t join "Task".projects p on p.id=t.project_id where t.user_id<>p.user_id)
    or exists(select 1 from "Task".tasks t join "Task".category c on c.id=t.category_id where t.user_id<>c.user_id)
    or exists(select 1 from "Task".routine_period p join "Task".routine r on r.id=p.routine_id where p.user_id<>r.user_id)
    or exists(select 1 from "Task".routine_completion c join "Task".routine r on r.id=c.routine_id where c.user_id<>r.user_id)
  then raise exception 'Hay asociaciones de distintos propietarios que necesitan revisión antes de migrar'; end if;
end $$;
create sequence "Task".sync_revision_seq;
create table "Task".sync_entities (
  user_id uuid not null references auth.users(id) on delete cascade,
  entity text not null, id uuid not null, revision bigint not null,
  state jsonb, deleted boolean not null, changed_at timestamptz not null,
  primary key(user_id,entity,id)
);
create table "Task".sync_history (
  user_id uuid not null references auth.users(id) on delete cascade,
  entity text not null, id uuid not null, revision bigint not null,
  state jsonb, deleted boolean not null, changed_at timestamptz not null,
  primary key(user_id,entity,id,revision)
);
create index sync_history_time on "Task".sync_history(user_id,entity,id,changed_at desc,revision desc);
create table "Task".sync_operations (
  user_id uuid not null references auth.users(id) on delete cascade,
  operation_id uuid not null, request jsonb not null, result jsonb not null,
  created_at timestamptz not null default clock_timestamp(),
  primary key(user_id,operation_id)
);
do $$ declare t text; begin
  foreach t in array array['sync_entities','sync_history','sync_operations'] loop
    execute format('alter table "Task".%I enable row level security',t);
    execute format('revoke all on "Task".%I from public,anon,authenticated',t);
    execute format('grant select on "Task".%I to authenticated',t);
    execute format('create policy own_rows on "Task".%I for select to authenticated using ((select auth.uid())=user_id)',t);
  end loop;
end $$;
revoke all on sequence "Task".sync_revision_seq from public,anon,authenticated;

create function "Task".sync_state(p_table text,p_row jsonb) returns jsonb
language sql immutable security invoker set search_path=pg_catalog as $$
  select p_row - '_sync'
$$;
revoke all on function "Task".sync_state(text,jsonb) from public,anon;
grant execute on function "Task".sync_state(text,jsonb) to authenticated;

-- These two internal bookkeeping helpers require the owner's privileges because
-- clients can SELECT, but cannot forge revisions, history or operation receipts.
-- No helper is executable by an API role; no dynamic identifier is client supplied.
create function "Task".sync_track(p_user uuid,p_table text,p_id uuid,p_state jsonb,p_deleted boolean)
returns void language plpgsql security definer set search_path=pg_catalog as $$
declare r bigint; at_time timestamptz := clock_timestamp();
begin
  if not exists(select 1 from auth.users where id=p_user) then return; end if;
  r := nextval('"Task".sync_revision_seq');
  insert into "Task".sync_entities values(p_user,p_table,p_id,r,p_state,p_deleted,at_time)
    on conflict(user_id,entity,id) do update set revision=excluded.revision,state=excluded.state,deleted=excluded.deleted,changed_at=excluded.changed_at;
  insert into "Task".sync_history values(p_user,p_table,p_id,r,p_state,p_deleted,at_time);
end $$;
revoke all on function "Task".sync_track(uuid,text,uuid,jsonb,boolean) from public,anon,authenticated;

create function "Task".sync_capture() returns trigger
language plpgsql security definer set search_path=pg_catalog as $$
declare row_data jsonb; owner uuid; item uuid; parent uuid; parent_data jsonb;
begin
  row_data := case when TG_OP='DELETE' then to_jsonb(old) else to_jsonb(new) end;
  owner := (row_data->>'user_id')::uuid;
  item := case when TG_TABLE_NAME='user_settings' then owner else (row_data->>'id')::uuid end;
  perform "Task".sync_track(owner,TG_TABLE_NAME,item,"Task".sync_state(TG_TABLE_NAME,row_data),TG_OP='DELETE');
  if TG_TABLE_NAME in ('routine_period','routine_completion') then
    parent := (row_data->>'routine_id')::uuid;
    select "Task".sync_state('routine',to_jsonb(r)) into parent_data from "Task".routine r where r.id=parent and r.user_id=owner;
    if parent_data is not null then
      parent_data := parent_data || jsonb_build_object('periods',coalesce((select jsonb_agg(jsonb_build_object('start',p.starts_on,'end',p.ends_on) order by p.starts_on,p.id) from "Task".routine_period p where p.routine_id=parent and p.user_id=owner),'[]'::jsonb));
      perform "Task".sync_track(owner,'routine',parent,parent_data,false);
    end if;
  elsif TG_TABLE_NAME='routine' and TG_OP<>'DELETE' then
    parent_data := "Task".sync_state('routine',row_data) || jsonb_build_object('periods',coalesce((select jsonb_agg(jsonb_build_object('start',p.starts_on,'end',p.ends_on) order by p.starts_on,p.id) from "Task".routine_period p where p.routine_id=item and p.user_id=owner),'[]'::jsonb));
    perform "Task".sync_track(owner,'routine',item,parent_data,false);
  elsif TG_TABLE_NAME='tasks' then
    for parent in select distinct value::uuid from jsonb_array_elements_text(jsonb_build_array(
      case when TG_OP<>'INSERT' then old.project_id else null end,
      case when TG_OP<>'DELETE' then new.project_id else null end)) where value is not null loop
      select "Task".sync_state('projects',to_jsonb(p)) into parent_data from "Task".projects p where p.id=parent and p.user_id=owner;
      if parent_data is not null then perform "Task".sync_track(owner,'projects',parent,parent_data,false); end if;
    end loop;
    for parent in select distinct value::uuid from jsonb_array_elements_text(jsonb_build_array(
      case when TG_OP<>'INSERT' then old.category_id else null end,
      case when TG_OP<>'DELETE' then new.category_id else null end)) where value is not null loop
      select "Task".sync_state('category',to_jsonb(c)) into parent_data from "Task".category c where c.id=parent and c.user_id=owner;
      if parent_data is not null then perform "Task".sync_track(owner,'category',parent,parent_data,false); end if;
    end loop;
  end if;
  return null;
end $$;
revoke all on function "Task".sync_capture() from public,anon,authenticated;

create function "Task".sync_guard() returns trigger
language plpgsql security invoker set search_path=pg_catalog as $$
declare uid uuid := auth.uid(); owner uuid; item uuid; row_data jsonb;
begin
  row_data := case when TG_OP='DELETE' then to_jsonb(old) else to_jsonb(new) end;
  owner := (row_data->>'user_id')::uuid;
  if uid is not null then
    if uid<>owner or current_setting('studycalendar.sync_user',true) is distinct from uid::text then
      raise exception 'Actualiza la aplicación: esta escritura requiere el protocolo de sincronización.';
    end if;
  end if;
  perform pg_advisory_xact_lock(hashtextextended(owner::text,4781));
  if TG_OP='UPDATE' and old.user_id<>new.user_id then raise exception 'No se puede cambiar el propietario'; end if;
  if TG_OP='INSERT' and TG_TABLE_NAME not in ('routine_period','routine_completion','user_settings') and exists(
    select 1 from "Task".sync_entities where user_id=owner and entity=TG_TABLE_NAME and id=(row_data->>'id')::uuid
  ) then raise exception 'Este identificador ya se utilizó y no puede recrearse'; end if;
  if TG_OP='DELETE' then return old; end if;
  if TG_TABLE_NAME='tasks' then
    if new.task_type not in ('daily','project') or (new.task_type='daily' and (new.project_id is not null or cardinality(new.prerequisite_ids)>0)) then raise exception 'Tipo de tarea no válido'; end if;
    if new.project_id is not null then select p.color into new.color from "Task".projects p where p.id=new.project_id and p.user_id=owner;
    elsif new.category_id is not null then select c.color into new.color from "Task".category c where c.id=new.category_id and c.user_id=owner; end if;
    if new.color !~ '^#[0-9A-Fa-f]{6}$'  then raise exception 'Color no válido'; end if;
    if new.category_id is not null and not exists(select 1 from "Task".category where id=new.category_id and user_id=owner) then raise exception 'Categoría no disponible'; end if;
    if new.project_id is not null and not exists(select 1 from "Task".projects where id=new.project_id and user_id=owner) then raise exception 'Proyecto no disponible'; end if;
    if new.project_id is not null and (TG_OP='INSERT' or (row_data->>'project_id') is distinct from (to_jsonb(old)->>'project_id')) and exists(select 1 from "Task".projects where id=new.project_id and user_id=owner and status<>'active') then raise exception 'Selecciona un proyecto activo'; end if;
  elsif TG_TABLE_NAME in ('routine_period','routine_completion') then
    if not exists(select 1 from "Task".routine where id=new.routine_id and user_id=owner) then raise exception 'Rutina no disponible'; end if;
  end if;
  return new;
end $$;
revoke all on function "Task".sync_guard() from public,anon,authenticated;

-- Capture the initial state without altering any existing business row.
do $$ declare t text; row_data jsonb; owner uuid; item uuid; begin
  foreach t in array array['category','projects','tasks','routine','routine_period','routine_completion','user_settings'] loop
    for row_data in execute format('select to_jsonb(t) from "Task".%I t',t) loop
      owner := (row_data->>'user_id')::uuid;
      item := case when t='user_settings' then owner else (row_data->>'id')::uuid end;
      if t='routine' then row_data := row_data || jsonb_build_object('periods',coalesce((select jsonb_agg(jsonb_build_object('start',p.starts_on,'end',p.ends_on) order by p.starts_on,p.id) from "Task".routine_period p where p.routine_id=item and p.user_id=owner),'[]'::jsonb)); end if;
      perform "Task".sync_track(owner,t,item,"Task".sync_state(t,row_data),false);
    end loop;
    execute format('create trigger a_sync_guard before insert or update or delete on "Task".%I for each row execute function "Task".sync_guard()',t);
    execute format('create trigger z_sync_capture after insert or update or delete on "Task".%I for each row execute function "Task".sync_capture()',t);
  end loop;
end $$;

create function "Task".sync_day(p_now timestamptz,p_reset text) returns jsonb
language plpgsql stable security invoker set search_path=pg_catalog as $$
declare day date; wall timestamp; boundary timestamptz; following timestamptz; offset_time time := p_reset::time;
begin
  day := (p_now at time zone 'Europe/Madrid')::date;
  -- Find the first instant for a repeated wall time; advance missing wall minutes.
  wall := day + offset_time;
  loop
    boundary := wall at time zone 'Europe/Madrid';
    if (boundary at time zone 'Europe/Madrid')=wall then exit; end if;
    wall := wall + interval '1 minute';
  end loop;
  if ((boundary-interval '1 hour') at time zone 'Europe/Madrid')=wall then boundary := boundary-interval '1 hour'; end if;
  if p_now<boundary then day:=day-1; end if;
  wall:=day+offset_time;
  loop boundary:=wall at time zone 'Europe/Madrid'; exit when (boundary at time zone 'Europe/Madrid')=wall; wall:=wall+interval '1 minute'; end loop;
  if ((boundary-interval '1 hour') at time zone 'Europe/Madrid')=wall then boundary:=boundary-interval '1 hour'; end if;
  wall:=(day+1)+offset_time;
  loop following:=wall at time zone 'Europe/Madrid'; exit when (following at time zone 'Europe/Madrid')=wall; wall:=wall+interval '1 minute'; end loop;
  if ((following-interval '1 hour') at time zone 'Europe/Madrid')=wall then following:=following-interval '1 hour'; end if;
  return jsonb_build_object('date',day,'timezone','Europe/Madrid','day_started_at',boundary,'next_day_at',following);
end $$;
revoke all on function "Task".sync_day(timestamptz,text) from public,anon;
grant execute on function "Task".sync_day(timestamptz,text) to authenticated;

create function "Task".sync_snapshot() returns jsonb
language plpgsql volatile security invoker set search_path=pg_catalog as $$
declare uid uuid:=auth.uid(); t text; data jsonb:='{}'; records jsonb; prefs jsonb; day_data jsonb;
begin
  if uid is null then raise exception 'Authentication required'; end if;
  perform pg_advisory_xact_lock(hashtextextended(uid::text,4781));
  foreach t in array array['category','projects','tasks','routine','routine_period','routine_completion','user_settings'] loop
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

create function "Task".sync_context_day(p_user uuid,p_context jsonb) returns date
language plpgsql stable security invoker set search_path=pg_catalog as $$
declare recorded timestamptz; target date; reset text; original jsonb;
begin
  if not(p_context ? 'recorded_at') then
    select routine_reset_time into reset from "Task".user_settings where user_id=p_user;
    return ("Task".sync_day(clock_timestamp(),coalesce(reset,'00:00'))->>'date')::date;
  end if;
  recorded:=(p_context->>'recorded_at')::timestamptz;
  reset:=p_context->>'reset_time'; target:=(p_context->>'day')::date;
  if recorded is null or target is null or recorded>clock_timestamp()+interval '5 minutes' or reset is null or reset !~ '^([01][0-9]|2[0-3]):[0-5][0-9]$' then raise exception 'Contexto temporal no válido'; end if;
  if target<>("Task".sync_day(recorded,reset)->>'date')::date or target>("Task".sync_day(clock_timestamp(),reset)->>'date')::date then raise exception 'El día no coincide con el contexto original'; end if;
  select state into original from "Task".sync_history where user_id=p_user and entity='user_settings' and id=p_user and revision=(p_context->>'settings_revision')::bigint and not deleted;
  if (original is null and (coalesce((p_context->>'settings_revision')::bigint,0)<>0 or reset<>'00:00')) or (original is not null and original->>'routine_reset_time'<>reset) then raise exception 'Ajustes originales no válidos'; end if;
  return target;
end $$;
revoke all on function "Task".sync_context_day(uuid,jsonb) from public,anon,authenticated;

-- Protected receipts require a narrowly scoped definer entry point. All table
-- identifiers/fields are whitelisted; every read and mutation is scoped to uid.
-- Trigger tracking is not bypassed, and the caller can never supply user_id.
create function "Task".sync_apply(p_operation jsonb) returns jsonb
language plpgsql security definer set search_path=pg_catalog as $$
<<op>>
declare uid uuid:=auth.uid(); op_id uuid; item uuid; entity text; action text; base jsonb; patch jsonb; context jsonb;
  old_receipt "Task".sync_operations; current_row "Task".sync_entities; remote jsonb; merged jsonb; result jsonb;
  allowed text[]; k text; conflict_fields text[]:='{}'; sql_sets text; row_result jsonb;
  reset text; day_data jsonb; target_day date; recorded timestamptz; historic jsonb; known_revision bigint; routine_id uuid;
  was_active boolean; start_day date; stored_id uuid; diagnostic text; known_state jsonb; activation_receipt "Task".sync_operations;
begin
  if uid is null then raise exception 'Authentication required'; end if;
  if p_operation->>'protocol' is distinct from '1' then raise exception 'Actualiza la aplicación: protocolo no compatible'; end if;
  op_id:=(p_operation->>'operation_id')::uuid; item:=(p_operation->>'id')::uuid;
  entity:=p_operation->>'entity'; action:=p_operation->>'action'; base:=p_operation->'base'; patch:=coalesce(p_operation->'changes','{}'); context:=coalesce(p_operation->'context','{}');
  if op_id is null or item is null or entity is null or action is null or entity not in ('tasks','projects','category','routine','routine_completion','user_settings') or action not in ('create','update','delete','complete') or jsonb_typeof(patch)<>'object' then raise exception 'Operación no válida'; end if;
  if (entity='routine_completion' and action<>'complete') or (entity<>'routine_completion' and action='complete') or (entity='user_settings' and action<>'update') then raise exception 'Acción no válida para este elemento'; end if;
  perform pg_advisory_xact_lock(hashtextextended(uid::text,4781));
  select * into old_receipt from "Task".sync_operations where user_id=uid and operation_id=op_id;
  if found then
    if old_receipt.request<>p_operation then raise exception 'El identificador de operación ya se utilizó con otro contenido'; end if;
    return old_receipt.result;
  end if;
  perform set_config('studycalendar.sync_user',uid::text,true);
  begin
    allowed:=case entity
      when 'tasks' then array['title','date','start_time','end_time','all_day','completed','category_id','project_id','task_type','color','prerequisite_ids']
      when 'projects' then array['name','color','status'] when 'category' then array['name','color']
      when 'routine' then array['title','color','active'] when 'routine_completion' then array['completed']
      else array['routine_reset_time','calendar_start_time','calendar_end_time','week_start'] end;
    for k in select jsonb_object_keys(patch) loop
      if not(k=any(allowed)) then raise exception 'Campo no permitido: %',k; end if;
      if patch->k='null'::jsonb and k not in ('date','start_time','end_time','category_id','project_id') then raise exception 'El campo % no admite null',k; end if;
      if k in ('completed','all_day','active') and jsonb_typeof(patch->k)<>'boolean' then raise exception 'El campo % debe ser booleano',k; end if;
      if k in ('start_time','end_time') and patch->k<>'null'::jsonb then patch:=jsonb_set(patch,array[k],to_jsonb((patch->>k)::time)); end if;
    end loop;
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
      if jsonb_typeof(base->'state')<>'object' then raise exception 'Falta la versión base de la edición'; end if;
      if action='delete' then
        if (base->>'revision')::bigint is distinct from current_row.revision then conflict_fields:=array['delete']; end if;
      else
        if entity='routine' and patch ? 'active' and base->'state'->'periods' is distinct from remote->'periods' then conflict_fields:=array['active']; end if;
        for k in select jsonb_object_keys(patch) loop
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
      merged:=coalesce(remote,'{}')||patch;
      if entity='user_settings' and item<>uid then raise exception 'Ajustes de otra cuenta'; end if;
      if entity in ('tasks','projects','category','routine') then
        if action='create' then
          if entity='tasks' then
            insert into "Task".tasks(id,user_id,title,date,start_time,end_time,color,completed,category_id,project_id,task_type,all_day,prerequisite_ids)
            values(item,uid,trim(patch->>'title'),(patch->>'date')::date,(patch->>'start_time')::time,(patch->>'end_time')::time,coalesce(patch->>'color','#4F46E5'),coalesce((patch->>'completed')::boolean,false),(patch->>'category_id')::uuid,(patch->>'project_id')::uuid,coalesce(patch->>'task_type','daily'),coalesce((patch->>'all_day')::boolean,false),coalesce(array(select jsonb_array_elements_text(patch->'prerequisite_ids')::uuid),'{}'));
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
    if SQLSTATE='P0001' then diagnostic:=SQLERRM; else diagnostic:='La combinación no cumple las reglas. Revisa títulos, nombres, fechas, horarios y asociaciones.'; end if;
    result:=jsonb_build_object('status','rejected','reason','validation','message',diagnostic);
  end;
  insert into "Task".sync_operations(user_id,operation_id,request,result) values(uid,op_id,p_operation,result);
  perform set_config('studycalendar.sync_user','',true);
  return result;
end $$;
revoke all on function "Task".sync_apply(jsonb) from public,anon;
grant execute on function "Task".sync_apply(jsonb) to authenticated;

notify pgrst, 'reload schema';
