from datetime import date, timedelta

from app import _routine_occurrences, app


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
