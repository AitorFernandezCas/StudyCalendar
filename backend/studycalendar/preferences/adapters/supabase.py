from ..domain import Preferences
from ...shared.domain import User, BusinessError
from postgrest.exceptions import APIError


class SupabasePreferencesRepository:
    def __init__(self, database, user: User):
        self.database = database
        self.user = user

    def get(self) -> Preferences:
        rows = self.database.table("user_settings").select(",".join(Preferences().payload())).eq("user_id", self.user.id).execute().data or []
        return Preferences(**rows[0]) if rows else Preferences()

    def save(self, changes: dict) -> Preferences:
        # Insert defaults only when absent; an upsert of a partial row would
        # replace omitted preferences with database defaults on conflict.
        self.database.table("user_settings").upsert(
            {"user_id": self.user.id}, on_conflict="user_id", ignore_duplicates=True
        ).execute()
        try:
            rows = self.database.table("user_settings").update(changes).eq("user_id", self.user.id).execute().data
        except APIError as error:
            if error.code == "23514":
                raise BusinessError("La hora de fin debe ser posterior al inicio.", details={"fields": {
                    "calendar_end_time": "Revisa el horario y vuelve a guardar."}}) from error
            raise
        return Preferences(**{name: rows[0][name] for name in Preferences().payload()})
