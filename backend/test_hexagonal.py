import ast
from contextlib import contextmanager
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from datetime import date, datetime, timezone
import json
from pathlib import Path
from types import SimpleNamespace
from zoneinfo import ZoneInfo
import base64

import httpx
import pytest
from postgrest import SyncPostgrestClient

from app import create_app
from studycalendar.config import Settings
from studycalendar.dependencies import Dependencies, Repositories
from studycalendar.shared.domain import User, BusinessError, DuplicateName, Command
from studycalendar.shared.clock import SystemClock
from studycalendar.shared.supabase_runtime import SupabaseRuntime
from studycalendar.tasks.domain import Task
from studycalendar.tasks.application import TaskService
from studycalendar.categories.domain import Category
from studycalendar.projects.domain import Project
from studycalendar.routines.domain import Routine, RoutineSummary, Period
from studycalendar.routines.application import RoutineService
from tests_support import FixedAuth, FixedClock, DatabaseProvider

HEADERS = {"Authorization": "Bearer test-token"}


class MemoryRepository:
    def __init__(self, model, rows=None):
        self.model = model
        self.rows = dict(rows or {})
        self.writes = []

    def list(self, start=None, end=None):
        return list(self.rows.values())

    def belongs_to(self, identifier, user_id):
        row = self.rows.get(identifier)
        return row is not None and row.extra.get("user_id") == user_id

    def create(self, payload):
        self.writes.append(("create", dict(payload)))
        result = self.model.from_record({"id": "new", **payload})
        self.rows[result.id] = result
        return result

    def update(self, identifier, payload):
        self.writes.append(("update", dict(payload)))
        if identifier not in self.rows:
            return None
        result = replace(self.rows[identifier], **payload)
        self.rows[identifier] = result
        return result

    def delete(self, identifier):
        return self.rows.pop(identifier, None) is not None

    def set_category_color(self, identifier, color):
        self.writes.append(("category_color", identifier, color))


class MemoryRoutines:
    def __init__(self, active=True, periods=None, due=True):
        self.routine = Routine(id="r", title="Leer", active=active)
        self.periods = list(periods or [])
        self.completions = {date(2026, 10, 4): True}
        self.due = due
        self.writes = []
        self.summary_dates = []

    def get(self, identifier):
        return self.routine if identifier == "r" else None

    def create(self, payload):
        self.writes.append(("create", dict(payload)))
        self.routine = Routine(id="r", title=payload["title"], active=payload.get("active", True))
        return self.routine

    def update(self, identifier, payload):
        self.routine = replace(self.routine, **payload)

    def delete(self, identifier):
        return identifier == "r"

    def open_periods(self, identifier):
        return self.periods

    def add_period(self, identifier, user_id, starts_on):
        self.writes.append(("period", identifier, user_id, starts_on))

    def remove_period(self, identifier):
        self.writes.append(("remove", identifier))

    def close_period(self, identifier, ends_on):
        self.writes.append(("close", identifier, ends_on))

    def complete(self, identifier, user_id, today, completed):
        self.completions[today] = completed
        self.writes.append(("complete", identifier, user_id, today, completed))

    def summaries(self, today, routine_id=None):
        self.summary_dates.append(today)
        if routine_id not in (None, "r"):
            return []
        return [RoutineSummary(id="r", active=self.routine.active, due_today=self.due,
                               completed_today=self.completions.get(today, False), current_streak=1, max_streak=1)]


@pytest.fixture
def memory():
    tasks = MemoryRepository(Task, {"t": Task(id="t", title="Estudiar", project_id="p")})
    categories = MemoryRepository(Category, {
        "c": Category.from_record({"id": "c", "name": "Física", "user_id": "user-1"}),
        "foreign": Category.from_record({"id": "foreign", "name": "Otro", "user_id": "user-2"}),
    })
    projects = MemoryRepository(Project, {
        "p": Project.from_record({"id": "p", "name": "Examen", "user_id": "user-1"}),
        "foreign": Project.from_record({"id": "foreign", "name": "Otro", "user_id": "user-2"}),
    })
    routines = MemoryRoutines()
    repositories = Repositories(tasks, categories, projects, routines, routines)

    class Provider:
        @contextmanager
        def scope(self, token, user):
            yield repositories

    clock = FixedClock()
    app = create_app(Settings(), Dependencies(FixedAuth(), Provider(), clock))
    return app.test_client(), repositories, clock


