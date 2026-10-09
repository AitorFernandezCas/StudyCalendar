-- Additive migration. Apply manually after reconciling history and backing up.
create table "Task".user_settings (
  user_id uuid primary key references auth.users(id) on delete cascade,
  routine_reset_time text not null default '00:00'
    check (routine_reset_time ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'),
  calendar_start_time text not null default '00:00'
    check (calendar_start_time ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'),
  calendar_end_time text not null default '24:00'
    check (calendar_end_time = '24:00' or calendar_end_time ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'),
  week_start text not null default 'monday' check (week_start in ('monday', 'sunday')),
  constraint user_settings_calendar_range check (calendar_start_time < calendar_end_time)
);

alter table "Task".user_settings enable row level security;
revoke all on "Task".user_settings from public, anon, authenticated;
grant select, insert on "Task".user_settings to authenticated;
grant update (routine_reset_time, calendar_start_time, calendar_end_time, week_start)
  on "Task".user_settings to authenticated;

create policy user_settings_select on "Task".user_settings
  for select to authenticated using ((select auth.uid()) = user_id);
create policy user_settings_insert on "Task".user_settings
  for insert to authenticated with check ((select auth.uid()) = user_id);
create policy user_settings_update on "Task".user_settings
  for update to authenticated using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);
