from dataclasses import dataclass
from datetime import date, timedelta
from .domain import ActivityDay, ActivitySnapshot
from .ports import ActivityRepository
from ..shared.domain import BusinessError
from ..tasks.application import date_range
from ..tasks.domain import Task
from ..tasks.ports import TaskRepository
from ..categories.domain import Category
from ..categories.ports import CategoryRepository
from ..projects.domain import Project
from ..projects.ports import ProjectRepository
from ..shared.ports import Clock


@dataclass(frozen=True)
class Bootstrap:
    categories: list[Category]
    projects: list[Project]
    tasks: list[Task]


class QueryService:
    def __init__(self, tasks: TaskRepository, categories: CategoryRepository, projects: ProjectRepository, clock: Clock, activity: ActivityRepository | None = None):
        self.tasks = tasks
        self.categories = categories
        self.projects = projects
        self.clock = clock
        self.activity_repository = activity

    def activity(self, year: str | None) -> ActivitySnapshot:
        day = self.clock.day()
        try:
            selected = day.date.year if year is None else int(year)
            if not 1900 <= selected <= 9998:
                raise ValueError()
        except (ValueError, TypeError):
            raise BusinessError("year must be between 1900 and 9998") from None
        first, last = date(selected, 1, 1), date(selected, 12, 31)
        if self.activity_repository is None:
            raise RuntimeError("Activity repository is not configured")
        records = self.activity_repository.read(first, last)
        counts = {}
        current = first
        while current <= last:
            counts[current.isoformat()] = [0, 0]
            current += timedelta(days=1)
        for task in records.tasks:
            if task.date in counts:
                counts[task.date][0] += 1
                counts[task.date][1] += int(task.completed)
        # A routine is due once per day, including closed historical periods.
        due = set()
        for period in records.periods:
            current = max(first, date.fromisoformat(period.starts_on))
            end = min(last, date.fromisoformat(period.ends_on)) if period.ends_on else last
            while current <= end:
                due.add((period.routine_id, current.isoformat()))
                current += timedelta(days=1)
        completed = {(item.routine_id, item.date) for item in records.completions}
        for occurrence in due:
            counts[occurrence[1]][0] += 1
            counts[occurrence[1]][1] += int(occurrence in completed)
        today = day.date.isoformat()
        days = [ActivityDay(value, total, done,
                            "future" if value > today else "empty" if total == 0 else
                            "complete" if done == total else "pending")
                for value, (total, done) in counts.items()]
        return ActivitySnapshot(selected, today, day.timezone, day.next_day_at, days)

    def calendar(self, start: str | None, end: str | None) -> list[Task]:
        first, last = date_range(start, end, self.clock)
        return self.tasks.list(first, last)

    def bootstrap(self, start: str | None, end: str | None) -> Bootstrap:
        first, last = date_range(start, end, self.clock)
        return Bootstrap(self.categories.list(), self.projects.list(), self.tasks.list(first, last))
