from datetime import date
import json
from types import SimpleNamespace

import httpx
import pytest
from postgrest import SyncPostgrestClient

from app import app
from app import create_app
from studycalendar.config import Settings
from studycalendar.dependencies import Dependencies
from studycalendar.shared.clock import SystemClock
from tests_support import DatabaseProvider, FixedClock, FixedAuth


@pytest.fixture
def authenticated_projects_client(monkeypatch):
    project = {'id': 'project-1', 'name': 'Oposición', 'color': '#ee7b6f', 'created_at': '2026-10-04', 'updated_at': '2026-10-04'}
    requests = []

    def respond(request):
        requests.append(request)
        if request.method == 'PATCH':
            return httpx.Response(200, json=[{**project, 'name': 'Proyecto actualizado'}])
        return httpx.Response(201 if request.method == 'POST' else 200, json=[project])

    http_client = httpx.Client(transport=httpx.MockTransport(respond), trust_env=False)
    database = SyncPostgrestClient('https://example.invalid/rest/v1', schema='Task', http_client=http_client)
    monkeypatch.setattr(database, 'schema', lambda name: database)
    application = create_app(Settings(), Dependencies(FixedAuth(), DatabaseProvider(database), FixedClock()))
    yield application.test_client(), requests
    http_client.close()


def test_authenticated_project_crud_uses_installed_sdk(authenticated_projects_client):
    client, requests = authenticated_projects_client
    headers = {'Authorization': 'Bearer test-token'}
    created = client.post('/api/projects', headers=headers, json={'name': ' Oposición ', 'color': '#ee7b6f'})
    assert created.status_code == 201
    assert created.json['name'] == 'Oposición'
    assert json.loads(requests[-1].read()) == {'name': 'Oposición', 'color': '#ee7b6f', 'user_id': 'user-1'}
    listed = client.get('/api/projects', headers=headers)
    assert listed.status_code == 200
    assert listed.json['projects'][0]['id'] == 'project-1'
    updated = client.patch('/api/projects/project-1', headers=headers, json={'name': 'Proyecto actualizado'})
    assert updated.status_code == 200
    assert updated.json['name'] == 'Proyecto actualizado'
    deleted = client.delete('/api/projects/project-1', headers=headers)
    assert deleted.status_code == 200
    assert deleted.json == {'deleted': 'project-1'}
    assert [request.method for request in requests] == ['POST', 'GET', 'PATCH', 'DELETE']
    assert all(request.headers.get('Accept-Profile', request.headers.get('Content-Profile')) == 'Task' for request in requests)


def test_local_vite_cors_and_untrusted_origin():
    client = app.test_client()
    for origin in ('http://localhost:5173', 'http://localhost:5174'):
        response = client.options('/api/projects', headers={'Origin': origin, 'Access-Control-Request-Method': 'POST'})
        assert response.headers['Access-Control-Allow-Origin'] == origin
    response = client.options('/api/projects', headers={'Origin': 'https://example.invalid', 'Access-Control-Request-Method': 'POST'})
    assert 'Access-Control-Allow-Origin' not in response.headers


def test_api_exceptions_return_json():
    application = create_app(Settings(), Dependencies(FixedAuth(), DatabaseProvider(SimpleNamespace()), FixedClock()))
    response = application.test_client().get('/api/projects', headers={'Authorization': 'Bearer test-token'})
    assert response.status_code == 500
    assert response.is_json
    assert 'error' in response.json
    assert 'AttributeError' not in response.json['error']


def test_health_endpoint():
    response = app.test_client().get('/api/health')
    assert response.status_code == 200
    assert response.json == {'status': 'ok'}


def test_tasks_require_authentication():
    response = app.test_client().get('/api/tasks')
    assert response.status_code == 401
    assert response.json['error'] == 'Authentication required'


def test_calendar_and_bootstrap_require_authentication():
    client = app.test_client()
    assert client.get('/api/calendar?from=2026-10-01&to=2026-10-07').status_code == 401
    assert client.get('/api/bootstrap?from=2026-10-01&to=2026-10-07').status_code == 401


def test_categories_require_authentication():
    response = app.test_client().get('/api/categories')
    assert response.status_code == 401
    assert response.json['error'] == 'Authentication required'


def test_category_mutations_require_authentication():
    client = app.test_client()
    assert client.post('/api/categories', json={'name': 'Física'}).status_code == 401
    assert client.patch('/api/categories/00000000-0000-0000-0000-000000000000', json={'name': 'Física'}).status_code == 401
    assert client.delete('/api/categories/00000000-0000-0000-0000-000000000000').status_code == 401


def test_projects_require_authentication():
    client = app.test_client()
    assert client.get('/api/projects').status_code == 401
    assert client.post('/api/projects', json={'name': 'Oposición'}).status_code == 401
    assert client.patch('/api/projects/00000000-0000-0000-0000-000000000000', json={'name': 'Oposición'}).status_code == 401
    assert client.delete('/api/projects/00000000-0000-0000-0000-000000000000').status_code == 401


