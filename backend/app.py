import base64
import json
import os
import re
import time
from collections import OrderedDict
from datetime import date, datetime, timedelta
from zoneinfo import ZoneInfo
from functools import wraps
from threading import RLock
from urllib.parse import urlparse

from dotenv import load_dotenv
from flask import Flask, g, jsonify, request
from flask_cors import CORS
from supabase import Client, create_client
from supabase.client import ClientOptions
from werkzeug.exceptions import HTTPException

load_dotenv()

TASK_FIELDS = {"title", "category_id", "project_id", "task_type", "date", "start_time", "end_time", "color", "completed"}
CATEGORY_FIELDS = {"name", "color"}
PROJECT_FIELDS = {"name", "color"}
ROUTINE_FIELDS = {"title", "color", "active", "starts_on"}
HEX_COLOR = re.compile(r"^#[0-9a-fA-F]{6}$")
TASK_SELECT = "id,title,category_id,project_id,task_type,date,start_time,end_time,color,completed"
CATEGORY_SELECT = "id,name,color"
PROJECT_SELECT = "id,name,color,created_at,updated_at"
ROUTINE_SELECT = "id,title,color,active,created_at,updated_at"
PERIOD_SELECT = "id,routine_id,starts_on,ends_on"

_auth_client = None
_auth_cache = OrderedDict()
_data_clients = OrderedDict()
_auth_cache_lock = RLock()


def _settings() -> tuple[str, str, str]:
    url = os.environ.get("SUPABASE_URL", "").strip()
    key = os.environ.get("SUPABASE_PUBLISHABLE_KEY", "").strip()
    origin = os.environ.get("FRONTEND_ORIGIN", "http://localhost:5173").strip()
    if not url or not key:
        raise RuntimeError("SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY are required")
    return url, key, origin


def _client_for_token(token: str) -> Client:
    with _auth_cache_lock:
        cached = _data_clients.get(token)
        if cached is not None:
            _data_clients.move_to_end(token)
            return cached
        url, key, _ = _settings()
        client = create_client(url, key, options=ClientOptions(headers={"Authorization": f"Bearer {token}"}))
        _data_clients[token] = client
        _data_clients.move_to_end(token)
        while len(_data_clients) > 128:
            _data_clients.popitem(last=False)
        return client


def _shared_auth_client() -> Client:
    global _auth_client
    with _auth_cache_lock:
        if _auth_client is None:
            url, key, _ = _settings()
            _auth_client = create_client(url, key)
    return _auth_client


def _token_expiry(token: str):
    try:
        encoded_payload = token.split(".")[1]
        encoded_payload += "=" * (-len(encoded_payload) % 4)
        payload = json.loads(base64.urlsafe_b64decode(encoded_payload).decode("utf-8"))
        return float(payload["exp"])
    except (IndexError, KeyError, TypeError, ValueError, UnicodeDecodeError, json.JSONDecodeError):
        return None


def _authenticated_user(token: str):
    now = time.monotonic()
    token_expiry = _token_expiry(token)
    with _auth_cache_lock:
        cached = _auth_cache.get(token)
        if cached and cached[0] > now and (token_expiry is None or token_expiry > time.time()):
            _auth_cache.move_to_end(token)
            return cached[1]

    response = _shared_auth_client().auth.get_user(token)
    if not response or not response.user:
        return None

    try:
        ttl = max(0.0, float(os.environ.get("AUTH_CACHE_TTL_SECONDS", "30")))
    except ValueError:
        ttl = 30.0
    if token_expiry is not None:
        ttl = min(ttl, max(0.0, token_expiry - time.time()))
    if ttl:
        with _auth_cache_lock:
            _auth_cache[token] = (now + ttl, response.user)
            _auth_cache.move_to_end(token)
            while len(_auth_cache) > 128:
                _auth_cache.popitem(last=False)
    return response.user


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
            user = _authenticated_user(token)
            if not user:
                return jsonify({"error": "Invalid access token"}), 401
            g.user = user
            g.supabase = _client_for_token(token)
        except Exception:
            return jsonify({"error": "Invalid access token"}), 401
        return view(*args, **kwargs)

    return wrapped


