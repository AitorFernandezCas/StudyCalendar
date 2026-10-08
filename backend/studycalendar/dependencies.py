"""Public dependency injection contracts; no framework or SDK dependencies."""
from contextlib import AbstractContextManager
from dataclasses import dataclass
from typing import Protocol
from .categories.ports import CategoryRepository
from .projects.ports import ProjectRepository
from .tasks.ports import TaskRepository
from .routines.ports import RoutineRepository, RoutineSummaries
from .queries.ports import ActivityRepository
from .shared.domain import User
from .shared.ports import Authenticator, Clock


@dataclass(frozen=True)
class Repositories:
    tasks: TaskRepository
    categories: CategoryRepository
    projects: ProjectRepository
    routines: RoutineRepository
    summaries: RoutineSummaries
    activity: ActivityRepository | None = None


class RepositoryProvider(Protocol):
    def scope(self, token: str, user: User) -> AbstractContextManager[Repositories]: ...


@dataclass(frozen=True)
class Dependencies:
    authenticator: Authenticator
    repositories: RepositoryProvider
    clock: Clock
