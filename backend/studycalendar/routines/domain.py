from dataclasses import dataclass
from datetime import date
from types import MappingProxyType
from typing import Any
from ..shared.domain import Model, Command, BusinessError, validate_color


@dataclass(frozen=True)
class Routine(Model):
    id: str = ""
    title: str = ""
    color: str = ""
    active: bool = True
    created_at: str = ""
    updated_at: str = ""


@dataclass(frozen=True)
class RoutineSummary(Routine):
    due_today: bool = False
    completed_today: bool = False
    current_streak: int = 0
    max_streak: int = 0


@dataclass(frozen=True)
class Period(Model):
    id: str = ""
    routine_id: str = ""
    starts_on: str = ""
    ends_on: str | None = None


def routine_command(raw: Any, creating: bool = False) -> Command:
    body = Command.parse(raw, {"title", "color", "active", "starts_on"}).payload()
    if (creating or "title" in body) and (not isinstance(body.get("title"), str) or not body["title"].strip()):
        raise BusinessError("title is required")
    if "title" in body:
        body["title"] = body["title"].strip()
    validate_color(body)
    if "active" in body and not isinstance(body["active"], bool):
        raise BusinessError("active must be boolean")
    if "starts_on" in body:
        try:
            date.fromisoformat(body["starts_on"])
        except (TypeError, ValueError):
            raise BusinessError("starts_on must be an ISO date")
    return Command(MappingProxyType(body))


@dataclass(frozen=True)
class Completion:
    date: date
    completed: bool

    @classmethod
    def parse(cls, raw: Any):
        if not isinstance(raw, dict) or set(raw) != {"date", "completed"} or not isinstance(raw["completed"], bool):
            raise BusinessError("date and boolean completed are required")
        try:
            target = date.fromisoformat(raw["date"])
        except (TypeError, ValueError):
            raise BusinessError("date must be an ISO date")
        return cls(target, raw["completed"])
