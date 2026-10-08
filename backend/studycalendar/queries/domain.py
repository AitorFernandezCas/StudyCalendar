from dataclasses import dataclass


@dataclass(frozen=True)
class DatedTask:
    date: str
    completed: bool


@dataclass(frozen=True)
class RoutinePeriod:
    routine_id: str
    starts_on: str
    ends_on: str | None


@dataclass(frozen=True)
class RoutineCompletion:
    routine_id: str
    date: str


@dataclass(frozen=True)
class ActivityRecords:
    tasks: list[DatedTask]
    periods: list[RoutinePeriod]
    completions: list[RoutineCompletion]


@dataclass(frozen=True)
class ActivityDay:
    date: str
    total: int
    completed: int
    status: str


@dataclass(frozen=True)
class ActivitySnapshot:
    year: int
    date: str
    timezone: str
    next_day_at: str
    days: list[ActivityDay]
