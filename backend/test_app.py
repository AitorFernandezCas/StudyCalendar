from datetime import date, timedelta
import json
from types import SimpleNamespace

import httpx
import pytest
from postgrest import SyncPostgrestClient

from app import _routine_occurrences, app
import app as app_module


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
    monkeypatch.setattr(app_module, '_authenticated_user', lambda token: SimpleNamespace(id='user-1'))
    monkeypatch.setattr(app_module, '_client_for_token', lambda token: database)
    yield app.test_client(), requests
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


def test_api_exceptions_return_json(monkeypatch):
    monkeypatch.setattr(app_module, '_authenticated_user', lambda token: SimpleNamespace(id='user-1'))
    monkeypatch.setattr(app_module, '_client_for_token', lambda token: SimpleNamespace())
    response = app.test_client().get('/api/projects', headers={'Authorization': 'Bearer test-token'})
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
    assert client.get('/api/routines/occurrences?from=2026-10-01&to=2026-10-07').status_code == 401
    assert client.post('/api/routines', json={'title': 'Leer', 'start_time': '08:00', 'end_time': '08:30'}).status_code == 401
    assert client.patch('/api/routines/00000000-0000-0000-0000-000000000000', json={'active': False}).status_code == 401
    assert client.patch('/api/routines/00000000-0000-0000-0000-000000000000/occurrences/2026-10-01', json={'completed': True}).status_code == 401
    assert client.delete('/api/routines/00000000-0000-0000-0000-000000000000').status_code == 401


def routine(routine_id='routine-1', active=True):
    return {
        'id': routine_id,
        'title': 'Rutina de prueba',
        'start_time': '08:00:00',
        'end_time': '08:30:00',
        'color': '#7c5cff',
        'active': active,
    }


def test_inactive_routine_only_generates_historical_occurrences():
    today = date.today()
    result = _routine_occurrences(
        [routine(active=False)],
        [{'routine_id': 'routine-1', 'starts_on': (today - timedelta(days=3)).isoformat(), 'ends_on': None}],
        [],
        today - timedelta(days=3),
        today + timedelta(days=3),
    )

    assert [item['date'] for item in result] == [
        (today - timedelta(days=3)).isoformat(),
        (today - timedelta(days=2)).isoformat(),
        (today - timedelta(days=1)).isoformat(),
    ]


def test_active_routine_generates_today_and_future():
    today = date.today()
    result = _routine_occurrences(
        [routine()],
        [{'routine_id': 'routine-1', 'starts_on': today.isoformat(), 'ends_on': None}],
        [],
        today,
        today + timedelta(days=2),
    )

    assert [item['date'] for item in result] == [
        today.isoformat(),
        (today + timedelta(days=1)).isoformat(),
        (today + timedelta(days=2)).isoformat(),
    ]


def test_reactivated_routine_preserves_history_and_continues_today():
    today = date.today()
    result = _routine_occurrences(
        [routine()],
        [
            {'routine_id': 'routine-1', 'starts_on': (today - timedelta(days=3)).isoformat(), 'ends_on': (today - timedelta(days=1)).isoformat()},
            {'routine_id': 'routine-1', 'starts_on': today.isoformat(), 'ends_on': None},
        ],
        [],
        today - timedelta(days=3),
        today + timedelta(days=1),
    )

    assert [item['date'] for item in result] == [
        (today - timedelta(days=3)).isoformat(),
        (today - timedelta(days=2)).isoformat(),
        (today - timedelta(days=1)).isoformat(),
        today.isoformat(),
        (today + timedelta(days=1)).isoformat(),
    ]
