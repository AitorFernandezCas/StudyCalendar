-- Run ONLY against an isolated database BEFORE project_status. All changes roll back.
begin;
do $$
begin
  if exists (select 1 from information_schema.columns where table_schema='Task' and table_name='projects' and column_name='status') then
    raise exception 'Use an isolated database BEFORE project_status';
  end if;
end;
$$;
create temporary table project_status_fixture(label text,user_id uuid,project_id uuid,task_id uuid);
insert into project_status_fixture values
  ('own',gen_random_uuid(),gen_random_uuid(),gen_random_uuid()),
  ('other',gen_random_uuid(),gen_random_uuid(),gen_random_uuid());
insert into auth.users(id) select user_id from project_status_fixture;
insert into "Task".projects(id,user_id,name) select project_id,user_id,label from project_status_fixture;
insert into "Task".tasks(id,user_id,title,subject,date,start_time,end_time,color,project_id)
select task_id,user_id,label,'Test',date '2026-10-10',time '09:00',time '10:00','#ee7b6f',project_id from project_status_fixture;
create temporary table project_status_before as select id,row_to_json(p)::jsonb as original from "Task".projects p;
create temporary table project_status_tasks_before as select id,row_to_json(t)::jsonb as original from "Task".tasks t;
create temporary table project_status_policies_before as select * from pg_policies where schemaname='Task' and tablename='projects';

\ir ../supabase/migrations/20261010164339_project_status.sql

do $$
begin
  if exists(select 1 from "Task".projects where status is distinct from 'active') then
    raise exception 'Existing projects must be active';
  end if;
  if exists(select 1 from project_status_before b join "Task".projects p using(id) where b.original is distinct from (row_to_json(p)::jsonb - 'status')) then
    raise exception 'Migration changed previous project fields';
  end if;
  if exists((select * from project_status_policies_before) except (select * from pg_policies where schemaname='Task' and tablename='projects')) then
    raise exception 'Migration changed project policies';
  end if;
  if not (select relrowsecurity from pg_class where oid='"Task".projects'::regclass) then
    raise exception 'RLS was disabled';
  end if;
  begin
    update "Task".projects set status=null;
    raise exception 'Null status accepted';
  exception when not_null_violation then null;
  end;
  begin
    update "Task".projects set status='archived';
    raise exception 'Invalid status accepted';
  exception when check_violation then null;
  end;
end;
$$;
grant select on project_status_fixture to authenticated;
select set_config('request.jwt.claim.sub',(select user_id::text from project_status_fixture where label='own'),true);
set local role authenticated;
do $$
declare own_id uuid; other_id uuid; next_status text; affected integer;
begin
  select project_id into own_id from project_status_fixture where label='own';
  select project_id into other_id from project_status_fixture where label='other';
  if exists(select 1 from "Task".projects where id=other_id) then
    raise exception 'Foreign project exposed';
  end if;
  foreach next_status in array array['inactive','completed','active'] loop
    update "Task".projects set status=next_status where id=own_id;
    if (select status from "Task".projects where id=own_id) is distinct from next_status then
      raise exception 'Transition failed';
    end if;
    update "Task".projects set status=next_status where id=other_id;
    get diagnostics affected=row_count;
    if affected<>0 then raise exception 'Foreign project updated'; end if;
  end loop;
  update "Task".projects set name='Renamed' where id=own_id;
  if (select status from "Task".projects where id=own_id)<>'active' then raise exception 'Sparse update changed status'; end if;
  begin
    update "Task".projects set user_id=(select user_id from project_status_fixture where label='other') where id=own_id;
    raise exception 'Project ownership reassigned';
  exception when insufficient_privilege then null;
  end;
  insert into "Task".projects(user_id,name,status) values(auth.uid(),'New inactive','inactive');
  insert into "Task".projects(user_id,name) values(auth.uid(),'New default');
  if (select status from "Task".projects where name='New default')<>'active' then raise exception 'Creation default missing'; end if;
  begin
    insert into "Task".projects(user_id,name,status) values(auth.uid(),'new default','completed');
    raise exception 'Status bypassed unique project name';
  exception when unique_violation then null;
  end;
end;
$$;
reset role;
do $$
begin
  if exists(select 1 from project_status_tasks_before b join "Task".tasks t using(id) where b.original is distinct from row_to_json(t)::jsonb) then
    raise exception 'Status changes modified tasks or history';
  end if;
end;
$$;
set local role anon;
do $$
begin
  begin
    perform status from "Task".projects;
    raise exception 'Anonymous project access allowed';
  exception when insufficient_privilege then null;
  end;
end;
$$;
reset role;
rollback;
