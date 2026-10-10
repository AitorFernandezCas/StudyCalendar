-- Run ONLY against an isolated test database BEFORE the all_day migration.
-- The migration, fixtures and assertions all roll back. Requires an admin role.
begin;
do $$
begin
  if exists (select 1 from information_schema.columns
             where table_schema = 'Task' and table_name = 'tasks' and column_name = 'all_day') then
    raise exception 'Use a test database BEFORE the all_day migration';
  end if;
end;
$$;

create temporary table task_day_fixture (label text, user_id uuid, task_id uuid);
insert into task_day_fixture values
  ('own', gen_random_uuid(), gen_random_uuid()),
  ('other', gen_random_uuid(), gen_random_uuid());
insert into auth.users (id) select user_id from task_day_fixture;
insert into "Task".tasks (id,user_id,title,date,start_time,end_time,color,task_type)
select task_id,user_id,label,date '2026-10-07',time '08:30',time '10:15','#7c5cff','daily'
from task_day_fixture;
create temporary table task_day_before as
select id,start_time,end_time from "Task".tasks;

\ir ../supabase/migrations/20261009220556_task_all_day.sql

do $$
begin
  if exists (select 1 from "Task".tasks where all_day is distinct from false) then
    raise exception 'Existing tasks must default to timed';
  end if;
  if exists (select 1 from task_day_before b join "Task".tasks t using(id)
             where b.start_time is distinct from t.start_time or b.end_time is distinct from t.end_time) then
    raise exception 'Migration changed existing hours';
  end if;
  begin
    update "Task".tasks set all_day = null where id=(select task_id from task_day_fixture where label='own');
    raise exception 'all_day accepted null';
  exception when not_null_violation then null;
  end;
end;
$$;

grant select on task_day_fixture to authenticated;
select set_config('request.jwt.claims', json_build_object('sub',(select user_id from task_day_fixture where label='own'),'role','authenticated')::text,true);
select set_config('request.jwt.claim.sub',(select user_id::text from task_day_fixture where label='own'),true);
set local role authenticated;
do $$
declare own_id uuid; foreign_id uuid; changed integer;
begin
  select task_id into own_id from task_day_fixture where label='own';
  select task_id into foreign_id from task_day_fixture where label='other';
  if exists (select 1 from "Task".tasks where id=foreign_id) then
    raise exception 'RLS exposed another user task';
  end if;
  update "Task".tasks set all_day=true where id=own_id;
  if not (select all_day from "Task".tasks where id=own_id) then
    raise exception 'All-day conversion failed';
  end if;
  update "Task".tasks set date=date '2026-10-08',completed=true where id=own_id;
  if not (select all_day and start_time=time '08:30' and end_time=time '10:15' from "Task".tasks where id=own_id) then
    raise exception 'Sparse updates changed mode or hours';
  end if;
  begin
    update "Task".tasks set end_time=start_time where id=own_id;
    raise exception 'All-day task accepted an invalid interval';
  exception when check_violation then null;
  end;
  begin
    update "Task".tasks set start_time=null where id=own_id;
    raise exception 'All-day task accepted missing hours';
  exception when not_null_violation then null;
  end;
  update "Task".tasks set all_day=false where id=own_id;
  if (select all_day from "Task".tasks where id=own_id) then
    raise exception 'Timed conversion failed';
  end if;
  update "Task".tasks set all_day=true where id=foreign_id;
  get diagnostics changed = row_count;
  if changed <> 0 then raise exception 'RLS allowed a foreign update'; end if;
  begin
    insert into "Task".tasks (user_id,title,date,start_time,end_time,color,all_day)
    select user_id,'Foreign insert',date '2026-10-07',time '09:00',time '10:00','#7c5cff',true
    from task_day_fixture where label='other';
    raise exception 'RLS allowed a foreign insert';
  exception when insufficient_privilege then null;
  end;
end;
$$;
reset role;
rollback;
