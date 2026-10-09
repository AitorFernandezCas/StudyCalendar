from .domain import Preferences
from .ports import PreferencesRepository
from ..shared.domain import User


class PreferencesService:
    def __init__(self, repository: PreferencesRepository, user: User, timezone: str):
        self.repository = repository
        self.user = user
        self.timezone = timezone

    def get(self) -> dict:
        return {**self.repository.get().payload(), "timezone": self.timezone}

    def update(self, raw) -> dict:
        self.repository.get().patch(raw)
        return {**self.repository.save(raw).payload(), "timezone": self.timezone}
