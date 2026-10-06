from datetime import date
from ..domain import Routine, RoutineSummary, Period

ROUTINE_SELECT = "id,title,color,active,created_at,updated_at"
PERIOD_SELECT = "id,routine_id,starts_on,ends_on"


class SupabaseRoutineRepository:
    def __init__(self, database):
        self.database = database

    def table(self, name="routine"):
        return self.database.table(name)

    def get(self, identifier: str) -> Routine | None:
        rows = self.table().select(ROUTINE_SELECT).eq("id", identifier).execute().data or []
        return Routine.from_record(rows[0]) if rows else None

    def create(self, payload: dict) -> Routine:
        return Routine.from_record(self.table().insert(payload).execute().data[0])

    def update(self, identifier: str, payload: dict) -> None:
        self.table().update(payload).eq("id", identifier).execute()

    def delete(self, identifier: str) -> bool:
        return bool(self.table().delete().eq("id", identifier).execute().data)

    def open_periods(self, routine_id: str) -> list[Period]:
        rows = self.table("routine_period").select(PERIOD_SELECT).eq("routine_id", routine_id).is_("ends_on", "null").execute().data or []
        return [Period.from_record(row) for row in rows]

    def add_period(self, routine_id: str, user_id: str, starts_on: str) -> None:
        self.table("routine_period").insert({"routine_id": routine_id, "user_id": user_id, "starts_on": starts_on, "ends_on": None}).execute()

    def remove_period(self, identifier: str) -> None:
        self.table("routine_period").delete().eq("id", identifier).execute()

    def close_period(self, identifier: str, ends_on: str) -> None:
        self.table("routine_period").update({"ends_on": ends_on}).eq("id", identifier).execute()

    def complete(self, routine_id: str, user_id: str, today: date, completed: bool) -> None:
        self.table("routine_completion").upsert(
            {"routine_id": routine_id, "user_id": user_id, "occurrence_date": today.isoformat(), "completed": completed},
            on_conflict="routine_id,occurrence_date",
        ).execute()


class SupabaseRoutineSummaries:
    def __init__(self, database):
        self.database = database

    def summaries(self, today: date, routine_id: str | None = None) -> list[RoutineSummary]:
        query = self.database.rpc("routine_summaries", {"p_today": today.isoformat()})
        if routine_id:
            query = query.eq("id", routine_id)
        return [RoutineSummary.from_record(row) for row in (query.execute().data or [])]
