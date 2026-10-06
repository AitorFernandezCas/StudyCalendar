from dataclasses import dataclass
from types import MappingProxyType
from typing import Any
from ..shared.domain import Model, Command, BusinessError


@dataclass(frozen=True)
class Task(Model):
    id: str = ""
    title: str = ""
    category_id: str | None = None
    project_id: str | None = None
    task_type: str = "daily"
    date: str = ""
    start_time: str = ""
    end_time: str = ""
    color: str = ""
    completed: bool = False


def task_command(raw: Any, creating: bool = False) -> Command:
    body = Command.parse(raw, {"title", "category_id", "project_id", "task_type", "date", "start_time", "end_time", "color", "completed"}).payload()
    if "task_type" in body and body["task_type"] not in ("daily", "project"):
        raise BusinessError("Use /api/routines for daily habits")
    if creating and not str(body.get("title", "")).strip():
        raise BusinessError("title is required")
    if "title" in body:
        body["title"] = str(body["title"]).strip()
    return Command(MappingProxyType(body))
