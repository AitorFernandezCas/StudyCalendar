"""Pure plan invariants, also enforced atomically by the persistence adapter's database."""
from .domain import Task
from ..shared.domain import BusinessError


def validate_plan(task: Task, tasks: list[Task], previous: Task | None = None, deleting=False):
    by_id = {row.id: row for row in tasks}
    dependents = [row for row in tasks if task.id in row.prerequisite_ids]
    if deleting or (previous and (previous.project_id != task.project_id or previous.task_type != task.task_type)):
        if (previous.prerequisite_ids if previous else task.prerequisite_ids) or dependents:
            raise BusinessError("Retira primero las conexiones de esta tarea", "conflict")
    if deleting:
        return
    if task.prerequisite_ids and (task.task_type != "project" or not task.project_id):
        raise BusinessError("Las tareas previas deben pertenecer al mismo proyecto")
    for identifier in task.prerequisite_ids:
        prerequisite = by_id.get(identifier)
        if identifier == task.id or not prerequisite or prerequisite.task_type != "project" or prerequisite.project_id != task.project_id:
            raise BusinessError("Las tareas previas deben pertenecer al mismo proyecto")
        if task.completed and not prerequisite.completed:
            raise BusinessError("Completa primero todas las tareas previas", "conflict")
    if not task.completed and any(row.completed for row in dependents):
        raise BusinessError("Reabre primero las tareas dependientes completadas", "conflict")
    pending = list(task.prerequisite_ids)
    seen = set()
    while pending:
        identifier = pending.pop()
        if identifier == task.id:
            raise BusinessError("Las conexiones no pueden formar un ciclo", "conflict")
        if identifier not in seen:
            seen.add(identifier)
            if identifier in by_id:
                pending.extend(by_id[identifier].prerequisite_ids)