def _json_body(allowed_fields: set[str]):
    body = request.get_json(silent=True)
    if body is None:
        body = {}
    if not isinstance(body, dict):
        return None, {"error": "A JSON object is required"}
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


def _project_payload(required_name: bool = False):
    body, error = _json_body(PROJECT_FIELDS)
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
        return None, {"error": "color must be a hexadecimal value such as #ee7b6f"}
    return body, None


def _task_payload(required_title: bool = False):
    body, error = _json_body(TASK_FIELDS)
    if error:
        return None, error
    if "task_type" in body and body["task_type"] not in ("daily", "project"):
        return None, {"error": "Use /api/routines for daily habits"}
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


def _project_belongs_to_user(project_id: str) -> bool:
    result = g.supabase.schema("Task").table("projects").select("id").eq("id", project_id).eq("user_id", str(g.user.id)).execute()
    return bool(result.data)


def _validate_task_project(payload):
    project_id = payload.get("project_id")
    if payload.get("task_type") == "project" and not project_id:
        return {"error": "project_id is required for project tasks"}
    if project_id is not None and not _project_belongs_to_user(project_id):
        return {"error": "Project not found"}
    return None


def _routine_payload(required_title: bool = False):
    body, error = _json_body(ROUTINE_FIELDS)
    if error:
        return None, error
    if (required_title or "title" in body) and (not isinstance(body.get("title"), str) or not body["title"].strip()):
        return None, {"error": "title is required"}
    if "title" in body:
        body["title"] = str(body["title"]).strip()
    if "color" in body and (not isinstance(body["color"], str) or not HEX_COLOR.fullmatch(body["color"])):
        return None, {"error": "color must be a hexadecimal value such as #7c5cff"}
    if "active" in body and not isinstance(body["active"], bool):
        return None, {"error": "active must be boolean"}
    if "starts_on" in body:
        try:
            date.fromisoformat(body["starts_on"])
        except (TypeError, ValueError):
            return None, {"error": "starts_on must be an ISO date"}
    return body, None


def _date_range():
    start = request.args.get("from")
    end = request.args.get("to")
    try:
        start_date = date.fromisoformat(start) if start else date.today()
        end_date = date.fromisoformat(end) if end else start_date + timedelta(days=41)
    except ValueError:
        return None, None, {"error": "from and to must be ISO dates"}
    if end_date < start_date or (end_date - start_date).days > 366:
        return None, None, {"error": "Invalid date range"}
    return start_date, end_date, None


def _optional_task_range():
    start = request.args.get("from")
    end = request.args.get("to")
    if not start and not end:
        return None, None, None
    try:
        start_date = date.fromisoformat(start) if start else None
        end_date = date.fromisoformat(end) if end else start_date
    except (TypeError, ValueError):
        return None, None, {"error": "from and to must be ISO dates"}
    if not start_date or not end_date or end_date < start_date or (end_date - start_date).days > 366:
        return None, None, {"error": "Invalid date range"}
    return start_date, end_date, None


def _task_query(start_date=None, end_date=None):
    query = g.supabase.schema("Task").table("tasks").select(TASK_SELECT).neq("task_type", "routine")
    if start_date:
        query = query.gte("date", start_date.isoformat())
    if end_date:
        query = query.lte("date", end_date.isoformat())
    return query.order("date").order("start_time")


def _category_query():
    return g.supabase.schema("Task").table("category").select(CATEGORY_SELECT).order("name")


def _project_query():
    return g.supabase.schema("Task").table("projects").select(PROJECT_SELECT).order("name")


def _routine_day():
    timezone = os.environ.get("APP_TIMEZONE", "Europe/Madrid")
    now = datetime.now(ZoneInfo(timezone))
    tomorrow = datetime.combine(now.date() + timedelta(days=1), datetime.min.time(), now.tzinfo)
    return now.date(), timezone, tomorrow.isoformat()


def _routine_summaries(today, routine_id=None):
    query = g.supabase.schema("Task").rpc("routine_summaries", {"p_today": today.isoformat()})
    if routine_id:
        query = query.eq("id", routine_id)
    return query.execute().data or []


def _routine_snapshot():
    today, timezone, next_day_at = _routine_day()
    return {"date": today.isoformat(), "timezone": timezone, "next_day_at": next_day_at, "routines": _routine_summaries(today)}


