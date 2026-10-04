create schema if not exists "Task";

alter table if exists public.tasks set schema "Task";

do $$
begin
  if exists (
    select 1
    from pg_proc p
    join pg_namespace n on n.oid = p.pronamespace
    where n.nspname = 'public' and p.proname = 'set_tasks_updated_at'
  ) then
    alter function public.set_tasks_updated_at() set schema "Task";
  end if;
end
$$;

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
