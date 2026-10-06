"""Injected test adapters. No test bypass exists in production authentication."""
from contextlib import contextmanager
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo
from studycalendar.dependencies import Repositories
from studycalendar.shared.domain import Day, User
from studycalendar.categories.adapters.supabase import SupabaseCategoryRepository
from studycalendar.projects.adapters.supabase import SupabaseProjectRepository
from studycalendar.tasks.adapters.supabase import SupabaseTaskRepository
from studycalendar.routines.adapters.supabase import SupabaseRoutineRepository, SupabaseRoutineSummaries


class FixedAuth:
    def authenticate(self, token):
        return User('user-1')


class FixedClock:
    def __init__(self, today=date(2026, 10, 5)):
        self.today = today
        self.calls = 0

    def local_date(self):
        return self.today

    def day(self):
        self.calls += 1
        tomorrow = datetime.combine(self.today + timedelta(days=1), datetime.min.time(), ZoneInfo('Europe/Madrid'))
        return Day(self.today, 'Europe/Madrid', tomorrow.isoformat())


class DatabaseProvider:
    def __init__(self, database):
        self.database = database

    @contextmanager
    def scope(self, token, user):
        yield Repositories(
            SupabaseTaskRepository(self.database), SupabaseCategoryRepository(self.database),
            SupabaseProjectRepository(self.database), SupabaseRoutineRepository(self.database),
            SupabaseRoutineSummaries(self.database),
        )