def create_app():
    app = Flask(__name__)
    origin = os.environ.get("FRONTEND_ORIGIN", "http://localhost:5173").strip()
    origins = [origin]
    if urlparse(origin).hostname in {"localhost", "127.0.0.1"}:
        origins.extend(f"http://{host}:{port}" for host in ("localhost", "127.0.0.1") for port in range(5173, 5184))
    CORS(app, resources={r"/api/*": {"origins": origins}}, expose_headers=["Server-Timing", "X-Response-Time-ms"], supports_credentials=False)

    @app.errorhandler(Exception)
    def handle_api_error(error):
        if not request.path.startswith("/api/"):
            if isinstance(error, HTTPException):
                return error
            raise error
        if isinstance(error, HTTPException):
            return jsonify({"error": error.description}), error.code
        app.logger.exception("API request failed")
        return jsonify({"error": "No se pudo completar la operación. Inténtalo de nuevo."}), 500

    @app.before_request
    def start_request_timer():
        g.request_started_at = time.perf_counter()

    @app.after_request
    def add_response_timing(response):
        started_at = getattr(g, "request_started_at", None)
        if started_at is not None and request.path.startswith("/api/"):
            duration_ms = (time.perf_counter() - started_at) * 1000
            response.headers["Server-Timing"] = f"app;dur={duration_ms:.1f}"
            response.headers["X-Response-Time-ms"] = f"{duration_ms:.1f}"
        return response

    @app.get("/api/health")
    def health():
        return jsonify({"status": "ok"})

    @app.get("/api/categories")
    @require_user
    def list_categories():
        result = _category_query().execute()
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

    @app.get("/api/projects")
    @require_user
    def list_projects():
        result = _project_query().execute()
        return jsonify({"projects": result.data or []})

    @app.post("/api/projects")
    @require_user
    def create_project():
        payload, error = _project_payload(required_name=True)
        if error:
            return jsonify(error), 400
        payload["user_id"] = str(g.user.id)
        try:
            result = g.supabase.schema("Task").table("projects").insert(payload).execute()
        except Exception as exc:
            if "duplicate" in str(exc).lower() or "unique" in str(exc).lower():
                return jsonify({"error": "Ya existe un proyecto con ese nombre"}), 409
            raise
        return jsonify(result.data[0]), 201

    @app.patch("/api/projects/<project_id>")
    @require_user
    def update_project(project_id):
        payload, error = _project_payload()
        if error:
            return jsonify(error), 400
        if not payload:
            return jsonify({"error": "At least one field is required"}), 400
        try:
            result = g.supabase.schema("Task").table("projects").update(payload).eq("id", project_id).execute()
        except Exception as exc:
            if "duplicate" in str(exc).lower() or "unique" in str(exc).lower():
                return jsonify({"error": "Ya existe un proyecto con ese nombre"}), 409
            raise
        if not result.data:
            return jsonify({"error": "Project not found"}), 404
        return jsonify(result.data[0])

    @app.delete("/api/projects/<project_id>")
    @require_user
    def delete_project(project_id):
        result = g.supabase.schema("Task").table("projects").delete().eq("id", project_id).execute()
        if not result.data:
            return jsonify({"error": "Project not found"}), 404
        return jsonify({"deleted": project_id})

    @app.get("/api/routines")
    @require_user
    def list_routines():
        return jsonify(_routine_snapshot())

    @app.post("/api/routines")
    @require_user
    def create_routine():
        payload, error = _routine_payload(required_title=True)
        if error:
            return jsonify(error), 400
        today, _, _ = _routine_day()
        starts_on = payload.pop("starts_on", today.isoformat())
        active = payload.get("active", True)
        payload["user_id"] = str(g.user.id)
        result = g.supabase.schema("Task").table("routine").insert(payload).execute()
        routine = result.data[0]
        if active:
            g.supabase.schema("Task").table("routine_period").insert({"routine_id": routine["id"], "user_id": str(g.user.id), "starts_on": starts_on, "ends_on": None}).execute()
        return jsonify(_routine_summaries(today, routine["id"])[0]), 201

    @app.patch("/api/routines/<routine_id>")
    @require_user
    def update_routine(routine_id):
        payload, error = _routine_payload()
        if error:
            return jsonify(error), 400
        if not payload:
            return jsonify({"error": "At least one field is required"}), 400
        current_result = g.supabase.schema("Task").table("routine").select(ROUTINE_SELECT).eq("id", routine_id).execute()
        if not current_result.data:
            return jsonify({"error": "Routine not found"}), 404
        current = current_result.data[0]
        today, _, _ = _routine_day()
        payload.pop("starts_on", None)
        active_change = payload.get("active", current["active"])
        if payload:
            g.supabase.schema("Task").table("routine").update(payload).eq("id", routine_id).execute()
        if active_change != current["active"]:
            periods = g.supabase.schema("Task").table("routine_period").select(PERIOD_SELECT).eq("routine_id", routine_id).is_("ends_on", "null").execute().data or []
            if active_change:
                if not periods:
                    g.supabase.schema("Task").table("routine_period").insert({"routine_id": routine_id, "user_id": str(g.user.id), "starts_on": today.isoformat(), "ends_on": None}).execute()
            else:
                for period in periods:
                    query = g.supabase.schema("Task").table("routine_period")
                    if date.fromisoformat(period["starts_on"]) >= today:
                        query.delete().eq("id", period["id"]).execute()
                    else:
                        query.update({"ends_on": (today - timedelta(days=1)).isoformat()}).eq("id", period["id"]).execute()
        return jsonify(_routine_summaries(today, routine_id)[0])

    @app.delete("/api/routines/<routine_id>")
    @require_user
    def delete_routine(routine_id):
        result = g.supabase.schema("Task").table("routine").delete().eq("id", routine_id).execute()
        if not result.data:
            return jsonify({"error": "Routine not found"}), 404
        return jsonify({"deleted": routine_id})

    @app.patch("/api/routines/<routine_id>/completion")
    @require_user
    def update_routine_completion(routine_id):
        payload = request.get_json(silent=True)
        if not isinstance(payload, dict) or set(payload) != {"date", "completed"} or not isinstance(payload["completed"], bool):
            return jsonify({"error": "date and boolean completed are required"}), 400
        try:
            target_date = date.fromisoformat(payload["date"])
        except (TypeError, ValueError):
            return jsonify({"error": "date must be an ISO date"}), 400
        today, timezone, next_day_at = _routine_day()
        if target_date != today:
            return jsonify({"error": "El día ha cambiado. Actualiza las rutinas.", "date": today.isoformat()}), 409
        routines = _routine_summaries(today, routine_id)
        if not routines or not routines[0]["due_today"]:
            return jsonify({"error": "Routine is not due today"}), 404
        g.supabase.schema("Task").table("routine_completion").upsert({"routine_id": routine_id, "user_id": str(g.user.id), "occurrence_date": today.isoformat(), "completed": payload["completed"]}, on_conflict="routine_id,occurrence_date").execute()
        return jsonify({"date": today.isoformat(), "timezone": timezone, "next_day_at": next_day_at, "routine": _routine_summaries(today, routine_id)[0]})

    @app.get("/api/calendar")
    @require_user
    def list_calendar_data():
        start_date, end_date, error = _date_range()
        if error:
            return jsonify(error), 400
        tasks = _task_query(start_date, end_date).execute()
        return jsonify({"tasks": tasks.data or []})

    @app.get("/api/bootstrap")
    @require_user
    def bootstrap():
        start_date, end_date, error = _date_range()
        if error:
            return jsonify(error), 400
        categories = _category_query().execute()
        projects = _project_query().execute()
        tasks = _task_query(start_date, end_date).execute()
        return jsonify({"categories": categories.data or [], "projects": projects.data or [], "tasks": tasks.data or []})

    @app.get("/api/tasks")
    @require_user
    def list_tasks():
        start_date, end_date, error = _optional_task_range()
        if error:
            return jsonify(error), 400
        result = _task_query(start_date, end_date).execute()
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
        project_error = _validate_task_project(payload)
        if project_error:
            return jsonify(project_error), 400
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
        project_error = _validate_task_project(payload)
        if project_error:
            return jsonify(project_error), 400
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