def test_routines_require_authentication():
    client = app.test_client()
    assert client.get('/api/routines').status_code == 401
    assert client.get('/api/routines/occurrences?from=2026-10-01&to=2026-10-07').status_code == 405
    assert client.post('/api/routines', json={'title': 'Leer', 'start_time': '08:00', 'end_time': '08:30'}).status_code == 401
    assert client.patch('/api/routines/00000000-0000-0000-0000-000000000000', json={'active': False}).status_code == 401
    assert client.patch('/api/routines/00000000-0000-0000-0000-000000000000/completion', json={'date': '2026-10-05', 'completed': True}).status_code == 401
    assert client.delete('/api/routines/00000000-0000-0000-0000-000000000000').status_code == 401


@pytest.fixture
def authenticated_routines_client(monkeypatch):
    today = date(2026, 10, 5)
    row = {'id': 'routine-1', 'title': 'Leer', 'color': '#7c5cff', 'active': True,
           'created_at': '2026-10-01', 'updated_at': '2026-10-01', 'due_today': True,
           'completed_today': False, 'current_streak': 3, 'max_streak': 7}
    periods = [{'id': 'period-1', 'routine_id': 'routine-1', 'starts_on': '2026-10-01', 'ends_on': None}]
    requests = []

    def respond(request):
        requests.append(request)
        path = request.url.path
        body = json.loads(request.read()) if request.content else {}
        if path.endswith('/rpc/routine_summaries'):
            return httpx.Response(200, json=[] if request.url.params.get('id') == 'eq.other-user-routine' else [row])
        if path.endswith('/routine_completion'):
            row['completed_today'] = body['completed']
            row['current_streak'] = 4 if body['completed'] else 3
            return httpx.Response(201, json=[body])
        if path.endswith('/routine_period'):
            return httpx.Response(201 if request.method == 'POST' else 200, json=periods)
        if request.method == 'PATCH':
            row.update(body)
            row['due_today'] = row['active']
        if request.method == 'POST':
            row.update(body)
        return httpx.Response(201 if request.method == 'POST' else 200, json=[row])

    http_client = httpx.Client(transport=httpx.MockTransport(respond), trust_env=False)
    database = SyncPostgrestClient('https://example.invalid/rest/v1', schema='Task', http_client=http_client)
    monkeypatch.setattr(database, 'schema', lambda name: database)
    application = create_app(Settings(), Dependencies(FixedAuth(), DatabaseProvider(database), FixedClock(today)))
    yield application.test_client(), requests, row, periods
    http_client.close()


HEADERS = {'Authorization': 'Bearer test-token'}


def test_routine_summary_has_canonical_day_and_no_times(authenticated_routines_client):
    client, requests, row, _ = authenticated_routines_client
    response = client.get('/api/routines', headers=HEADERS)
    assert response.status_code == 200
    assert response.json == {'date': '2026-10-05', 'timezone': 'Europe/Madrid', 'next_day_at': '2026-10-06T00:00:00+02:00', 'routines': [row]}
    assert json.loads(requests[0].read()) == {'p_today': '2026-10-05'}
    assert requests[0].headers['Content-Profile'] == 'Task'


def test_complete_and_uncomplete_preserve_other_dates(authenticated_routines_client):
    client, requests, _, _ = authenticated_routines_client
    for completed, streak in [(True, 4), (False, 3)]:
        response = client.patch('/api/routines/routine-1/completion', headers=HEADERS, json={'date': '2026-10-05', 'completed': completed})
        assert response.status_code == 200
        assert response.json['routine']['completed_today'] is completed
        assert response.json['routine']['current_streak'] == streak
        writes = [request for request in requests if request.url.path.endswith('/routine_completion')]
        assert json.loads(writes[-1].read()) == {'routine_id': 'routine-1', 'user_id': 'user-1', 'occurrence_date': '2026-10-05', 'completed': completed}
        assert writes[-1].url.params['on_conflict'] == 'routine_id,occurrence_date'


@pytest.mark.parametrize('value', ['2026-10-04', '2026-10-06'])
def test_completion_rejects_stale_or_future_day(authenticated_routines_client, value):
    client, requests, _, _ = authenticated_routines_client
    response = client.patch('/api/routines/routine-1/completion', headers=HEADERS, json={'date': value, 'completed': True})
    assert response.status_code == 409
    assert response.json['date'] == '2026-10-05'
    assert requests == []


