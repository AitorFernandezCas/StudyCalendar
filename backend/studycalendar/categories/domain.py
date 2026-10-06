from dataclasses import dataclass
from typing import Any
from ..shared.domain import Model, Command, named_payload


@dataclass(frozen=True)
class Category(Model):
    id: str = ""
    name: str = ""
    color: str = ""


def category_command(raw: Any, creating: bool = False) -> Command:
    return named_payload(raw, creating, "#7c5cff")
