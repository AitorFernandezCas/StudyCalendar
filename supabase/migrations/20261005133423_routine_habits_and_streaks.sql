-- Habit definitions retain their IDs, ownership, active periods and completion history.
alter table "Task".routine
  drop constraint if exists routine_end_after_start,
  drop column start_time,
  drop column end_time;

create index if not exists routine_completion_streak_idx
  on "Task".routine_completion (user_id, routine_id, occurrence_date)
  where completed = true;

create or replace function "Task".routine_summaries(p_today date)
returns table (
  id uuid, title text, color text, active boolean,
  created_at timestamptz, updated_at timestamptz,
  due_today boolean, completed_today boolean,
  current_streak integer, max_streak integer
)
language sql
stable
security invoker
set search_path = ''
as $$
  with owned as (
    select r.* from "Task".routine r
    where r.user_id = (select auth.uid())
  ), numbered as (
    select c.routine_id, c.occurrence_date,
      c.occurrence_date - (row_number() over (
        partition by c.routine_id order by c.occurrence_date
      ))::integer as run_key
    from "Task".routine_completion c
    join owned r on r.id = c.routine_id and r.user_id = c.user_id
    where c.completed = true and c.occurrence_date <= p_today
  ), runs as (
    select routine_id, max(occurrence_date) as ends_on, count(*)::integer as days
    from numbered group by routine_id, run_key
  ), streaks as (
    select routine_id, max(days)::integer as maximum,
      max(days) filter (where ends_on in (p_today, p_today - 1))::integer as current
    from runs group by routine_id
  )
  select r.id, r.title, r.color, r.active, r.created_at, r.updated_at,
    scheduled.due_today,
    exists (
      select 1 from "Task".routine_completion c
      where c.routine_id = r.id and c.user_id = r.user_id
        and c.occurrence_date = p_today and c.completed = true
    ),
    case when scheduled.due_today then coalesce(s.current, 0) else 0 end,
    coalesce(s.maximum, 0)
  from owned r
  cross join lateral (
    select r.active and exists (
      select 1 from "Task".routine_period p
      where p.routine_id = r.id and p.user_id = r.user_id
        and p.starts_on <= p_today and (p.ends_on is null or p.ends_on >= p_today)
    ) as due_today
  ) scheduled
  left join streaks s on s.routine_id = r.id
  order by lower(r.title), r.id;
$$;

revoke all on function "Task".routine_summaries(date) from public, anon;
grant execute on function "Task".routine_summaries(date) to authenticated;
