from dataclasses import dataclass
from datetime import date, timedelta
from typing import Any
from .domain import RoutineSummary, Completion, routine_command
from .ports import RoutineRepository, RoutineSummaries
from ..shared.domain import BusinessError, Day, User
from ..shared.ports import Clock


@dataclass(frozen=True)
class Snapshot:
    day: Day
    routines: list[RoutineSummary]


@dataclass(frozen=True)
class CompletionResult:
    day: Day
    routine: RoutineSummary


class RoutineService:
    def __init__(self, repository: RoutineRepository, summaries: RoutineSummaries, user: User, clock: Clock):
        self.repository = repository
        self.summaries = summaries
        self.user = user
        self.clock = clock

    def list(self) -> Snapshot:
        day = self.clock.day()
        return Snapshot(day, self.summaries.summaries(day.date))

    def create(self, raw: Any) -> RoutineSummary:
        payload = routine_command(raw, creating=True).payload()
        day = self.clock.day()
        starts_on = payload.pop("starts_on", day.date.isoformat())
        active = payload.get("active", True)
        payload["user_id"] = self.user.id
        routine = self.repository.create(payload)
        if active:
            self.repository.add_period(routine.id, self.user.id, starts_on)
        return self.summaries.summaries(day.date, routine.id)[0]

    def update(self, identifier: str, raw: Any) -> RoutineSummary:
        command = routine_command(raw)
        command.require_changes()
        payload = command.payload()
        current = self.repository.get(identifier)
        if current is None:
            raise BusinessError("Routine not found", "not_found")
        day = self.clock.day()
        # Historic API accepts and validates starts_on on PATCH but ignores it.
        payload.pop("starts_on", None)
        active = payload.get("active", current.active)
        if payload:
            self.repository.update(identifier, payload)
        if active != current.active:
            periods = self.repository.open_periods(identifier)
            if active:
                if not periods:
                    self.repository.add_period(identifier, self.user.id, day.date.isoformat())
            else:
                for period in periods:
                    if date.fromisoformat(period.starts_on) >= day.date:
                        self.repository.remove_period(period.id)
                    else:
                        self.repository.close_period(period.id, (day.date - timedelta(days=1)).isoformat())
        return self.summaries.summaries(day.date, identifier)[0]

    def delete(self, identifier: str) -> str:
        if not self.repository.delete(identifier):
            raise BusinessError("Routine not found", "not_found")
        return identifier

    def complete(self, identifier: str, raw: Any) -> CompletionResult:
        command = Completion.parse(raw)
        day = self.clock.day()
        if command.date != day.date:
            raise BusinessError("El día ha cambiado. Actualiza las rutinas.", "conflict", {"date": day.date.isoformat()})
        rows = self.summaries.summaries(day.date, identifier)
        if not rows or not rows[0].due_today:
            raise BusinessError("Routine is not due today", "not_found")
        self.repository.complete(identifier, self.user.id, day.date, command.completed)
        return CompletionResult(day, self.summaries.summaries(day.date, identifier)[0])
