from dataclasses import dataclass
from typing import Any
from ..shared.domain import Model, Command, BusinessError, named_payload


@dataclass(frozen=True)
class Project(Model):
    id: str = ""
    name: str = ""
    color: str = ""
    created_at: str = ""
    updated_at: str = ""
    status: str = "active"


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
