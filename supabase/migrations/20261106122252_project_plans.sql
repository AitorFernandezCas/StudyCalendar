-- Apply only after isolated verification and a private backup; never automatic.

alter table "Task".tasks

  add column prerequisite_ids uuid[] not null default '{}',

  alter column date drop not null,

  alter column start_time drop not null,

  alter column end_time drop not null;

alter table "Task".tasks add constraint tasks_schedule_complete check (

  (date is null and start_time is null and end_time is null and task_type = 'project' and not all_day)

  or (date is not null and start_time is not null and end_time is not null and end_time > start_time)

);

create index tasks_prerequisites_idx on "Task".tasks using gin(prerequisite_ids);



create function "Task".validate_task_plan() returns trigger

language plpgsql security invoker set search_path = pg_catalog as $$

declare item uuid; previous_project uuid; current_project uuid;

begin

  if TG_OP <> 'INSERT' then previous_project := old.project_id; end if;

  if TG_OP <> 'DELETE' then current_project := new.project_id; end if;

  -- Serialize all writes to a project, including completion and graph changes.

  perform id from "Task".projects where id in (previous_project,current_project) order by id for update;

  if TG_OP = 'DELETE' then return old; end if;
  if TG_OP = 'UPDATE' then

    if (old.project_id is distinct from new.project_id or old.task_type <> new.task_type or old.user_id <> new.user_id)

      and (cardinality(old.prerequisite_ids) > 0 or exists(select 1 from "Task".tasks where prerequisite_ids @> array[old.id]))

    then raise exception 'Retira primero las conexiones de esta tarea'; end if;

  end if;

  if new.project_id is not null and not exists (

    select 1 from "Task".projects where id=new.project_id and user_id=new.user_id

  ) then raise exception 'Proyecto no disponible'; end if;

  if cardinality(new.prerequisite_ids) <> (select count(distinct x) from unnest(new.prerequisite_ids) x)

    then raise exception 'Las tareas previas deben ser únicas y no nulas'; end if;

  if cardinality(new.prerequisite_ids) > 0 and (new.project_id is null or new.task_type <> 'project')

    then raise exception 'Las conexiones requieren un proyecto'; end if;

  foreach item in array new.prerequisite_ids loop

    if item = new.id or not exists (

      select 1 from "Task".tasks where id=item and user_id=new.user_id and project_id=new.project_id and task_type='project'

    ) then raise exception 'Las tareas previas deben pertenecer al mismo proyecto'; end if;

    if new.completed and exists(select 1 from "Task".tasks where id=item and not completed)

      then raise exception 'Completa primero todas las tareas previas'; end if;

  end loop;

  if not new.completed and exists(select 1 from "Task".tasks where prerequisite_ids @> array[new.id] and completed)

    then raise exception 'Reabre primero las tareas dependientes completadas'; end if;

  if exists (

    with recursive ancestors(id) as (

      select unnest(new.prerequisite_ids)

      union

      select unnest(t.prerequisite_ids) from "Task".tasks t join ancestors a on t.id=a.id

    ) select 1 from ancestors where id=new.id

  ) then raise exception 'Las conexiones no pueden formar un ciclo'; end if;

  return new;

end $$;

revoke all on function "Task".validate_task_plan() from public, anon, authenticated;

create trigger task_plan_guard before insert or update or delete on "Task".tasks

for each row execute function "Task".validate_task_plan();



-- Validate deletions at transaction end so Auth account cascades can delete
-- tasks and projects in either order, while isolated task deletes still fail.
create function "Task".validate_task_plan_deletion() returns trigger
language plpgsql security invoker set search_path = pg_catalog as $$
begin
  if old.project_id is not null and not exists(select 1 from "Task".projects where id=old.project_id)
    then return null; end if;
  if cardinality(old.prerequisite_ids) > 0 or exists (
    select 1 from "Task".tasks where prerequisite_ids @> array[old.id]
  ) then raise exception 'Retira primero las conexiones de esta tarea'; end if;
  return null;
end $$;
revoke all on function "Task".validate_task_plan_deletion() from public, anon, authenticated;
create constraint trigger task_plan_deletion after delete on "Task".tasks
  deferrable initially deferred for each row execute function "Task".validate_task_plan_deletion();

-- All writes are one transaction. Remove links before removing tasks so individual

-- deletion guards remain strict, without a caller-controlled bypass flag.

create function "Task".delete_project_plan() returns trigger

language plpgsql security invoker set search_path = pg_catalog as $$

begin

  update "Task".tasks set prerequisite_ids='{}' where project_id=old.id;

  delete from "Task".tasks where project_id=old.id;

  return old;

end $$;

revoke all on function "Task".delete_project_plan() from public, anon, authenticated;

create trigger delete_project_plan before delete on "Task".projects

for each row execute function "Task".delete_project_plan();

