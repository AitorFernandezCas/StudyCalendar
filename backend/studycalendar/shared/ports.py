from datetime import date
from typing import Protocol
from .domain import Day, User


class Authenticator(Protocol):
    def authenticate(self, token: str) -> User | None: ...


class Clock(Protocol):
    def day(self) -> Day: ...
    def local_date(self) -> date: ...
