-- Execute against the migrated database. All fixtures and writes are rolled back.
begin;
create temporary table habit_fixture (label text, user_id uuid, routine_id uuid);
insert into habit_fixture values
  ('empty', gen_random_uuid(), gen_random_uuid()),
  ('long', null, gen_random_uuid()),
  ('gap', null, gen_random_uuid()),
  ('paused', null, gen_random_uuid()),
  ('future', null, gen_random_uuid()),
  ('other', gen_random_uuid(), gen_random_uuid());
update habit_fixture set user_id = (select user_id from habit_fixture where label = 'empty') where user_id is null;
insert into auth.users (id) select distinct user_id from habit_fixture;
insert into "Task".routine (id,user_id,title,color,active)
select routine_id,user_id,label,'#7c5cff',label <> 'paused' from habit_fixture;
insert into "Task".routine_period (routine_id,user_id,starts_on,ends_on)
select routine_id,user_id,
  case when label = 'future' then date '2026-10-06' else date '2020-01-01' end,
  case when label = 'paused' then date '2026-10-02' else null end
from habit_fixture;
insert into "Task".routine_completion(routine_id,user_id,occurrence_date,completed)
select f.routine_id,f.user_id,date '2026-10-05' - n,true
from habit_fixture f cross join generate_series(1,1005) n where f.label='long';
insert into "Task".routine_completion(routine_id,user_id,occurrence_date,completed)
select f.routine_id,f.user_id,date '2026-10-05' - n,true
from habit_fixture f cross join generate_series(10,15) n where f.label='gap';
insert into "Task".routine_completion(routine_id,user_id,occurrence_date,completed)
select routine_id,user_id,date '2026-10-04',true from habit_fixture where label='gap';
insert into "Task".routine_completion(routine_id,user_id,occurrence_date,completed)
select f.routine_id,f.user_id,date '2026-10-05' - n,true
from habit_fixture f cross join generate_series(3,7) n where f.label='paused';
insert into "Task".routine_completion(routine_id,user_id,occurrence_date,completed)
select routine_id,user_id,date '2026-10-06',true from habit_fixture where label='future';
grant select on habit_fixture to authenticated;
select set_config('request.jwt.claims', json_build_object('sub',(select user_id from habit_fixture where label='empty'),'role','authenticated')::text,true);
select set_config('request.jwt.claim.sub', (select user_id::text from habit_fixture where label='empty'),true);
set local role authenticated;
do $$
declare r record;
begin
  if (select count(*) from "Task".routine_summaries(date '2026-10-05')) <> 5 then raise exception 'RLS routine isolation failed'; end if;
  if exists(select 1 from "Task".routine where title='other') then raise exception 'RLS direct isolation failed'; end if;
  select * into r from "Task".routine_summaries(date '2026-10-05') where title='empty';
  if r.current_streak <> 0 or r.max_streak <> 0 or not r.due_today or r.completed_today then raise exception 'empty routine failed'; end if;
  select * into r from "Task".routine_summaries(date '2026-10-05') where title='long';
  if r.current_streak <> 1005 or r.max_streak <> 1005 then raise exception '1005-day history was truncated: %',row_to_json(r); end if;
  select * into r from "Task".routine_summaries(date '2026-10-05') where title='gap';
  if r.current_streak <> 1 or r.max_streak <> 6 then raise exception 'gap failed'; end if;
  select * into r from "Task".routine_summaries(date '2026-10-05') where title='paused';
  if r.current_streak <> 0 or r.max_streak <> 5 or r.due_today then raise exception 'pause failed'; end if;
  select * into r from "Task".routine_summaries(date '2026-10-05') where title='future';
  if r.current_streak <> 0 or r.max_streak <> 0 or r.due_today then raise exception 'future completion counted'; end if;
  insert into "Task".routine_completion(routine_id,user_id,occurrence_date,completed)
    select routine_id,user_id,date '2026-10-05',true from habit_fixture where label='long';
  select * into r from "Task".routine_summaries(date '2026-10-05') where title='long';
  if r.current_streak <> 1006 or r.max_streak <> 1006 or not r.completed_today then raise exception 'completion failed'; end if;
  update "Task".routine_completion set completed=false where routine_id=(select routine_id from habit_fixture where label='long') and occurrence_date=date '2026-10-05';
  select * into r from "Task".routine_summaries(date '2026-10-05') where title='long';
  if r.current_streak <> 1005 or r.max_streak <> 1005 or r.completed_today then raise exception 'uncompletion failed'; end if;
  select * into r from "Task".routine_summaries(date '2026-10-06') where title='long';
  if r.current_streak <> 0 or r.max_streak <> 1005 then raise exception 'missed yesterday failed'; end if;
  update "Task".routine_completion set completed=false where routine_id=(select routine_id from habit_fixture where label='long') and occurrence_date=date '2026-10-05' - 500;
  select * into r from "Task".routine_summaries(date '2026-10-05') where title='long';
  if r.current_streak <> 499 or r.max_streak <> 505 then raise exception 'record recalculation failed'; end if;
  update "Task".routine set active=true where id=(select routine_id from habit_fixture where label='paused');
  insert into "Task".routine_period(routine_id,user_id,starts_on)
    select routine_id,user_id,date '2026-10-05' from habit_fixture where label='paused';
  insert into "Task".routine_completion(routine_id,user_id,occurrence_date,completed)
    select routine_id,user_id,date '2026-10-05',true from habit_fixture where label='paused';
  select * into r from "Task".routine_summaries(date '2026-10-05') where title='paused';
  if r.current_streak <> 1 or r.max_streak <> 5 then raise exception 'reactivation failed'; end if;
end $$;
rollback;
