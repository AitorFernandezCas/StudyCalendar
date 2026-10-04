create table if not exists "Task".projects (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null check (char_length(trim(name)) > 0),
  color text not null default '#ee7b6f' check (color ~ '^#[0-9A-Fa-f]{6}$'),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create unique index if not exists project_user_name_unique_idx
  on "Task".projects (user_id, lower(name));

alter table "Task".tasks
  add column if not exists project_id uuid;

do $$
begin
  if not exists (
    select 1 from pg_constraint
    where conname = 'tasks_project_id_fkey'
      and conrelid = '"Task".tasks'::regclass
  ) then
    alter table "Task".tasks
      add constraint tasks_project_id_fkey
      foreign key (project_id) references "Task".projects(id) on delete set null;
  end if;
end
$$;

create index if not exists tasks_user_project_idx on "Task".tasks (user_id, project_id);

alter table "Task".projects enable row level security;
grant usage on schema "Task" to authenticated;
grant select, insert, update, delete on "Task".projects to authenticated;

drop policy if exists "Users can view their own projects" on "Task".projects;
create policy "Users can view their own projects" on "Task".projects
  for select to authenticated using ((select auth.uid()) = user_id);

drop policy if exists "Users can create their own projects" on "Task".projects;
create policy "Users can create their own projects" on "Task".projects
  for insert to authenticated with check ((select auth.uid()) = user_id);

drop policy if exists "Users can update their own projects" on "Task".projects;
create policy "Users can update their own projects" on "Task".projects
  for update to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists "Users can delete their own projects" on "Task".projects;
create policy "Users can delete their own projects" on "Task".projects
  for delete to authenticated using ((select auth.uid()) = user_id);

create or replace function "Task".set_project_updated_at()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

drop trigger if exists project_updated_at on "Task".projects;
create trigger project_updated_at before update on "Task".projects
for each row execute function "Task".set_project_updated_at();
