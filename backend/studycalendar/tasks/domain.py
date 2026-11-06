from dataclasses import dataclass, field
from datetime import date, time
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
    date: str | None = ""
    start_time: str | None = ""
    end_time: str | None = ""
    color: str = ""
    completed: bool = False
    all_day: bool = False
    prerequisite_ids: list[str] = field(default_factory=list)


def task_command(raw: Any, creating: bool = False) -> Command:
    body = Command.parse(raw, {"title", "category_id", "project_id", "task_type", "date", "start_time", "end_time", "color", "completed", "all_day", "prerequisite_ids"}).payload()
    if "prerequisite_ids" in body:
        ids = body["prerequisite_ids"]
        if not isinstance(ids, list) or any(not isinstance(i, str) or not i for i in ids) or len(set(ids)) != len(ids):
            raise BusinessError("Las tareas previas deben ser una lista de identificadores únicos")
    if "completed" in body and not isinstance(body["completed"], bool):
        raise BusinessError("completed must be a boolean")
    if body.get("date") is not None and "date" in body:
        try:
            date.fromisoformat(body["date"])
        except (TypeError, ValueError):
            raise BusinessError("La fecha debe tener formato AAAA-MM-DD")
    if "all_day" in body and not isinstance(body["all_day"], bool):
        raise BusinessError("all_day must be a boolean")
    # All-day tasks retain a valid internal interval. Sparse conversions without
    # hour fields keep the existing interval; creation supplies safe defaults.
    if body.get("all_day") and (creating or "start_time" in body or "end_time" in body):
        try:
            valid_interval = time.fromisoformat(body.get("end_time")) > time.fromisoformat(body.get("start_time"))
        except (TypeError, ValueError):
            valid_interval = False
        if not valid_interval:
            body.update(start_time="09:00", end_time="10:00")
    if "task_type" in body and body["task_type"] not in ("daily", "project"):
        raise BusinessError("Use /api/routines for daily habits")
    if creating and not str(body.get("title", "")).strip():
        raise BusinessError("title is required")
    if "title" in body:
        body["title"] = str(body["title"]).strip()
    return Command(MappingProxyType(body))
