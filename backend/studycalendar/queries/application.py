from dataclasses import dataclass
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
    def __init__(self, tasks: TaskRepository, categories: CategoryRepository, projects: ProjectRepository, clock: Clock):
        self.tasks = tasks
        self.categories = categories
        self.projects = projects
        self.clock = clock

    def calendar(self, start: str | None, end: str | None) -> list[Task]:
        first, last = date_range(start, end, self.clock)
        return self.tasks.list(first, last)

    def bootstrap(self, start: str | None, end: str | None) -> Bootstrap:
        first, last = date_range(start, end, self.clock)
        return Bootstrap(self.categories.list(), self.projects.list(), self.tasks.list(first, last))
