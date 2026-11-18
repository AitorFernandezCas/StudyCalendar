from __future__ import annotations

from uuid import uuid4
from postgrest.exceptions import APIError
from ..domain import Project, ProjectFeature, ProjectFeatureLink
from ...shared.domain import BusinessError, DuplicateName
from ...shared.persistence import execute_named


class SupabaseProjectRepository:
    def __init__(self, database):
        self.database = database

    def table(self):
        return self.database.table("projects")

    def list(self) -> list[Project]:
        rows = self.table().select("id,name,color,status,created_at,updated_at").order("name").execute().data or []
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


class SupabaseFeatureRepository:
    def __init__(self, database):
        self.database = database

    def table(self):
        return self.database.table("project_features")

    def list(self) -> list[ProjectFeature]:
        features = []
        offset = 0
        while True:
            rows = self.table().select("id,project_id,name,description").order("id").range(offset, offset + 499).execute().data or []
            features.extend(ProjectFeature.from_record(row) for row in rows)
            if len(rows) < 500:
                return features
            offset += 500

    def create(self, payload: dict) -> ProjectFeature:
        changes = {key: value for key, value in payload.items() if key != "user_id"}
        result = self.mutate("create", str(uuid4()), changes)
        return ProjectFeature.from_record(result["row"])

    def update(self, identifier: str, payload: dict) -> ProjectFeature | None:
        result = self.mutate("update", identifier, payload)
        return ProjectFeature.from_record(result["row"]) if result else None

    def delete(self, identifier: str) -> bool:
        return bool(self.mutate("delete", identifier, {}))

    def belongs_to_project(self, identifier: str, project_id: str, user_id: str) -> bool:
        return bool(self.table().select("id").eq("id", identifier).eq("project_id", project_id).eq("user_id", user_id).execute().data)

    def list_links(self, project_id: str | None = None) -> list[ProjectFeatureLink] | None:
        links = []
        while True:
            query = self.database.table("project_feature_links").select("id,project_id,source_feature_id,target_feature_id")
            if project_id is not None:
                query = query.eq("project_id", project_id)
            try:
                rows = query.order("id").range(len(links), len(links) + 499).execute().data or []
            except APIError as error:
                if error.code in {"42P01", "PGRST205"} and "project_feature_links" in (error.message or ""):
                    return None
                raise
            links.extend(ProjectFeatureLink.from_record(row) for row in rows)
            if len(rows) < 500:
                return links

    def create_link(self, payload: dict) -> ProjectFeatureLink:
        changes = {key: value for key, value in payload.items() if key != "user_id"}
        result = self.mutate("create", str(uuid4()), changes, "project_feature_links")
        return ProjectFeatureLink.from_record(result["row"])

    def delete_link(self, identifier: str) -> bool:
        return bool(self.mutate("delete", identifier, {}, "project_feature_links"))

    def mutate(self, action: str, identifier: str, changes: dict, entity: str = "project_features") -> dict | None:
        base = None
        if action != "create":
            rows = (self.database.table("sync_entities").select("revision,state")
                    .eq("entity", entity).eq("id", identifier).eq("deleted", False).execute().data or [])
            if not rows:
                return None
            base = rows[0]
        operation = {"protocol": 1, "operation_id": str(uuid4()), "entity": entity,
                     "id": identifier, "action": action, "base": base, "changes": changes, "context": {}}
        result = self.database.rpc("sync_apply", {"p_operation": operation}).execute().data
        if result["status"] == "applied":
            return result
        if action != "create" and result.get("reason") == "deleted":
            return None
        if result.get("reason") == "duplicate_feature_name":
            raise DuplicateName()
        raise BusinessError(result.get("message", "Los datos cambiaron. Actualiza e inténtalo de nuevo."),
                            "validation" if result["status"] == "rejected" else "conflict")