def test_pure_task_service_preserves_sparse_patch(memory):
    _, repos, clock = memory
    service = TaskService(repos.tasks, repos.categories, repos.projects, User("user-1"), clock)
    result = service.update("t", {"title": " Nuevo "})
    assert result.title == "Nuevo"
    assert result.project_id == "p"
    assert repos.tasks.writes[-1] == ("update", {"title": "Nuevo"})
    assert service.update("t", {"project_id": None}).project_id is None
    assert "project_id" not in Command.parse({}, {"project_id"}).values
    assert Command.parse({"project_id": None}, {"project_id"}).values["project_id"] is None


@pytest.mark.parametrize("payload,message", [
    ({"title": "T", "category_id": "foreign"}, "Category not found"),
    ({"title": "T", "project_id": "foreign"}, "Project not found"),
    ({"title": "T", "task_type": "project"}, "project_id is required for project tasks"),
    ({"title": "T", "task_type": "routine"}, "Use /api/routines for daily habits"),
    ({"title": " "}, "title is required"),
    ({"title": "T", "user_id": "user-2"}, "Unsupported fields: user_id"),
])
def test_task_validation_has_no_writes(memory, payload, message):
    client, repos, _ = memory
    response = client.post("/api/tasks", headers=HEADERS, json=payload)
    assert response.status_code == 400
    assert response.json == {"error": message}
    assert repos.tasks.writes == []


def test_http_task_crud_and_unassigned_project_compatibility(memory):
    client, _, _ = memory
    created = client.post("/api/tasks", headers=HEADERS,
                          json={"title": " Nueva ", "task_type": "project", "project_id": "p", "category_id": "c"})
    assert created.status_code == 201
    assert created.json["title"] == "Nueva"
    assert created.json["user_id"] == "user-1"
    assert client.get("/api/tasks", headers=HEADERS).status_code == 200
    assert client.patch("/api/tasks/t", headers=HEADERS, json={"project_id": None}).status_code == 200
    assert client.delete("/api/tasks/t", headers=HEADERS).json == {"deleted": "t"}
    assert client.delete("/api/tasks/t", headers=HEADERS).status_code == 404


@pytest.mark.parametrize("feature", ["tasks", "categories", "projects", "routines"])
def test_missing_resource_and_empty_patch(memory, feature):
    client, _, _ = memory
    assert client.patch(f"/api/{feature}/missing", headers=HEADERS, json={}).status_code == 400
    payload = {"name": "Nombre"} if feature in ("categories", "projects") else {"title": "Título"}
    assert client.patch(f"/api/{feature}/missing", headers=HEADERS, json=payload).status_code == 404
    assert client.delete(f"/api/{feature}/missing", headers=HEADERS).status_code == 404


@pytest.mark.parametrize("feature", ["categories", "projects"])
@pytest.mark.parametrize("method", ["post", "patch"])
def test_unique_conflicts_are_business_errors(memory, feature, method):
    client, repos, _ = memory
    def duplicate(*args):
        raise DuplicateName()
    setattr(getattr(repos, feature), "create" if method == "post" else "update", duplicate)
    path = f"/api/{feature}" + ("/c" if method == "patch" else "")
    response = getattr(client, method)(path, headers=HEADERS, json={"name": "Física"})
    assert response.status_code == 409
    assert response.json == {"error": "A category with this name already exists" if feature == "categories" else "Ya existe un proyecto con ese nombre"}


def test_category_crud_and_color_propagation(memory):
    client, repos, _ = memory
    created = client.post("/api/categories", headers=HEADERS, json={"name": " Química ", "color": "#123abc"})
    assert created.status_code == 201
    assert created.json == {"id": "new", "name": "Química", "color": "#123abc", "user_id": "user-1"}
    updated = client.patch("/api/categories/c", headers=HEADERS, json={"color": "#abcdef"})
    assert updated.status_code == 200
    assert repos.tasks.writes == [("category_color", "c", "#abcdef")]
    assert client.get("/api/categories", headers=HEADERS).status_code == 200
    assert client.delete("/api/categories/c", headers=HEADERS).json == {"deleted": "c"}


@pytest.mark.parametrize("payload,message", [
    ([], "A JSON object is required"), ({}, "name is required"),
    ({"name": " "}, "name is required"),
    ({"name": "N", "color": None}, "color must be a hexadecimal value such as #7c5cff"),
    ({"name": "N", "color": "red"}, "color must be a hexadecimal value such as #7c5cff"),
    ({"name": "N", "unknown": True}, "Unsupported fields: unknown"),
])
def test_category_invalid_inputs(memory, payload, message):
    client, repos, _ = memory
    response = client.post("/api/categories", headers=HEADERS, json=payload)
    assert response.status_code == 400
    assert response.json == {"error": message}
    assert repos.categories.writes == []


