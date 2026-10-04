create index if not exists routine_period_user_routine_range_idx
  on "Task".routine_period (user_id, routine_id, starts_on, ends_on);
