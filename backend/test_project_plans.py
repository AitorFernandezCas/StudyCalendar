from dataclasses import replace
import pytest
from studycalendar.tasks.domain import Task
from studycalendar.tasks.planning import validate_plan
from studycalendar.shared.domain import BusinessError
from test_hexagonal import memory, HEADERS


def task(identifier, parents=(), completed=False, project="p"):
    return Task(id=identifier, task_type="project", project_id=project,
                prerequisite_ids=list(parents), completed=completed)


def test_branching_completion_and_reverse_reopening():
    a, b, c, d = task("a"), task("b", ["a"]), task("c", ["a"]), task("d", ["b", "c"])
    with pytest.raises(BusinessError):
        validate_plan(replace(d, completed=True), [a, b, c, d])
    rows = [replace(a, completed=True), replace(b, completed=True), c, d]
    with pytest.raises(BusinessError):
        validate_plan(replace(d, completed=True), rows)
    rows[2] = replace(c, completed=True)
    validate_plan(replace(d, completed=True), rows)
    with pytest.raises(BusinessError):
        validate_plan(a, rows)


def test_cycles_foreign_links_and_individual_lifecycle():
    a, b = task("a"), task("b", ["a"])
    for candidate in [replace(a, prerequisite_ids=["b"]), replace(a, prerequisite_ids=["a"]), replace(a, prerequisite_ids=["missing"])]:
        with pytest.raises(BusinessError):
            validate_plan(candidate, [a, b])
    with pytest.raises(BusinessError):
        validate_plan(replace(a, project_id="other"), [a, b], a)
    with pytest.raises(BusinessError):
        validate_plan(a, [a, b], deleting=True)
    with pytest.raises(BusinessError):
        validate_plan(replace(b, project_id="other"), [a, b], b)


def test_undated_project_http_and_daily_conversion(memory):
    client, repos, _ = memory
    response = client.post('/api/tasks', headers=HEADERS, json={"title": "Plan", "task_type": "project", "completed": False})
    assert response.status_code == 201
    assert response.json['date'] is None
    assert response.json['start_time'] is None
    identifier = response.json['id']
    assert client.patch(f'/api/tasks/{identifier}', headers=HEADERS, json={"completed": True}).status_code == 200
    assert client.patch(f'/api/tasks/{identifier}', headers=HEADERS, json={"task_type": "daily"}).status_code == 400
    scheduled = client.patch(f'/api/tasks/{identifier}', headers=HEADERS, json={"date": "2026-11-06"})
    assert scheduled.status_code == 200 and scheduled.json['start_time'] == '09:00'
    cleared = client.patch(f'/api/tasks/{identifier}', headers=HEADERS, json={"date": None})
    assert cleared.status_code == 200 and cleared.json['completed'] is True


def test_plan_completion_enforced_through_patch(memory):
    client, repos, _ = memory
    repos.tasks.rows.update(a=task('a'), b=task('b', ['a']))
    assert client.patch('/api/tasks/b', headers=HEADERS, json={"completed": True}).status_code == 409
    assert client.delete('/api/tasks/a', headers=HEADERS).status_code == 409
    assert client.patch('/api/tasks/a', headers=HEADERS, json={"completed": True}).status_code == 200
    assert client.patch('/api/tasks/b', headers=HEADERS, json={"completed": True}).status_code == 200
    assert client.patch('/api/tasks/a', headers=HEADERS, json={"completed": False}).status_code == 409


def test_undated_fixture_activity_and_project_deletion():
    from ui_smoke import demo_app
    client = demo_app().test_client()
    headers = {"Authorization": "Bearer demo-token"}
    project = client.post('/api/projects', headers=headers, json={"name": "Plan", "color": "#123456"}).json
    task = client.post('/api/tasks', headers=headers, json={"title": "Undated", "task_type": "project", "project_id": project['id'], "completed": True}).json
    activity = client.get('/api/activity?year=2026', headers=headers)
    assert activity.status_code == 200
    assert all(day['total'] == 0 for day in activity.json['days'])
    assert client.delete('/api/projects/' + project['id'], headers=headers).status_code == 200
    assert client.get('/api/tasks', headers=headers).json == []


def test_sdk_task_pagination_keeps_prerequisites_beyond_first_page():
    import httpx
    from postgrest import SyncPostgrestClient
    from studycalendar.tasks.adapters.supabase import SupabaseTaskRepository
    offsets = []
    def respond(request):
        offset = int(request.url.params['offset'])
        offsets.append(offset)
        return httpx.Response(200, json=[{"id": str(i), "title": str(i), "prerequisite_ids": ["0"] if i else []}
                                         for i in range(offset, min(offset + 500, 1001))])
    with httpx.Client(transport=httpx.MockTransport(respond), trust_env=False) as transport:
        database = SyncPostgrestClient('https://example.invalid/rest/v1', schema='Task', http_client=transport)
        rows = SupabaseTaskRepository(database).list()
    assert offsets == [0, 500, 1000]
    assert len(rows) == 1001 and rows[-1].prerequisite_ids == ['0']
