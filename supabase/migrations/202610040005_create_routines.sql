create table if not exists "Task".routine (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references auth.users(id) on delete cascade,
  title text not null check (char_length(trim(title)) > 0),
  category_id uuid references "Task".category(id) on delete set null,
  start_time time not null,
  end_time time not null,
  color text not null check (color ~ '^#[0-9A-Fa-f]{6}$'),
  active boolean not null default true,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint routine_end_after_start check (end_time > start_time)
);

create table if not exists "Task".routine_period (
  id uuid primary key default gen_random_uuid(),
  routine_id uuid not null references "Task".routine(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  starts_on date not null,
  ends_on date,
  constraint routine_period_end_after_start check (ends_on is null or ends_on >= starts_on)
);

create table if not exists "Task".routine_completion (
  id uuid primary key default gen_random_uuid(),
  routine_id uuid not null references "Task".routine(id) on delete cascade,
  user_id uuid not null references auth.users(id) on delete cascade,
  occurrence_date date not null,
  completed boolean not null default false,
  unique (routine_id, occurrence_date)
);

create index if not exists routine_user_idx on "Task".routine (user_id, active);
create index if not exists routine_period_range_idx on "Task".routine_period (user_id, starts_on, ends_on);
create index if not exists routine_completion_date_idx on "Task".routine_completion (user_id, occurrence_date);

alter table "Task".routine enable row level security;
alter table "Task".routine_period enable row level security;
alter table "Task".routine_completion enable row level security;

grant usage on schema "Task" to authenticated;
grant select, insert, update, delete on "Task".routine to authenticated;
grant select, insert, update, delete on "Task".routine_period to authenticated;
grant select, insert, update, delete on "Task".routine_completion to authenticated;

drop policy if exists "Users can view their own routines" on "Task".routine;
create policy "Users can view their own routines" on "Task".routine for select to authenticated using ((select auth.uid()) = user_id);
drop policy if exists "Users can create their own routines" on "Task".routine;
create policy "Users can create their own routines" on "Task".routine for insert to authenticated with check ((select auth.uid()) = user_id);
drop policy if exists "Users can update their own routines" on "Task".routine;
create policy "Users can update their own routines" on "Task".routine for update to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
drop policy if exists "Users can delete their own routines" on "Task".routine;
create policy "Users can delete their own routines" on "Task".routine for delete to authenticated using ((select auth.uid()) = user_id);

drop policy if exists "Users can view their own routine periods" on "Task".routine_period;
create policy "Users can view their own routine periods" on "Task".routine_period for select to authenticated using ((select auth.uid()) = user_id);
drop policy if exists "Users can create their own routine periods" on "Task".routine_period;
create policy "Users can create their own routine periods" on "Task".routine_period for insert to authenticated with check ((select auth.uid()) = user_id);
drop policy if exists "Users can update their own routine periods" on "Task".routine_period;
create policy "Users can update their own routine periods" on "Task".routine_period for update to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
drop policy if exists "Users can delete their own routine periods" on "Task".routine_period;
create policy "Users can delete their own routine periods" on "Task".routine_period for delete to authenticated using ((select auth.uid()) = user_id);

drop policy if exists "Users can view their own routine completions" on "Task".routine_completion;
create policy "Users can view their own routine completions" on "Task".routine_completion for select to authenticated using ((select auth.uid()) = user_id);
drop policy if exists "Users can create their own routine completions" on "Task".routine_completion;
create policy "Users can create their own routine completions" on "Task".routine_completion for insert to authenticated with check ((select auth.uid()) = user_id);
drop policy if exists "Users can update their own routine completions" on "Task".routine_completion;
create policy "Users can update their own routine completions" on "Task".routine_completion for update to authenticated using ((select auth.uid()) = user_id) with check ((select auth.uid()) = user_id);
drop policy if exists "Users can delete their own routine completions" on "Task".routine_completion;
create policy "Users can delete their own routine completions" on "Task".routine_completion for delete to authenticated using ((select auth.uid()) = user_id);

drop trigger if exists routine_updated_at on "Task".routine;
create trigger routine_updated_at before update on "Task".routine for each row execute function "Task".set_tasks_updated_at();
