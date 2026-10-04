alter table "Task".tasks
  add column if not exists task_type text not null default 'daily';

update "Task".tasks
set task_type = 'daily'
where task_type is null or task_type not in ('routine', 'project', 'daily');

alter table "Task".tasks
  drop constraint if exists tasks_task_type_check;

alter table "Task".tasks
  add constraint tasks_task_type_check
  check (task_type in ('routine', 'project', 'daily'));
