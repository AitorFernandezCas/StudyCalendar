from datetime import date
import re
from postgrest.exceptions import APIError
from ..domain import Task
from ...shared.domain import BusinessError

LEGACY_TASK_SELECT = "id,title,category_id,project_id,task_type,date,start_time,end_time,color,completed"
TASK_SELECT = LEGACY_TASK_SELECT + ",all_day"


def missing_all_day(error: APIError) -> bool:
    message = error.message or ""
    return (error.code == "42703" and bool(re.search(
        r'column (?:tasks\.all_day|"tasks"\."all_day"|"?all_day"?) does not exist', message))) or (
        error.code == "PGRST204" and "'all_day' column of 'tasks'" in message)


class SupabaseTaskRepository:
    def __init__(self, database):
        self.database = database
        # Request-scoped capability state; never cached across schema upgrades.
        self.all_day_available = None

    def table(self):
        return self.database.table("tasks")

    def read_rows(self, columns: str, start: date | None, end: date | None) -> list[dict]:
        query = self.table().select(columns).neq("task_type", "routine")
        if start:
            query = query.gte("date", start.isoformat())
        if end:
            query = query.lte("date", end.isoformat())
        return query.order("date").order("start_time").execute().data or []

    def list(self, start: date | None = None, end: date | None = None) -> list[Task]:
        if self.all_day_available is False:
            rows = self.read_rows(LEGACY_TASK_SELECT, start, end)
        else:
            try:
                rows = self.read_rows(TASK_SELECT, start, end)
                self.all_day_available = True
            except APIError as error:
                if not missing_all_day(error):
                    raise
                self.all_day_available = False
                rows = self.read_rows(LEGACY_TASK_SELECT, start, end)
        return [Task.from_record({"all_day": False, **row}) for row in rows]

    def write_payload(self, payload: dict) -> dict:
        if "all_day" not in payload:
            return payload
        if self.all_day_available is None:
            # Probe with a read before writing; failed mutations are never retried.
            try:
                self.table().select("all_day").limit(0).execute()
                self.all_day_available = True
            except APIError as error:
                if not missing_all_day(error):
                    raise
                self.all_day_available = False
        if self.all_day_available:
            return payload
        if payload["all_day"]:
            raise BusinessError("Las tareas de todo el día requieren actualizar la base de datos. Las tareas con horario siguen disponibles.", "conflict")
        return {key: value for key, value in payload.items() if key != "all_day"}

    def create(self, payload: dict) -> Task:
        row = self.table().insert(self.write_payload(payload)).execute().data[0]
        return Task.from_record({"all_day": False, **row})

    def update(self, identifier: str, payload: dict) -> Task | None:
        compatible_payload = self.write_payload(payload)
        if not compatible_payload:
            # Setting an already-false legacy value still checks ownership via RLS.
            rows = self.table().select(LEGACY_TASK_SELECT).eq("id", identifier).execute().data or []
        else:
            rows = self.table().update(compatible_payload).eq("id", identifier).execute().data or []
        return Task.from_record({"all_day": False, **rows[0]}) if rows else None

    def delete(self, identifier: str) -> bool:
        return bool(self.table().delete().eq("id", identifier).execute().data)

    def set_category_color(self, category_id: str, color: str) -> None:
        self.table().update({"color": color}).eq("category_id", category_id).execute()
