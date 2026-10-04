create schema if not exists "Task";

create table if not exists "Task".tasks (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  title text not null check (char_length(trim(title)) > 0),
  subject text not null,
  date date not null,
  start_time time not null,
  end_time time not null,
  color text not null,
  completed boolean not null default false,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint tasks_end_after_start check (end_time > start_time)
);

create index if not exists tasks_user_date_idx on "Task".tasks (user_id, date, start_time);

alter table "Task".tasks enable row level security;

grant usage on schema "Task" to authenticated;
grant select, insert, update, delete on "Task".tasks to authenticated;

drop policy if exists "Users can view their own tasks" on "Task".tasks;
create policy "Users can view their own tasks" on "Task".tasks
  for select to authenticated using ((select auth.uid()) = user_id);

drop policy if exists "Users can create their own tasks" on "Task".tasks;
create policy "Users can create their own tasks" on "Task".tasks
  for insert to authenticated with check ((select auth.uid()) = user_id);

drop policy if exists "Users can update their own tasks" on "Task".tasks;
create policy "Users can update their own tasks" on "Task".tasks
  for update to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists "Users can delete their own tasks" on "Task".tasks;
create policy "Users can delete their own tasks" on "Task".tasks
  for delete to authenticated using ((select auth.uid()) = user_id);

create or replace function "Task".set_tasks_updated_at()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

drop trigger if exists tasks_updated_at on "Task".tasks;
create trigger tasks_updated_at before update on "Task".tasks
for each row execute function "Task".set_tasks_updated_at();
