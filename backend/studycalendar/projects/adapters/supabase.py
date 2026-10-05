from ..domain import Project
from ...shared.persistence import execute_named


class SupabaseProjectRepository:
    def __init__(self, database):
        self.database = database

    def table(self):
        return self.database.table("projects")

    def list(self) -> list[Project]:
        rows = self.table().select("id,name,color,created_at,updated_at").order("name").execute().data or []
        return [Project.from_record(row) for row in rows]

    def create(self, payload: dict) -> Project:
        return Project.from_record(execute_named(self.table().insert(payload)).data[0])

    def update(self, identifier: str, payload: dict) -> Project | None:
        rows = execute_named(self.table().update(payload).eq("id", identifier)).data or []
        return Project.from_record(rows[0]) if rows else None

    def delete(self, identifier: str) -> bool:
        return bool(self.table().delete().eq("id", identifier).execute().data)

    def belongs_to(self, identifier: str, user_id: str) -> bool:
        return bool(self.table().select("id").eq("id", identifier).eq("user_id", user_id).execute().data)