@pytest.mark.parametrize("endpoint,query,status", [
    ("tasks", "", 200), ("tasks", "?from=2026-10-05", 200),
    ("tasks", "?to=2026-10-05", 400), ("tasks", "?from=bad", 400),
    ("calendar", "", 200), ("bootstrap", "", 200),
    ("calendar", "?from=2026-10-06&to=2026-10-05", 400),
    ("bootstrap", "?from=2025-01-01&to=2026-01-03", 400),
    ("calendar", "?from=2026-01-01&to=2027-01-02", 200),
])
def test_range_contract(memory, endpoint, query, status):
    client, _, _ = memory
    assert client.get(f"/api/{endpoint}{query}", headers=HEADERS).status_code == status


def test_historic_permissive_task_validation(memory):
    client, repos, _ = memory
    response = client.patch("/api/tasks/t", headers=HEADERS, json={"title": " ", "start_time": None})
    assert response.status_code == 200
    assert repos.tasks.writes[-1] == ("update", {"title": "", "start_time": None})


@pytest.mark.parametrize("starts_on,expected", [
    ("2026-10-01", ("close", "period", "2026-10-04")),
    ("2026-10-05", ("remove", "period")),
    ("2026-10-07", ("remove", "period")),
])
def test_pure_routine_pause_preserves_completions(starts_on, expected):
    repo = MemoryRoutines(periods=[Period(id="period", starts_on=starts_on)])
    clock = FixedClock()
    service = RoutineService(repo, repo, User("user-1"), clock)
    service.update("r", {"active": False})
    assert repo.writes == [expected]
    assert repo.completions == {date(2026, 10, 4): True}
    assert clock.calls == 1


def test_pure_reactivation_and_start_date_patch():
    repo = MemoryRoutines(active=False)
    clock = FixedClock()
    service = RoutineService(repo, repo, User("user-1"), clock)
    service.update("r", {"active": True, "starts_on": "2026-11-01"})
    assert repo.writes == [("period", "r", "user-1", "2026-10-05")]
    assert repo.completions == {date(2026, 10, 4): True}
    clock.calls = 0
    repo.writes.clear()
    service.update("r", {"starts_on": "2026-11-01"})
    assert repo.writes == []
    assert clock.calls == 1


@pytest.mark.parametrize("completed", [True, False])
def test_pure_completion_uses_one_day_snapshot(completed):
    repo = MemoryRoutines()
    clock = FixedClock()
    result = RoutineService(repo, repo, User("user-1"), clock).complete("r", {"date": "2026-10-05", "completed": completed})
    assert clock.calls == 1
    assert result.routine.completed_today is completed
    assert repo.completions[date(2026, 10, 4)] is True
    assert repo.summary_dates == [clock.today, clock.today]


@pytest.mark.parametrize("day,due,kind", [("2026-10-04", True, "conflict"), ("2026-10-06", True, "conflict"), ("2026-10-05", False, "not_found")])
def test_pure_completion_rejections_do_not_write(day, due, kind):
    repo = MemoryRoutines(due=due)
    service = RoutineService(repo, repo, User("user-1"), FixedClock())
    with pytest.raises(BusinessError) as error:
        service.complete("r", {"date": day, "completed": True})
    assert error.value.kind == kind
    assert repo.writes == []


def test_inactive_creation_does_not_create_period():
    repo = MemoryRoutines()
    service = RoutineService(repo, repo, User("user-1"), FixedClock())
    service.create({"title": " Leer ", "active": False, "starts_on": "2026-11-01"})
    assert repo.writes == [("create", {"title": "Leer", "active": False, "user_id": "user-1"})]


@pytest.mark.parametrize("instant,next_day,hours", [
    (datetime(2026, 3, 29, 0, 0), "2026-03-30T00:00:00+02:00", 23),
    (datetime(2026, 10, 25, 0, 0), "2026-10-26T00:00:00+01:00", 25),
])
def test_both_dst_boundaries(instant, next_day, hours):
    clock = SystemClock("Europe/Madrid", now=lambda tz: instant.replace(tzinfo=tz))
    day = clock.day()
    assert day.next_day_at == next_day
    start = clock.now(ZoneInfo("Europe/Madrid")).astimezone(timezone.utc)
    end = datetime.fromisoformat(next_day).astimezone(timezone.utc)
    assert (end - start).total_seconds() == hours * 3600


def test_midnight_changes_authoritative_day():
    moments = iter([datetime(2026, 10, 5, 23, 59, 59), datetime(2026, 10, 6, 0, 0)])
    clock = SystemClock("Europe/Madrid", now=lambda tz: next(moments).replace(tzinfo=tz))
    assert clock.day().date == date(2026, 10, 5)
    assert clock.day().date == date(2026, 10, 6)


