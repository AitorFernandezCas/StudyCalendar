from dataclasses import dataclass
from typing import Any
from uuid import UUID
from ..shared.domain import Model, Command, BusinessError, named_payload


@dataclass(frozen=True)
class Project(Model):
    id: str = ""
    name: str = ""
    color: str = ""
    created_at: str = ""
    updated_at: str = ""
    status: str = "active"


@dataclass(frozen=True)
class ProjectFeature(Model):
    id: str
    project_id: str
    name: str
    description: str | None = None


@dataclass(frozen=True)
class ProjectFeatureLink(Model):
    id: str
    project_id: str
    source_feature_id: str
    target_feature_id: str


def validate_feature_link(source: str, target: str, links: list[ProjectFeatureLink]) -> None:
    if source == target:
        raise BusinessError("Una funcionalidad no puede depender de sí misma")
    outgoing: dict[str, list[str]] = {}
    for link in links:
        if link.source_feature_id == source and link.target_feature_id == target:
            raise BusinessError("Las funcionalidades ya están conectadas", "conflict")
        outgoing.setdefault(link.source_feature_id, []).append(link.target_feature_id)
    pending, seen = [target], set()
    while pending:
        identifier = pending.pop()
        if identifier == source:
            raise BusinessError("La conexión crearía un ciclo entre funcionalidades", "conflict")
        if identifier not in seen:
            seen.add(identifier)
            pending.extend(outgoing.get(identifier, []))


def parse_feature_id(value: Any) -> str:
    try:
        return str(UUID(value))
    except (ValueError, TypeError, AttributeError):
        raise BusinessError("Identificador de funcionalidad no válido") from None


def project_command(raw: Any, creating: bool = False) -> Command:
    body = Command.parse(raw, {"name", "color", "status"}).payload()
    payload = named_payload({key: value for key, value in body.items() if key != "status"}, creating, "#ee7b6f").payload()
    if "status" in body:
        if not isinstance(body["status"], str) or body["status"] not in ("active", "inactive", "completed"):
            raise BusinessError("status must be active, inactive or completed")
        payload["status"] = body["status"]
    elif creating:
        payload["status"] = "active"
    return Command(payload)
