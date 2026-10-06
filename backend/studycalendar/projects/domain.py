from dataclasses import dataclass
from typing import Any
from ..shared.domain import Model, Command, named_payload


@dataclass(frozen=True)
class Project(Model):
    id: str = ""
    name: str = ""
    color: str = ""
    created_at: str = ""
    updated_at: str = ""


def project_command(raw: Any, creating: bool = False) -> Command:
    return named_payload(raw, creating, "#ee7b6f")