@pytest.mark.parametrize('payload', [None, [], {'date': 'invalid', 'completed': True}, {'date': None, 'completed': True}, {'date': '2026-10-05', 'completed': 'true'}, {'date': '2026-10-05', 'completed': True, 'user_id': 'other'}])
def test_invalid_completion_payload(authenticated_routines_client, payload):
    client, requests, _, _ = authenticated_routines_client
    response = client.patch('/api/routines/routine-1/completion', headers=HEADERS, json=payload)
    assert response.status_code == 400
    assert requests == []


@pytest.mark.parametrize('routine_id,due', [('routine-1', False), ('other-user-routine', True)])
def test_completion_requires_owned_routine_due_today(authenticated_routines_client, routine_id, due):
    client, requests, row, _ = authenticated_routines_client
    row['due_today'] = due
    response = client.patch(f'/api/routines/{routine_id}/completion', headers=HEADERS, json={'date': '2026-10-05', 'completed': True})
    assert response.status_code == 404
    assert not any(request.url.path.endswith('/routine_completion') for request in requests)


def test_create_routine_without_hours(authenticated_routines_client):
    client, requests, _, _ = authenticated_routines_client
    response = client.post('/api/routines', headers=HEADERS, json={'title': ' Meditar ', 'color': '#7c5cff', 'active': True, 'starts_on': '2026-10-06'})
    assert response.status_code == 201
    body = json.loads(requests[0].read())
    assert body == {'title': 'Meditar', 'color': '#7c5cff', 'active': True, 'user_id': 'user-1'}
    assert json.loads(requests[1].read())['starts_on'] == '2026-10-06'


def test_old_routine_hours_are_rejected(authenticated_routines_client):
    client, requests, _, _ = authenticated_routines_client
    response = client.post('/api/routines', headers=HEADERS, json={'title': 'Leer', 'start_time': '08:00'})
    assert response.status_code == 400
    assert requests == []


def test_ordinary_task_cannot_create_a_routine(authenticated_routines_client):
    client, requests, _, _ = authenticated_routines_client
    response = client.post('/api/tasks', headers=HEADERS, json={'title': 'Leer', 'task_type': 'routine'})
    assert response.status_code == 400
    assert requests == []


@pytest.mark.parametrize('starts_on,method', [('2026-10-01', 'PATCH'), ('2026-10-05', 'DELETE'), ('2026-10-07', 'DELETE')])
def test_deactivation_closes_or_removes_period_without_invalid_range(authenticated_routines_client, starts_on, method):
    client, requests, _, periods = authenticated_routines_client
    periods[0]['starts_on'] = starts_on
    response = client.patch('/api/routines/routine-1', headers=HEADERS, json={'active': False})
    assert response.status_code == 200
    writes = [request for request in requests if request.url.path.endswith('/routine_period') and request.method != 'GET']
    assert writes[0].method == method
    if method == 'PATCH':
        assert json.loads(writes[0].read()) == {'ends_on': '2026-10-04'}


def test_reactivation_starts_today_without_resetting_history(authenticated_routines_client):
    client, requests, row, periods = authenticated_routines_client
    row['active'] = False
    periods.clear()
    response = client.patch('/api/routines/routine-1', headers=HEADERS, json={'active': True})
    assert response.status_code == 200
    writes = [request for request in requests if request.url.path.endswith('/routine_period') and request.method == 'POST']
    assert json.loads(writes[0].read()) == {'routine_id': 'routine-1', 'user_id': 'user-1', 'starts_on': '2026-10-05', 'ends_on': None}
    assert not any(request.url.path.endswith('/routine_completion') for request in requests)


def test_calendar_and_bootstrap_do_not_load_routines(authenticated_projects_client):
    client, requests = authenticated_projects_client
    for endpoint in ['calendar', 'bootstrap']:
        requests.clear()
        response = client.get(f'/api/{endpoint}?from=2026-10-01&to=2026-10-07', headers=HEADERS)
        assert response.status_code == 200
        assert 'routine_occurrences' not in response.json
        assert 'routines' not in response.json
        task_request = next(request for request in requests if request.url.path.endswith('/tasks'))
        assert task_request.url.params['task_type'] == 'neq.routine'
        assert not any('routine' in request.url.path for request in requests)


def test_day_boundary_handles_madrid_dst():
    from datetime import datetime, timezone
    from zoneinfo import ZoneInfo

    class FrozenDateTime(datetime):
        @classmethod
        def now(cls, tz=None):
            return datetime(2026, 10, 25, 0, 0, tzinfo=tz)

    day = SystemClock('Europe/Madrid', now=FrozenDateTime.now).day()
    today, tz, next_day = day.date, day.timezone, day.next_day_at
    assert today == date(2026, 10, 25)
    assert tz == 'Europe/Madrid'
    assert next_day == '2026-10-26T00:00:00+01:00'
    start = datetime(2026, 10, 25, tzinfo=ZoneInfo(tz)).astimezone(timezone.utc)
    end = datetime.fromisoformat(next_day).astimezone(timezone.utc)
    assert (end - start).total_seconds() == 25 * 3600
