-- Run on a migrated test database as an administrator. Fixtures are rolled back.
begin;
create temporary table settings_fixture (label text, user_id uuid);
insert into settings_fixture values ('own', gen_random_uuid()), ('other', gen_random_uuid());
insert into auth.users (id) select user_id from settings_fixture;
insert into "Task".user_settings(user_id, routine_reset_time)
  select user_id, '04:30' from settings_fixture where label = 'other';
grant select on settings_fixture to authenticated;
select set_config('request.jwt.claims', json_build_object('sub',
  (select user_id from settings_fixture where label = 'own'), 'role', 'authenticated')::text, true);
select set_config('request.jwt.claim.sub', (select user_id::text from settings_fixture where label = 'own'), true);
set local role authenticated;

do $$
declare n integer;
begin
  if exists(select 1 from "Task".user_settings) then raise exception 'Other preferences leaked'; end if;
  insert into "Task".user_settings(user_id) select user_id from settings_fixture where label = 'own';
  if not exists(select 1 from "Task".user_settings where routine_reset_time = '00:00'
    and calendar_start_time = '00:00' and calendar_end_time = '24:00' and week_start = 'monday') then
    raise exception 'Defaults failed';
  end if;
  update "Task".user_settings set routine_reset_time = '04:30', calendar_start_time = '07:30',
    calendar_end_time = '22:15', week_start = 'sunday';
  update "Task".user_settings set calendar_end_time = '23:15';
  if not exists(select 1 from "Task".user_settings where routine_reset_time = '04:30'
    and calendar_start_time = '07:30' and calendar_end_time = '23:15' and week_start = 'sunday') then
    raise exception 'Partial update lost preferences';
  end if;
  update "Task".user_settings set week_start = 'monday'
    where user_id = (select user_id from settings_fixture where label = 'other');
  get diagnostics n = row_count;
  if n <> 0 then raise exception 'Other preferences updated'; end if;
  begin
    insert into "Task".user_settings(user_id) select user_id from settings_fixture where label = 'other';
    raise exception 'Other preferences inserted';
  exception when insufficient_privilege then null;
  end;
  begin
    update "Task".user_settings set user_id = (select user_id from settings_fixture where label = 'other');
    raise exception 'Ownership reassigned';
  exception when insufficient_privilege then null;
  end;
  begin
    update "Task".user_settings set calendar_end_time = '06:00';
    raise exception 'Invalid range accepted';
  exception when check_violation then null;
  end;
  begin
    update "Task".user_settings set routine_reset_time = '24:00';
    raise exception 'Invalid reset time accepted';
  exception when check_violation then null;
  end;
  begin
    update "Task".user_settings set week_start = null;
    raise exception 'Null accepted';
  exception when not_null_violation then null;
  end;
  begin
    delete from "Task".user_settings;
    raise exception 'Delete granted unexpectedly';
  exception when insufficient_privilege then null;
  end;
end $$;

reset role;
set local role anon;
do $$
begin
  begin
    perform 1 from "Task".user_settings;
    raise exception 'Anonymous read granted';
  exception when insufficient_privilege then null;
  end;
  begin
    insert into "Task".user_settings(user_id) values (gen_random_uuid());
    raise exception 'Anonymous insert granted';
  exception when insufficient_privilege then null;
  end;
end $$;
reset role;
rollback;
