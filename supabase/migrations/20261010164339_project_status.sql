-- Existing projects remain active. Task rows, ownership and RLS stay unchanged.
alter table "Task".projects
  add column status text not null default 'active'
  constraint projects_status_check check (status in ('active', 'inactive', 'completed'));
