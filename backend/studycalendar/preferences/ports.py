from typing import Protocol
from .domain import Preferences


class PreferencesRepository(Protocol):
    def get(self) -> Preferences: ...
    def save(self, changes: dict) -> Preferences: ...
