"""Framework-independent values, errors and compatibility validation."""
from dataclasses import dataclass, field, fields
from datetime import date
from types import MappingProxyType
from typing import Any, Mapping, Self
import re

HEX_COLOR = re.compile(r"^#[0-9a-fA-F]{6}$")


class BusinessError(Exception):
    def __init__(self, message: str, kind: str = "validation", details: dict | None = None):
        super().__init__(message)
        self.kind = kind
        self.details = details or {}


class DuplicateName(Exception):
    """A persistence adapter detected a unique-name conflict."""


@dataclass(frozen=True)
class User:
    id: str


@dataclass(frozen=True)
class Day:
    date: date
    timezone: str
    next_day_at: str
    day_started_at: str | None = None

    def metadata(self) -> dict:
        result = {"date": self.date.isoformat(), "timezone": self.timezone, "next_day_at": self.next_day_at}
        if self.day_started_at is not None:
            result["day_started_at"] = self.day_started_at
        return result


@dataclass(frozen=True)
class Model:
    # Preserve fields returned by persistence, including legacy/default columns.
    # Known fields are typed in each feature; transport serialization happens outside the core.
    extra: Mapping[str, Any] = field(default_factory=dict, kw_only=True, repr=False)
    present: frozenset[str] | None = field(default=None, kw_only=True, repr=False)

    @classmethod
    def from_record(cls, row: Mapping[str, Any]) -> Self:
        names = {f.name for f in fields(cls)} - {"extra", "present"}
        return cls(**{k: v for k, v in row.items() if k in names},
                   extra=MappingProxyType({k: v for k, v in row.items() if k not in names}),
                   present=frozenset(row))


@dataclass(frozen=True)
class Command:
    """A sparse command: omitted fields remain distinct from explicit null."""
    values: Mapping[str, Any]

    @classmethod
    def parse(cls, raw: Any, allowed: set[str]) -> Self:
        body = {} if raw is None else raw
        if not isinstance(body, dict):
            raise BusinessError("A JSON object is required")
        unknown = set(body) - allowed
        if unknown:
            raise BusinessError(f"Unsupported fields: {', '.join(sorted(unknown))}")
        return cls(MappingProxyType(dict(body)))

    def payload(self) -> dict:
        return dict(self.values)

    def require_changes(self) -> None:
        if not self.values:
            raise BusinessError("At least one field is required")


def named_payload(raw: Any, creating: bool, color_example: str) -> Command:
    body = Command.parse(raw, {"name", "color"}).payload()
    name = str(body.get("name", "")).strip()
    if creating and not name:
        raise BusinessError("name is required")
    if "name" in body:
        if not name:
            raise BusinessError("name cannot be empty")
        body["name"] = name
    validate_color(body, color_example)
    return Command(MappingProxyType(body))


def validate_color(body: dict, example: str = "#7c5cff") -> None:
    if "color" in body and (not isinstance(body["color"], str) or not HEX_COLOR.fullmatch(body["color"])):
        raise BusinessError(f"color must be a hexadecimal value such as {example}")
