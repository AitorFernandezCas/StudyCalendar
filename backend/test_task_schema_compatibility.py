"""Exercise both task schemas with the installed PostgREST SDK and HTTP routes."""
import json

import httpx
import pytest
from postgrest import SyncPostgrestClient

from app import create_app
from studycalendar.config import Settings
from studycalendar.dependencies import Dependencies
from tests_support import DatabaseProvider, FixedAuth, FixedClock

HEADERS = {"Authorization": "Bearer test-token"}
ROW = {"id": "task-1", "title": "Estudiar", "task_type": "daily", "date": "2026-10-05",
       "start_time": "09:00", "end_time": "10:00", "completed": False}
MISSING = {"code": "42703", "message": "column tasks.all_day does not exist", "details": None, "hint": None}


@pytest.fixture
def legacy_api():
    requests = []

    def respond(request):
        requests.append(request)
        assert request.headers.get("Accept-Profile", request.headers.get("Content-Profile")) == "Task"
        if not request.url.path.endswith("/tasks"):
            return httpx.Response(200, json=[])
        if "all_day" in request.url.params.get("select", "").split(","):
            return httpx.Response(400, json=MISSING)
        if request.url.params.get("id") == "eq.unowned":
            return httpx.Response(200, json=[])
        payload = json.loads(request.content) if request.content else {}
        assert "all_day" not in payload
        return httpx.Response(201 if request.method == "POST" else 200, json=[{**ROW, **payload}])

    with httpx.Client(transport=httpx.MockTransport(respond), trust_env=False) as transport:
        database = SyncPostgrestClient("https://example.invalid/rest/v1", schema="Task", http_client=transport)
        app = create_app(Settings(), Dependencies(FixedAuth(), DatabaseProvider(database), FixedClock()))
        yield app.test_client(), requests


@pytest.mark.parametrize("path", ["/api/tasks", "/api/calendar?from=2026-10-01&to=2026-10-07", "/api/bootstrap?from=2026-10-01&to=2026-10-07"])
def test_legacy_reads_keep_task_filters_and_default_all_day(legacy_api, path):
    client, requests = legacy_api
    response = client.get(path, headers=HEADERS)
    assert response.status_code == 200
    tasks = response.json if isinstance(response.json, list) else response.json["tasks"]
    assert tasks[0]["id"] == "task-1" and tasks[0]["all_day"] is False
    task_requests = [request for request in requests if request.url.path.endswith("/tasks")]
    assert len(task_requests) == 2
    for request in task_requests:
        assert request.method == "GET"
        assert request.url.params["task_type"] == "neq.routine"
        assert request.url.params["order"] == "date.asc,start_time.asc"
        if "from=" in path:
            assert request.url.params.get_list("date") == ["gte.2026-10-01", "lte.2026-10-07"]


@pytest.mark.parametrize("method,path,status", [("POST", "/api/tasks", 201), ("PATCH", "/api/tasks/task-1", 200)])
def test_legacy_timed_writes_probe_before_one_mutation(legacy_api, method, path, status):
    client, requests = legacy_api
    response = client.open(path, method=method, headers=HEADERS, json={"title": "Tarea con horario", "all_day": False})
    assert response.status_code == status and response.json["all_day"] is False
    assert [request.method for request in requests] == ["GET", method]
    assert requests[0].url.params["limit"] == "0"
    payload = json.loads(requests[1].content)
    assert payload["title"] == "Tarea con horario" and "all_day" not in payload
    if method == "POST":
        assert payload["user_id"] == "user-1"
    else:
        assert requests[1].url.params["id"] == "eq.task-1"


@pytest.mark.parametrize("method,path", [("POST", "/api/tasks"), ("PATCH", "/api/tasks/task-1")])
def test_legacy_all_day_writes_fail_clearly_without_mutating(legacy_api, method, path):
    client, requests = legacy_api
    response = client.open(path, method=method, headers=HEADERS, json={"title": "Todo el día", "all_day": True})
    assert response.status_code == 409
    assert "actualizar la base de datos" in response.json["error"]
    assert [request.method for request in requests] == ["GET"]


@pytest.mark.parametrize("identifier,status", [("task-1", 200), ("unowned", 404)])
def test_legacy_false_only_patch_still_checks_owned_row(legacy_api, identifier, status):
    client, requests = legacy_api
    response = client.patch(f"/api/tasks/{identifier}", headers=HEADERS, json={"all_day": False})
    assert response.status_code == status
    assert [request.method for request in requests] == ["GET", "GET"]
    assert requests[1].url.params["id"] == f"eq.{identifier}"


@pytest.mark.parametrize("error", [
    {"code": "42501", "message": "permission denied for table tasks"},
    {"code": "42703", "message": "column tasks.project_id does not exist"},
])
def test_unrelated_database_errors_are_not_retried(error):
    requests = []

    def respond(request):
        requests.append(request)
        return httpx.Response(400, json={**error, "details": None, "hint": None})

    with httpx.Client(transport=httpx.MockTransport(respond), trust_env=False) as transport:
        database = SyncPostgrestClient("https://example.invalid/rest/v1", schema="Task", http_client=transport)
        client = create_app(Settings(), Dependencies(FixedAuth(), DatabaseProvider(database), FixedClock())).test_client()
        assert client.get("/api/tasks", headers=HEADERS).status_code == 500
        assert len(requests) == 1
