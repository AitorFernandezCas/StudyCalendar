from datetime import date, timedelta
from typing import Any
from .domain import Task, task_command
from .ports import TaskRepository
from ..categories.ports import CategoryRepository
from ..projects.ports import ProjectRepository
from ..shared.domain import BusinessError, User
from ..shared.ports import Clock


def date_range(start: str | None, end: str | None, clock: Clock, optional: bool = False) -> tuple[date | None, date | None]:
    if optional and not start and not end:
        return None, None
    try:
        first = date.fromisoformat(start) if start else (None if optional else clock.local_date())
        last = date.fromisoformat(end) if end else (first if optional else first + timedelta(days=41))
    except (TypeError, ValueError):
        raise BusinessError("from and to must be ISO dates")
    if first is None or last is None or last < first or (last - first).days > 366:
        raise BusinessError("Invalid date range")
    return first, last


class TaskService:
    def __init__(self, repository: TaskRepository, categories: CategoryRepository, projects: ProjectRepository, user: User, clock: Clock):
        self.repository = repository
        self.categories = categories
        self.projects = projects
        self.user = user
        self.clock = clock

    def list(self, start: str | None = None, end: str | None = None) -> list[Task]:
        first, last = date_range(start, end, self.clock, optional=True)
        return self.repository.list(first, last)

    def validate_associations(self, payload: dict) -> None:
        category_id = payload.get("category_id")
        if category_id is not None and not self.categories.belongs_to(category_id, self.user.id):
            raise BusinessError("Category not found")
        project_id = payload.get("project_id")
        if payload.get("task_type") == "project" and not project_id:
            raise BusinessError("project_id is required for project tasks")
        if project_id is not None and not self.projects.belongs_to(project_id, self.user.id):
            raise BusinessError("Project not found")

    def create(self, raw: Any) -> Task:
        payload = task_command(raw, creating=True).payload()
        self.validate_associations(payload)
        payload["user_id"] = self.user.id
        return self.repository.create(payload)

    def update(self, identifier: str, raw: Any) -> Task:
        command = task_command(raw)
        command.require_changes()
        payload = command.payload()
        self.validate_associations(payload)
        result = self.repository.update(identifier, payload)
        if result is None:
            raise BusinessError("Task not found", "not_found")
        return result

    def delete(self, identifier: str) -> str:
        if not self.repository.delete(identifier):
            raise BusinessError("Task not found", "not_found")
        return identifier
