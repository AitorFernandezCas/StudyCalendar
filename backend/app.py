import os
import re
from functools import wraps

from dotenv import load_dotenv
from flask import Flask, g, jsonify, request
from flask_cors import CORS
from supabase import Client, create_client
from supabase.client import ClientOptions

load_dotenv()

TASK_FIELDS = {"title", "category_id", "date", "start_time", "end_time", "color", "completed"}
CATEGORY_FIELDS = {"name", "color"}
HEX_COLOR = re.compile(r"^#[0-9a-fA-F]{6}$")


def _settings() -> tuple[str, str, str]:
    url = os.environ.get("SUPABASE_URL", "").strip()
    key = os.environ.get("SUPABASE_PUBLISHABLE_KEY", "").strip()
    origin = os.environ.get("FRONTEND_ORIGIN", "http://localhost:5173").strip()
    if not url or not key:
        raise RuntimeError("SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY are required")
    return url, key, origin


def _client_for_token(token: str) -> Client:
    url, key, _ = _settings()
    return create_client(url, key, options=ClientOptions(headers={"Authorization": f"Bearer {token}"}))


def require_user(view):
    @wraps(view)
    def wrapped(*args, **kwargs):
        authorization = request.headers.get("Authorization", "")
        if not authorization.startswith("Bearer "):
            return jsonify({"error": "Authentication required"}), 401
        token = authorization.removeprefix("Bearer ").strip()
        if not token:
            return jsonify({"error": "Authentication required"}), 401
        try:
            url, key, _ = _settings()
            auth_client = create_client(url, key)
            response = auth_client.auth.get_user(token)
            if not response or not response.user:
                return jsonify({"error": "Invalid access token"}), 401
            g.user = response.user
            g.supabase = _client_for_token(token)
        except Exception:
            return jsonify({"error": "Invalid access token"}), 401
        return view(*args, **kwargs)

    return wrapped


def _json_body(allowed_fields: set[str]):
    body = request.get_json(silent=True) or {}
    unknown = set(body) - allowed_fields
    if unknown:
        return None, {"error": f"Unsupported fields: {', '.join(sorted(unknown))}"}
    return body, None


def _category_payload(required_name: bool = False):
    body, error = _json_body(CATEGORY_FIELDS)
    if error:
        return None, error
    name = str(body.get("name", "")).strip()
    if required_name and not name:
        return None, {"error": "name is required"}
    if "name" in body:
        if not name:
            return None, {"error": "name cannot be empty"}
        body["name"] = name
    if "color" in body and (not isinstance(body["color"], str) or not HEX_COLOR.fullmatch(body["color"])):
        return None, {"error": "color must be a hexadecimal value such as #7c5cff"}
    return body, None


def _task_payload(required_title: bool = False):
    body, error = _json_body(TASK_FIELDS)
    if error:
        return None, error
    if required_title and not str(body.get("title", "")).strip():
        return None, {"error": "title is required"}
    if "title" in body:
        body["title"] = str(body["title"]).strip()
    return body, None


def _category_belongs_to_user(category_id: str) -> bool:
    result = g.supabase.schema("Task").table("category").select("id").eq("id", category_id).eq("user_id", str(g.user.id)).execute()
    return bool(result.data)


def _validate_task_category(payload):
    category_id = payload.get("category_id")
    if category_id is not None and not _category_belongs_to_user(category_id):
        return {"error": "Category not found"}
    return None


def create_app():
    app = Flask(__name__)
    origin = os.environ.get("FRONTEND_ORIGIN", "http://localhost:5173").strip()
    CORS(app, resources={r"/api/*": {"origins": origin}}, supports_credentials=False)

    @app.get("/api/health")
    def health():
        return jsonify({"status": "ok"})

    @app.get("/api/categories")
    @require_user
    def list_categories():
        result = g.supabase.schema("Task").table("category").select("*").order("name").execute()
        return jsonify(result.data or [])

    @app.post("/api/categories")
    @require_user
    def create_category():
        payload, error = _category_payload(required_name=True)
        if error:
            return jsonify(error), 400
        payload["user_id"] = str(g.user.id)
        try:
            result = g.supabase.schema("Task").table("category").insert(payload).execute()
        except Exception as exc:
            if "duplicate" in str(exc).lower() or "unique" in str(exc).lower():
                return jsonify({"error": "A category with this name already exists"}), 409
            raise
        return jsonify(result.data[0]), 201

    @app.patch("/api/categories/<category_id>")
    @require_user
    def update_category(category_id):
        payload, error = _category_payload()
        if error:
            return jsonify(error), 400
        if not payload:
            return jsonify({"error": "At least one field is required"}), 400
        try:
            result = g.supabase.schema("Task").table("category").update(payload).eq("id", category_id).execute()
        except Exception as exc:
            if "duplicate" in str(exc).lower() or "unique" in str(exc).lower():
                return jsonify({"error": "A category with this name already exists"}), 409
            raise
        if not result.data:
            return jsonify({"error": "Category not found"}), 404
        if "color" in payload:
            g.supabase.schema("Task").table("tasks").update({"color": payload["color"]}).eq("category_id", category_id).execute()
        return jsonify(result.data[0])

    @app.delete("/api/categories/<category_id>")
    @require_user
    def delete_category(category_id):
        result = g.supabase.schema("Task").table("category").delete().eq("id", category_id).execute()
        if not result.data:
            return jsonify({"error": "Category not found"}), 404
        return jsonify({"deleted": category_id})

    @app.get("/api/tasks")
    @require_user
    def list_tasks():
        result = g.supabase.schema("Task").table("tasks").select("*").order("date").order("start_time").execute()
        return jsonify(result.data or [])

    @app.post("/api/tasks")
    @require_user
    def create_task():
        payload, error = _task_payload(required_title=True)
        if error:
            return jsonify(error), 400
        category_error = _validate_task_category(payload)
        if category_error:
            return jsonify(category_error), 400
        payload["user_id"] = str(g.user.id)
        result = g.supabase.schema("Task").table("tasks").insert(payload).execute()
        return jsonify(result.data[0]), 201

    @app.patch("/api/tasks/<task_id>")
    @require_user
    def update_task(task_id):
        payload, error = _task_payload()
        if error:
            return jsonify(error), 400
        if not payload:
            return jsonify({"error": "At least one field is required"}), 400
        category_error = _validate_task_category(payload)
        if category_error:
            return jsonify(category_error), 400
        result = g.supabase.schema("Task").table("tasks").update(payload).eq("id", task_id).execute()
        if not result.data:
            return jsonify({"error": "Task not found"}), 404
        return jsonify(result.data[0])

    @app.delete("/api/tasks/<task_id>")
    @require_user
    def delete_task(task_id):
        result = g.supabase.schema("Task").table("tasks").delete().eq("id", task_id).execute()
        if not result.data:
            return jsonify({"error": "Task not found"}), 404
        return jsonify({"deleted": task_id})

    return app


app = create_app()

if __name__ == "__main__":
    app.run(host="127.0.0.1", port=int(os.environ.get("PORT", "5000")), debug=os.environ.get("FLASK_ENV") == "development")
