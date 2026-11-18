from typing import Any
from .domain import Project, ProjectFeature, ProjectFeatureLink, parse_feature_id, project_command, validate_feature_link
from .ports import FeatureRepository, ProjectRepository
from ..shared.domain import User, BusinessError, Command, DuplicateName


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


class FeatureService:
    def __init__(self, repository: FeatureRepository | None, projects: ProjectRepository, user: User):
        self.repository = repository
        self.projects = projects
        self.user = user

    def repo(self) -> FeatureRepository:
        if self.repository is None:
            raise BusinessError("Funcionalidades no disponibles", "not_found")
        return self.repository

    def list(self) -> list[ProjectFeature]:
        return self.repo().list()

    def create(self, raw: Any) -> ProjectFeature:
        command = Command.parse(raw, {"project_id", "name", "description"})
        payload = command.payload()
        project_id = parse_feature_id(payload.pop("project_id", None))
        if not self.projects.belongs_to(project_id, self.user.id):
            raise BusinessError("Proyecto no disponible", "not_found")
        payload = self.fields(payload, creating=True)
        try:
            return self.repo().create({**payload, "project_id": project_id, "user_id": self.user.id})
        except DuplicateName:
            raise BusinessError("Ya existe una funcionalidad con ese nombre", "conflict") from None

    def update(self, identifier: str, raw: Any) -> ProjectFeature:
        command = Command.parse(raw, {"name", "description"})
        command.require_changes()
        payload = self.fields(command.payload())
        try:
            result = self.repo().update(parse_feature_id(identifier), payload)
        except DuplicateName:
            raise BusinessError("Ya existe una funcionalidad con ese nombre", "conflict") from None
        if result is None:
            raise BusinessError("Funcionalidad no disponible", "not_found")
        return result

    def delete(self, identifier: str) -> str:
        identifier = parse_feature_id(identifier)
        if not self.repo().delete(identifier):
            raise BusinessError("Funcionalidad no disponible", "not_found")
        return identifier

    def create_link(self, raw: Any) -> ProjectFeatureLink:
        fields = ("project_id", "source_feature_id", "target_feature_id")
        body = Command.parse(raw, set(fields)).payload()
        payload = {field: parse_feature_id(body.get(field)) for field in fields}
        project = payload["project_id"]
        source, target = payload["source_feature_id"], payload["target_feature_id"]
        repository = self.repo()
        if not all(repository.belongs_to_project(item, project, self.user.id) for item in (source, target)):
            raise BusinessError("Selecciona dos funcionalidades del mismo proyecto disponibles")
        links = repository.list_links(project)
        if links is None:
            raise BusinessError("Guardar conexiones requiere actualizar la base de datos", "conflict")
        validate_feature_link(source, target, links)
        return repository.create_link({**payload, "user_id": self.user.id})

    def delete_link(self, identifier: str) -> str:
        identifier = parse_feature_id(identifier)
        if not self.repo().delete_link(identifier):
            raise BusinessError("Conexión no disponible", "not_found")
        return identifier

    @staticmethod
    def fields(payload: dict, creating: bool = False) -> dict:
        if creating or "name" in payload:
            name = payload.get("name")
            if not isinstance(name, str) or not name.strip():
                raise BusinessError("Introduce un nombre para la funcionalidad")
            payload["name"] = name.strip()
        if "description" in payload and payload["description"] is not None:
            if not isinstance(payload["description"], str):
                raise BusinessError("La descripción debe ser texto")
            payload["description"] = payload["description"].strip() or None
        return payload
