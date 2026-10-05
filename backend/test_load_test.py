import json
import sys
from pathlib import Path
from urllib.error import HTTPError, URLError
import pytest
import load_test
from load_test import benchmark, compare, percentile


def test_percentiles():
    assert percentile([], .95) == 0
    assert percentile([4, 1, 2, 3], .5) == 2
    assert percentile(list(range(1, 101)), .95) == 95


def test_readonly_benchmark_reports_errors_without_tokens():
    seen = []
    class Response:
        status = 200
        def __enter__(self):
            return self
        def __exit__(self, *args):
            pass
        def read(self):
            return b"{}"

    def opener(request, timeout):
        seen.append((request.method, request.full_url))
        if request.full_url.endswith("/error"):
            raise HTTPError(request.full_url, 401, "private-token", {}, None)
        if request.full_url.endswith("/offline"):
            raise URLError("private-token")
        return Response()

    report = benchmark("http://localhost", ["private-token"], ["/api/tasks", "/error", "/offline"], 2, .02, 1, opener=opener)
    assert report["requests"] > 0
    assert report["errors"] > 0
    assert report["statuses"]["401"] > 0
    assert report["statuses"]["connection_error"] > 0
    assert set(method for method, _ in seen) == {"GET"}
    assert "private-token" not in json.dumps(report)
    assert report["connection_errors"]
    assert report["successful_requests_per_second"] < report["requests_per_second"]
    assert report["requests"] == sum(row["requests"] for row in report["endpoints"].values())


def test_baseline_comparison():
    before = {"runs": [{"concurrency": 1, "requests_per_second": 100, "p95_ms": 20, "errors": 1}]}
    after = {"runs": [{"concurrency": 1, "requests_per_second": 110, "p95_ms": 15, "errors": 0}]}
    assert compare(after, before) == [{"concurrency": 1, "requests_per_second_delta": 10, "p95_ms_delta": -5, "errors_delta": -1}]


def test_disposable_ui_fixture_uses_real_use_cases():
    from ui_smoke import demo_app
    client = demo_app().test_client()
    headers = {"Authorization": "Bearer demo-token"}
    assert client.get("/api/tasks", headers={"Authorization": "Bearer real-token"}).status_code == 401
    category = client.post("/api/categories", headers=headers, json={"name": "Física", "color": "#123456"}).json
    project = client.post("/api/projects", headers=headers, json={"name": "Examen", "color": "#abcdef"}).json
    task = client.post("/api/tasks", headers=headers, json={"title": "Repasar", "task_type": "project", "category_id": category["id"],
                         "project_id": project["id"], "date": "2026-10-06", "start_time": "09:00", "end_time": "10:00"}).json
    client.delete("/api/projects/" + project["id"], headers=headers)
    client.delete("/api/categories/" + category["id"], headers=headers)
    result = client.get("/api/tasks", headers=headers).json[0]
    assert result["project_id"] is None and result["category_id"] is None
    assert client.delete("/api/tasks/" + task["id"], headers=headers).status_code == 200


@pytest.mark.parametrize("flag,value", [("--duration", "nan"), ("--timeout", "inf"), ("--interval", "-1")])
def test_cli_rejects_invalid_workload_without_network(monkeypatch, flag, value):
    monkeypatch.setattr(sys, "argv", ["load_test.py", "--base-url", "http://localhost", flag, value])
    monkeypatch.setattr(load_test, "benchmark", lambda *args, **kwargs: pytest.fail("unexpected request"))
    with pytest.raises(SystemExit) as error:
        load_test.main()
    assert error.value.code == 2


def test_cli_rejects_incompatible_baseline_before_requests(monkeypatch):
    monkeypatch.setattr(sys, "argv", ["load_test.py", "--base-url", "http://localhost", "--baseline", "baseline.local",
                                     "--from-date", "2026-10-05", "--to-date", "2026-10-11"])
    monkeypatch.setenv("LOAD_TEST_TOKENS", '["private-token"]')
    monkeypatch.setattr(Path, "read_text", lambda *args, **kwargs: json.dumps({"duration_seconds": 5, "runs": []}))
    monkeypatch.setattr(load_test, "benchmark", lambda *args, **kwargs: pytest.fail("unexpected request"))
    with pytest.raises(SystemExit) as error:
        load_test.main()
    assert error.value.code == 2
