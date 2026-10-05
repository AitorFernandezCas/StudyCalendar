from datetime import date
from ..domain import Task

TASK_SELECT = "id,title,category_id,project_id,task_type,date,start_time,end_time,color,completed"


class SupabaseTaskRepository:
    def __init__(self, database):
        self.database = database

    def table(self):
        return self.database.table("tasks")

    def list(self, start: date | None = None, end: date | None = None) -> list[Task]:
        query = self.table().select(TASK_SELECT).neq("task_type", "routine")
        if start:
            query = query.gte("date", start.isoformat())
        if end:
            query = query.lte("date", end.isoformat())
        rows = query.order("date").order("start_time").execute().data or []
        return [Task.from_record(row) for row in rows]

    def create(self, payload: dict) -> Task:
        return Task.from_record(self.table().insert(payload).execute().data[0])

    def update(self, identifier: str, payload: dict) -> Task | None:
        rows = self.table().update(payload).eq("id", identifier).execute().data or []
        return Task.from_record(rows[0]) if rows else None

    def delete(self, identifier: str) -> bool:
        return bool(self.table().delete().eq("id", identifier).execute().data)

    def set_category_color(self, category_id: str, color: str) -> None:
        self.table().update({"color": color}).eq("category_id", category_id).execute()