def fake_runtime(ttl=30):
    transports, calls, now = [], [], [100.0]
    class Transport:
        def __init__(self, **kwargs):
            self.closed = False
            self.close_calls = 0
            transports.append(self)
        def close(self):
            self.closed = True
            self.close_calls += 1

    def factory(url, key, options):
        class Auth:
            def get_user(self, token):
                calls.append(token)
                return SimpleNamespace(user=SimpleNamespace(id=token))
        return SimpleNamespace(auth=Auth(), postgrest=object(), options=options)
    runtime = SupabaseRuntime(Settings(supabase_url="https://example.invalid", supabase_publishable_key="test", auth_cache_ttl_seconds=ttl),
                              client_factory=factory, transport_factory=Transport,
                              monotonic=lambda: now[0], wall_time=lambda: now[0])
    return runtime, transports, calls, now


def test_auth_cache_ttl_expiry_and_bound():
    runtime, transports, calls, now = fake_runtime()
    assert runtime.authenticate("a") == User("a")
    runtime.authenticate("a")
    assert calls == ["a"]
    now[0] += 31
    runtime.authenticate("a")
    assert calls == ["a", "a"]
    for n in range(140):
        runtime.authenticate(str(n))
    assert len(runtime.auth_cache) == 128
    assert len(transports) == 1
    runtime.close()
    assert transports[0].closed


def test_jwt_expiry_is_cache_hint_not_authentication():
    runtime, _, calls, now = fake_runtime()
    payload = base64.urlsafe_b64encode(json.dumps({"exp": 105}).encode()).decode().rstrip("=")
    token = f"x.{payload}.x"
    runtime.authenticate(token)
    now[0] = 104
    runtime.authenticate(token)
    assert len(calls) == 1
    now[0] = 106
    runtime.authenticate(token)
    assert len(calls) == 2
    assert token not in runtime.auth_cache
    runtime.close()


def test_zero_ttl_always_checks_auth():
    runtime, _, calls, _ = fake_runtime(ttl=0)
    runtime.authenticate("a")
    runtime.authenticate("a")
    assert calls == ["a", "a"]
    assert not runtime.auth_cache
    runtime.close()


def test_evicted_connection_waits_for_inflight_request():
    runtime, transports, _, _ = fake_runtime()
    with runtime.lease("a") as first:
        assert first.options.headers["Authorization"] == "Bearer a"
        for n in range(128):
            with runtime.lease(str(n)):
                pass
        assert len(runtime.data_clients) == 128
        assert "a" not in runtime.data_clients
        assert not transports[0].closed
    assert transports[0].closed
    runtime.close()
    assert all(t.close_calls == 1 for t in transports)


def test_shutdown_waits_for_auth_and_data_leases():
    runtime, transports, _, _ = fake_runtime()
    with runtime.lease():
        with runtime.lease("a"):
            runtime.close()
            assert not any(t.closed for t in transports)
        assert transports[1].closed
    assert transports[0].closed
    runtime.close()
    assert all(t.close_calls == 1 for t in transports)
    with pytest.raises(RuntimeError):
        with runtime.lease("b"):
            pass


def test_factory_instances_have_independent_runtime_and_lazy_settings():
    first, second = create_app(Settings()), create_app(Settings())
    assert first.extensions["studycalendar.runtime"] is not second.extensions["studycalendar.runtime"]
    for app in (first, second):
        assert app.test_client().get("/api/health").json == {"status": "ok"}
        assert app.test_client().get("/api/tasks", headers=HEADERS).status_code == 401
        app.extensions["studycalendar.runtime"].close()


def test_concurrent_users_keep_bearer_token_and_schema():
    seen = []
    def respond(request):
        token = request.headers["Authorization"].removeprefix("Bearer ")
        seen.append((request.url.path, token, request.headers.get("Accept-Profile")))
        if request.url.path.endswith("/user"):
            return httpx.Response(200, json={"id": token, "aud": "authenticated", "created_at": "2026-10-01T00:00:00Z",
                                            "app_metadata": {}, "user_metadata": {}})
        return httpx.Response(200, json=[{"id": token, "name": token, "color": "#123456"}])
    runtime = SupabaseRuntime(
        Settings(supabase_url="https://example.invalid", supabase_publishable_key="test"),
        transport_factory=lambda **kwargs: httpx.Client(transport=httpx.MockTransport(respond), **kwargs),
    )
    app = create_app(Settings(), Dependencies(runtime, runtime, FixedClock()))
    def fetch(index):
        token = f"user-{index % 2}"
        with app.test_client() as client:
            response = client.get("/api/categories", headers={"Authorization": f"Bearer {token}"})
            assert response.status_code == 200
            assert response.json[0]["id"] == token
    try:
        with ThreadPoolExecutor(max_workers=8) as executor:
            list(executor.map(fetch, range(80)))
        assert all(schema == "Task" for path, _, schema in seen if path.endswith("/category"))
        assert len(runtime.data_clients) == 2
    finally:
        runtime.close()


