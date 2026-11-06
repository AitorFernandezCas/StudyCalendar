-- Isolated database BEFORE project_plans only. Migration and fixtures roll back.
begin;
do $$ begin
  if exists(select 1 from information_schema.columns where table_schema='Task' and table_name='tasks' and column_name='prerequisite_ids')
  then raise exception 'Use an isolated database BEFORE project_plans'; end if;
end $$;
insert into auth.users(id) values ('10000000-0000-0000-0000-000000000001'),('10000000-0000-0000-0000-000000000002');
insert into "Task".projects(id,user_id,name,color) values
 ('20000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001','Plan','#7c5cff'),
 ('20000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000002','Other','#7c5cff');
insert into "Task".tasks(id,user_id,title,task_type,project_id,date,start_time,end_time,color) values
 ('30000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001','A','project','20000000-0000-0000-0000-000000000001','2026-11-06','09:00','10:00','#7c5cff'),
 ('30000000-0000-0000-0000-000000000004','10000000-0000-0000-0000-000000000002','Foreign','project','20000000-0000-0000-0000-000000000002','2026-11-06','09:00','10:00','#7c5cff');
create temporary table before_plan as select md5(to_jsonb(t)::text) fingerprint from "Task".tasks t;
\ir ../supabase/migrations/20261106122252_project_plans.sql
do $$ begin
  if exists(select fingerprint from before_plan except select md5((to_jsonb(t)-'prerequisite_ids')::text) from "Task".tasks t)
  then raise exception 'Migration changed existing task fields'; end if;
end $$;
create function pg_temp.reject(statement text) returns void language plpgsql as $$
begin
  begin execute statement;
    set constraints all immediate;
  exception when raise_exception or check_violation or insufficient_privilege then return;
  end;
  raise exception 'Invalid operation accepted: %',statement;
end $$;
set local role authenticated;
select set_config('request.jwt.claim.sub','10000000-0000-0000-0000-000000000001',true);
insert into "Task".tasks(id,user_id,title,task_type,project_id,color,prerequisite_ids) values
 ('30000000-0000-0000-0000-000000000002','10000000-0000-0000-0000-000000000001','B','project','20000000-0000-0000-0000-000000000001','#7c5cff',array['30000000-0000-0000-0000-000000000001']::uuid[]),
 ('30000000-0000-0000-0000-000000000003','10000000-0000-0000-0000-000000000001','C','project','20000000-0000-0000-0000-000000000001','#7c5cff','{}');
select pg_temp.reject($q$update "Task".tasks set completed=true where title='B'$q$);
select pg_temp.reject($q$delete from "Task".tasks where title='A'$q$);
select pg_temp.reject($q$update "Task".tasks set prerequisite_ids=array['30000000-0000-0000-0000-000000000002']::uuid[] where title='A'$q$);
select pg_temp.reject($q$update "Task".tasks set prerequisite_ids=array[id] where title='C'$q$);
select pg_temp.reject($q$update "Task".tasks set prerequisite_ids=array['30000000-0000-0000-0000-000000000004']::uuid[] where title='C'$q$);
select pg_temp.reject($q$update "Task".tasks set project_id=null where title='B'$q$);
select pg_temp.reject($q$update "Task".tasks set task_type='daily' where title='B'$q$);
update "Task".tasks set completed=true where title='A';
update "Task".tasks set completed=true where title='B';
select pg_temp.reject($q$update "Task".tasks set completed=false where title='A'$q$);
select pg_temp.reject($q$update "Task".tasks set prerequisite_ids=array['30000000-0000-0000-0000-000000000003']::uuid[] where title='B'$q$);
-- Check owner isolation independently of the dependency validation.
do $$ begin
  if (select count(*) from "Task".tasks) <> 3 then raise exception 'RLS leaked tasks'; end if;
end $$;
delete from "Task".projects where id='20000000-0000-0000-0000-000000000001';
set constraints all immediate;
do $$ begin
  if exists(select 1 from "Task".tasks) then raise exception 'Project tasks survived deletion'; end if;
end $$;
reset role;
do $$ begin
  if (select count(*) from "Task".tasks) <> 1 then raise exception 'Other user tasks changed'; end if;
end $$;
-- Preserve the existing Auth user-deletion cascade with connected plan tasks.
set constraints all deferred;
insert into "Task".tasks(id,user_id,title,task_type,project_id,color,prerequisite_ids) values
 ('30000000-0000-0000-0000-000000000005','10000000-0000-0000-0000-000000000002','Other dependent','project','20000000-0000-0000-0000-000000000002','#7c5cff',array['30000000-0000-0000-0000-000000000004']::uuid[]);
delete from auth.users where id='10000000-0000-0000-0000-000000000002';
set constraints all immediate;
do $$ begin
  if exists(select 1 from "Task".tasks) then raise exception 'Auth cascade failed'; end if;
end $$;
rollback;
