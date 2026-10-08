from datetime import date

import httpx
import pytest
from postgrest import SyncPostgrestClient

from app import create_app
from studycalendar.config import Settings
from studycalendar.dependencies import Dependencies
from studycalendar.queries.application import QueryService
from studycalendar.queries.domain import ActivityRecords, DatedTask, RoutinePeriod, RoutineCompletion
from studycalendar.shared.domain import BusinessError
from tests_support import FixedAuth, FixedClock, DatabaseProvider


class History:
    def __init__(self, records):
        self.records = records
        self.ranges = []

    def read(self, first, last):
        self.ranges.append((first, last))
        return self.records


def activity(records, today=date(2026, 10, 8), year=None):
    clock = FixedClock(today)
    history = History(records)
    service = QueryService(None, None, None, clock, history)
    snapshot = service.activity(year)
    assert clock.calls == 1
    assert history.ranges == [(date(snapshot.year, 1, 1), date(snapshot.year, 12, 31))]
    return {day.date: day for day in snapshot.days}


def test_daily_counts_include_tasks_and_historical_routines_without_double_counting():
    days = activity(ActivityRecords(
        [DatedTask('2026-10-06', True), DatedTask('2026-10-08', False), DatedTask('2026-10-09', True)],
        [RoutinePeriod('paused', '2025-12-30', '2026-01-02'),
         RoutinePeriod('r', '2026-10-06', '2026-10-06'),
         RoutinePeriod('r', '2026-10-08', None), RoutinePeriod('r', '2026-10-08', None)],
        [RoutineCompletion('r', '2026-10-06'), RoutineCompletion('r', '2026-10-08'),
         RoutineCompletion('r', '2026-10-08'), RoutineCompletion('r', '2026-10-07')],
    ))
    assert len(days) == 365
    assert days['2026-01-01'].status == 'pending'
    assert days['2026-01-03'].status == 'empty'
    assert (days['2026-10-06'].total, days['2026-10-06'].completed, days['2026-10-06'].status) == (2, 2, 'complete')
    assert days['2026-10-07'].status == 'empty'  # Completion outside an active period does not create an obligation.
    assert (days['2026-10-08'].total, days['2026-10-08'].completed, days['2026-10-08'].status) == (2, 1, 'pending')
    assert days['2026-10-09'].status == 'future'  # Future remains white even if pre-completed.
    assert days['2026-12-31'].status == 'future'


def test_leap_year_and_refresh_after_completing_or_unmarking():
    records = ActivityRecords([DatedTask('2024-02-29', False)], [], [])
    days = activity(records, date(2024, 2, 29), '2024')
    assert len(days) == 366
    assert days['2024-02-29'].status == 'pending'
    records.tasks[0] = DatedTask('2024-02-29', True)
    assert activity(records, date(2024, 2, 29))['2024-02-29'].status == 'complete'
    records.tasks[0] = DatedTask('2024-02-29', False)
    assert activity(records, date(2024, 3, 1))['2024-02-29'].status == 'pending'
    assert activity(records, date(2024, 3, 1))['2024-03-01'].status == 'empty'


@pytest.mark.parametrize('year', ['', 'no-year', '2026.5', '1899', '9999'])
def test_invalid_year_does_not_read_database(year):
    history = History(ActivityRecords([], [], []))
    with pytest.raises(BusinessError):
        QueryService(None, None, None, FixedClock(), history).activity(year)
    assert history.ranges == []


def test_authenticated_http_reads_all_pages_and_scopes_every_query():
    requests = []
    rows = {
        'tasks': [{'id': str(i), 'date': '2026-10-05', 'completed': i < 1000} for i in range(1201)],
        'routine_period': [{'id': str(i), 'routine_id': f'r{i}', 'starts_on': '2026-10-05', 'ends_on': '2026-10-05'} for i in range(1001)],
        'routine_completion': [{'id': str(i), 'routine_id': f'r{i}', 'occurrence_date': '2026-10-05'} for i in range(1001)],
    }

    def respond(request):
        requests.append(request)
        assert request.headers['accept-profile'] == 'Task'
        assert request.url.params['user_id'] == 'eq.user-1'
        assert request.url.params['order'] == 'id.asc'
        start = int(request.url.params.get('offset', '0'))
        limit = int(request.url.params['limit'])
        return httpx.Response(200, json=rows[request.url.path.split('/')[-1]][start:start + limit])

    with httpx.Client(transport=httpx.MockTransport(respond), trust_env=False) as client:
        database = SyncPostgrestClient('https://example.invalid/rest/v1', schema='Task', http_client=client)
        application = create_app(Settings(), Dependencies(FixedAuth(), DatabaseProvider(database), FixedClock()))
        http = application.test_client()
        assert http.get('/api/activity').status_code == 401
        assert requests == []
        response = http.get('/api/activity?year=2026', headers={'Authorization': 'Bearer test-token'})
        assert response.status_code == 200
        snapshot = response.json
        assert snapshot['date'] == '2026-10-05'
        assert snapshot['timezone'] == 'Europe/Madrid'
        assert snapshot['next_day_at'] == '2026-10-06T00:00:00+02:00'
        day = next(day for day in snapshot['days'] if day['date'] == snapshot['date'])
        assert (day['total'], day['completed'], day['status']) == (2202, 2001, 'pending')
        assert len(requests) == 9
        task_query = next(r for r in requests if r.url.path.endswith('/tasks')).url.params
        assert task_query['task_type'] == 'neq.routine'
        assert task_query.get_list('date') == ['gte.2026-01-01', 'lte.2026-12-31']
        period_query = next(r for r in requests if r.url.path.endswith('/routine_period')).url.params
        assert period_query['or'] == '(ends_on.is.null,ends_on.gte.2026-01-01)'
        completion_query = next(r for r in requests if r.url.path.endswith('/routine_completion')).url.params
        assert completion_query['completed'] == 'eq.true'
        assert http.get('/api/activity?year=invalid', headers={'Authorization': 'Bearer test-token'}).status_code == 400
        assert len(requests) == 9
