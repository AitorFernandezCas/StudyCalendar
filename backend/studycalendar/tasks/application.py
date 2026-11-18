from dataclasses import replace
from datetime import date, timedelta
from typing import Any
from .planning import validate_plan
from .domain import Task, task_command
from .ports import TaskRepository
from ..categories.ports import CategoryRepository
from ..projects.ports import FeatureRepository, ProjectRepository
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
    def __init__(self, repository: TaskRepository, categories: CategoryRepository, projects: ProjectRepository, user: User, clock: Clock, features: FeatureRepository | None = None):
        self.repository = repository
        self.categories = categories
        self.projects = projects
        self.user = user
        self.clock = clock
        self.features = features

    def list(self, start: str | None = None, end: str | None = None) -> list[Task]:
        first, last = date_range(start, end, self.clock, optional=True)
        return self.repository.list(first, last)

    def validate_associations(self, payload: dict) -> None:
        category_id = payload.get("category_id")
        if category_id is not None and not self.categories.belongs_to(category_id, self.user.id):
            raise BusinessError("Category not found")
        project_id = payload.get("project_id")
        if project_id is not None and not self.projects.belongs_to(project_id, self.user.id):
            raise BusinessError("Project not found")

    def validate_feature(self, task: Task) -> None:
        if task.feature_id is not None and (
            task.task_type != "project" or task.project_id is None or self.features is None
            or not self.features.belongs_to_project(task.feature_id, task.project_id, self.user.id)
        ):
            raise BusinessError("La funcionalidad no pertenece al proyecto de la tarea")

    def create(self, raw: Any) -> Task:
        payload = task_command(raw, creating=True).payload()
        self.validate_associations(payload)
        if payload.get("task_type") == "project" and not payload.get("date"):
            payload.update(date=None, start_time=None, end_time=None, all_day=False)
        candidate = Task(**payload)
        self.validate_feature(candidate)
        validate_plan(candidate, self.repository.list() if payload.get("prerequisite_ids") else [])
        payload["user_id"] = self.user.id
        return self.repository.create(payload)

    def update(self, identifier: str, raw: Any) -> Task:
        command = task_command(raw)
        command.require_changes()
        payload = command.payload()
        self.validate_associations(payload)
        if not set(payload).intersection({"prerequisite_ids", "completed", "project_id", "feature_id", "task_type", "date", "start_time", "end_time"}):
            result = self.repository.update(identifier, payload)
            if result is None:
                raise BusinessError("Task not found", "not_found")
            return result
        rows = self.repository.list()
        previous = next((row for row in rows if row.id == identifier), None)
        if previous is None:
            raise BusinessError("Task not found", "not_found")
        if "date" in payload and not payload["date"]:
            if payload.get("task_type", previous.task_type) != "project":
                raise BusinessError("Las tareas diarias requieren fecha")
            payload.update(date=None, start_time=None, end_time=None, all_day=False)
        if payload.get("date") and previous.date is None:
            payload.setdefault("start_time", "09:00")
            payload.setdefault("end_time", "10:00")
        candidate = replace(previous, **payload)
        if "feature_id" not in payload and previous.feature_id is not None and (
            candidate.project_id != previous.project_id or candidate.task_type != "project"
        ):
            payload["feature_id"] = None
            candidate = replace(candidate, feature_id=None)
        if "feature_id" in payload:
            self.validate_feature(candidate)
        if candidate.task_type == "daily" and candidate.date is None:
            raise BusinessError("Las tareas diarias requieren fecha")
        validate_plan(candidate, rows, previous)
        result = self.repository.update(identifier, payload)
        if result is None:
            raise BusinessError("Task not found", "not_found")
        return result

    def delete(self, identifier: str) -> str:
        rows = self.repository.list()
        previous = next((row for row in rows if row.id == identifier), None)
        if previous:
            validate_plan(previous, rows, deleting=True)
        if not self.repository.delete(identifier):
            raise BusinessError("Task not found", "not_found")
        return identifier
