create table if not exists "Task".category (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  name text not null check (char_length(trim(name)) > 0),
  color text not null default '#7c5cff' check (color ~ '^#[0-9A-Fa-f]{6}$'),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create unique index if not exists category_user_name_unique_idx
  on "Task".category (user_id, lower(name));

alter table "Task".tasks add column if not exists category_id uuid;

insert into "Task".category (user_id, name, color)
select distinct user_id, trim(subject), '#7c5cff'
from "Task".tasks
where subject is not null and char_length(trim(subject)) > 0
on conflict (user_id, lower(name)) do nothing;

update "Task".tasks as task
set category_id = category.id
from "Task".category as category
where category.user_id = task.user_id
  and lower(category.name) = lower(trim(task.subject))
  and task.category_id is null;

update "Task".category as category
set color = source.color
from (
  select distinct on (user_id, lower(trim(subject)))
    user_id, lower(trim(subject)) as normalized_name, color
  from "Task".tasks
  where subject is not null and char_length(trim(subject)) > 0
  order by user_id, lower(trim(subject)), created_at
) as source
where category.user_id = source.user_id
  and lower(category.name) = source.normalized_name
  and source.color ~ '^#[0-9A-Fa-f]{6}$';

do $$
begin
  if not exists (
    select 1 from pg_constraint
    where conname = 'tasks_category_id_fkey'
      and conrelid = '"Task".tasks'::regclass
  ) then
    alter table "Task".tasks
      add constraint tasks_category_id_fkey
      foreign key (category_id) references "Task".category(id) on delete set null;
  end if;
end
$$;

create index if not exists tasks_user_category_idx on "Task".tasks (user_id, category_id);

alter table "Task".tasks drop column if exists subject;

alter table "Task".category enable row level security;
grant usage on schema "Task" to authenticated;
grant select, insert, update, delete on "Task".category to authenticated;

drop policy if exists "Users can view their own categories" on "Task".category;
create policy "Users can view their own categories" on "Task".category
  for select to authenticated using ((select auth.uid()) = user_id);

drop policy if exists "Users can create their own categories" on "Task".category;
create policy "Users can create their own categories" on "Task".category
  for insert to authenticated with check ((select auth.uid()) = user_id);

drop policy if exists "Users can update their own categories" on "Task".category;
create policy "Users can update their own categories" on "Task".category
  for update to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

drop policy if exists "Users can delete their own categories" on "Task".category;
create policy "Users can delete their own categories" on "Task".category
  for delete to authenticated using ((select auth.uid()) = user_id);

create or replace function "Task".set_category_updated_at()
returns trigger
language plpgsql
set search_path = pg_catalog
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

drop trigger if exists category_updated_at on "Task".category;
create trigger category_updated_at before update on "Task".category
for each row execute function "Task".set_category_updated_at();
