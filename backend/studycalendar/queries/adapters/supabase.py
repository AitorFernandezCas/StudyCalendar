from datetime import date
from ..domain import ActivityRecords, DatedTask, RoutinePeriod, RoutineCompletion
from ...shared.domain import User


class SupabaseActivityRepository:
    """Read the full year under the authenticated lease, respecting REST page limits."""
    PAGE_SIZE = 500

    def __init__(self, database, user: User):
        self.database = database
        self.user = user

    def rows(self, query_factory):
        offset = 0
        result = []
        while True:
            page = query_factory().range(offset, offset + self.PAGE_SIZE - 1).execute().data or []
            result.extend(page)
            if len(page) < self.PAGE_SIZE:
                return result
            offset += self.PAGE_SIZE

    def read(self, start: date, end: date) -> ActivityRecords:
        first, last = start.isoformat(), end.isoformat()
        tasks = self.rows(lambda: self.database.table("tasks").select("id,date,completed")
                          .eq("user_id", self.user.id).neq("task_type", "routine")
                          .gte("date", first).lte("date", last).order("id"))
        periods = self.rows(lambda: self.database.table("routine_period").select("id,routine_id,starts_on,ends_on")
                            .eq("user_id", self.user.id).lte("starts_on", last)
                            .or_(f"ends_on.is.null,ends_on.gte.{first}").order("id"))
        completions = self.rows(lambda: self.database.table("routine_completion").select("id,routine_id,occurrence_date")
                                .eq("user_id", self.user.id).eq("completed", "true")
                                .gte("occurrence_date", first).lte("occurrence_date", last).order("id"))
        return ActivityRecords(
            [DatedTask(row["date"], row["completed"]) for row in tasks],
            [RoutinePeriod(row["routine_id"], row["starts_on"], row["ends_on"]) for row in periods],
            [RoutineCompletion(row["routine_id"], row["occurrence_date"]) for row in completions],
        )
