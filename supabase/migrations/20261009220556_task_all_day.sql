-- Existing tasks retain their hours and remain timed. The internal interval and
-- ownership policies also apply to all-day tasks.
alter table "Task".tasks
  add column all_day boolean not null default false;
