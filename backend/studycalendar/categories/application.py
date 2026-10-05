from typing import Any
from .domain import Category, category_command
from .ports import CategoryRepository
from ..shared.domain import User, BusinessError, DuplicateName
from ..tasks.ports import TaskRepository


class CategoryService:
    def __init__(self, repository: CategoryRepository, user: User, tasks: TaskRepository):
        self.repository = repository
        self.user = user
        self.tasks = tasks

    def list(self) -> list[Category]:
        return self.repository.list()

    def create(self, raw: Any) -> Category:
        payload = category_command(raw, creating=True).payload()
        payload["user_id"] = self.user.id
        try:
            return self.repository.create(payload)
        except DuplicateName:
            raise BusinessError("A category with this name already exists", "conflict")

    def update(self, identifier: str, raw: Any) -> Category:
        command = category_command(raw)
        command.require_changes()
        payload = command.payload()
        try:
            result = self.repository.update(identifier, payload)
        except DuplicateName:
            raise BusinessError("A category with this name already exists", "conflict")
        if result is None:
            raise BusinessError("Category not found", "not_found")
        if "color" in payload:
            self.tasks.set_category_color(identifier, payload["color"])
        return result

    def delete(self, identifier: str) -> str:
        if not self.repository.delete(identifier):
            raise BusinessError("Category not found", "not_found")
        return identifier