def test_sdk_task_category_queries_conflicts_and_json_errors():
    requests = []
    def respond(request):
        requests.append(request)
        if request.url.params.get("id") == "eq.missing":
            return httpx.Response(200, json=[])
        body = json.loads(request.content) if request.content else {}
        if body.get("name") == "duplicate":
            return httpx.Response(409, json={"code": "23505", "message": "constraint violated", "details": "", "hint": ""})
        row = {"id": "c", "name": "Física", "color": "#123456"} if request.url.path.endswith("/category") else {"id": "t", "title": "Estudiar", "completed": True}
        return httpx.Response(201 if request.method == "POST" else 200, json=[{**row, **body}])
    with httpx.Client(transport=httpx.MockTransport(respond), trust_env=False) as transport:
        database = SyncPostgrestClient("https://example.invalid/rest/v1", schema="Task", http_client=transport)
        database.schema = lambda _: database
        client = create_app(Settings(), Dependencies(FixedAuth(), DatabaseProvider(database), FixedClock())).test_client()
        response = client.post("/api/tasks", headers=HEADERS, json={"title": "T", "category_id": "c"})
        assert response.status_code == 201
        assert requests[0].url.params["user_id"] == "eq.user-1"
        assert json.loads(requests[1].content)["user_id"] == "user-1"
        requests.clear()
        assert client.get("/api/tasks?from=2026-10-01&to=2026-10-07", headers=HEADERS).json[0]["completed"] is True
        params = requests[0].url.params
        assert params.get_list("date") == ["gte.2026-10-01", "lte.2026-10-07"]
        assert params["order"] == "date.asc,start_time.asc"
        assert params["task_type"] == "neq.routine"
        requests.clear()
        assert client.patch("/api/categories/c", headers=HEADERS, json={"color": "#abcdef"}).status_code == 200
        assert requests[1].url.params["category_id"] == "eq.c"
        assert json.loads(requests[1].content) == {"color": "#abcdef"}
        assert client.post("/api/categories", headers=HEADERS, json={"name": "duplicate"}).status_code == 409
        assert client.patch("/api/tasks/missing", headers=HEADERS, json={"title": "T"}).status_code == 404
        assert client.post("/api/tasks", headers=HEADERS, json={"title": "T", "category_id": "missing"}).status_code == 400
        assert all(req.headers.get("Accept-Profile", req.headers.get("Content-Profile")) == "Task" for req in requests)


def test_core_dependency_boundaries():
    root = Path(__file__).parent / "studycalendar"
    for path in root.rglob("*.py"):
        if path.name not in {"domain.py", "application.py", "ports.py", "dependencies.py"}:
            continue
        tree = ast.parse(path.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            imports = [item.name for item in node.names] if isinstance(node, ast.Import) else [node.module or ""] if isinstance(node, ast.ImportFrom) else []
            for name in imports:
                assert not any(part in name.split(".") for part in ("flask", "flask_cors", "supabase", "postgrest", "httpx", "adapters", "http", "factory", "supabase_runtime", "persistence")), (path, name)


def test_provider_sees_failure_and_releases_request_scope(memory):
    _, repositories, clock = memory
    released = []
    class Provider:
        @contextmanager
        def scope(self, token, user):
            try:
                yield replace(repositories, tasks=object())
            except Exception as exc:
                released.append(type(exc))
                raise
    app = create_app(Settings(), Dependencies(FixedAuth(), Provider(), clock))
    response = app.test_client().get("/api/tasks", headers=HEADERS)
    assert response.status_code == 500
    assert released == [AttributeError]
    assert "AttributeError" not in response.json["error"]
    assert "Server-Timing" in response.headers
    assert "X-Response-Time-ms" in response.headers


def test_failed_client_initialization_closes_transport():
    runtime, transports, _, _ = fake_runtime()
    def broken(*args, **kwargs):
        raise ValueError("SDK failure")
    runtime.client_factory = broken
    with pytest.raises(ValueError):
        with runtime.lease("a"):
            pass
    assert transports[0].close_calls == 1
    assert not runtime.data_clients
    runtime.close()
