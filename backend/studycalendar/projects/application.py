from typing import Any
from .domain import Project, project_command
from .ports import ProjectRepository
from ..shared.domain import User, BusinessError, DuplicateName


class ProjectService:
    def __init__(self, repository: ProjectRepository, user: User):
        self.repository = repository
        self.user = user

    def list(self) -> list[Project]:
        return self.repository.list()

    def create(self, raw: Any) -> Project:
        payload = project_command(raw, creating=True).payload()
        payload["user_id"] = self.user.id
        try:
            return self.repository.create(payload)
        except DuplicateName:
            raise BusinessError("Ya existe un proyecto con ese nombre", "conflict")

    def update(self, identifier: str, raw: Any) -> Project:
        command = project_command(raw)
        command.require_changes()
        payload = command.payload()
        try:
            result = self.repository.update(identifier, payload)
        except DuplicateName:
            raise BusinessError("Ya existe un proyecto con ese nombre", "conflict")
        if result is None:
            raise BusinessError("Project not found", "not_found")
        return result

    def delete(self, identifier: str) -> str:
        if not self.repository.delete(identifier):
            raise BusinessError("Project not found", "not_found")
        return identifier
