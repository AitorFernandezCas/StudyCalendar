from datetime import date
from typing import Protocol
from .domain import ActivityRecords


class ActivityRepository(Protocol):
    def read(self, start: date, end: date) -> ActivityRecords: ...
