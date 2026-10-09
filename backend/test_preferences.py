from contextlib import contextmanager
from datetime import date, datetime
import json

import httpx
import pytest
from postgrest import SyncPostgrestClient
from postgrest.exceptions import APIError

from app import create_app
from studycalendar.config import Settings
from studycalendar.dependencies import Dependencies, Repositories
from studycalendar.preferences.domain import Preferences
from studycalendar.preferences.adapters.supabase import SupabasePreferencesRepository
from studycalendar.routines.application import RoutineService
from studycalendar.routines.domain import Period
from studycalendar.shared.clock import SystemClock
from studycalendar.shared.domain import BusinessError, User
from test_hexagonal import MemoryRoutines


class MemoryPreferences:
    def __init__(self, values=Preferences()):
        self.values = values

    def get(self):
        return self.values

    def save(self, changes):
        self.values = self.values.patch(changes)
        return self.values


@pytest.mark.parametrize("changes", [None, {}, [], {"timezone": "UTC"},
    {"routine_reset_time": None}, {"routine_reset_time": "24:00"},
    {"routine_reset_time": "4:30"}, {"calendar_start_time": "24:00"},
    {"calendar_end_time": "24:01"}, {"calendar_end_time": "00:00"},
    {"week_start": "friday"}, {"week_start": []},
    {"calendar_start_time": "22:00", "calendar_end_time": "08:00"}])
def test_invalid_preferences(changes):
    with pytest.raises(BusinessError):
        Preferences().patch(changes)


def test_preferences_http_defaults_sparse_patch_and_user_isolation():
    rows = {}
    class Auth:
        def authenticate(self, token):
            return User(token)
    class Provider:
        @contextmanager
        def scope(self, token, user):
            repository = rows.setdefault(user.id, MemoryPreferences())
            yield Repositories(None, None, None, None, None, preferences=repository)
    clock = SystemClock("Europe/Madrid")
    client = create_app(Settings(), Dependencies(Auth(), Provider(), clock)).test_client()
    own, other = {"Authorization": "Bearer own"}, {"Authorization": "Bearer other"}
    assert client.get('/api/settings').status_code == 401
    assert client.patch('/api/settings', json={"week_start": "sunday"}).status_code == 401
    assert client.get('/api/settings', headers=own).json == {**Preferences().payload(), "timezone": "Europe/Madrid"}
    changes = {"routine_reset_time": "04:30", "calendar_start_time": "07:30", "calendar_end_time": "22:15", "week_start": "sunday"}
    assert client.patch('/api/settings', headers=own, json=changes).status_code == 200
    result = client.patch('/api/settings', headers=own, json={"calendar_end_time": "23:15"})
    assert result.json == {**changes, "calendar_end_time": "23:15", "timezone": "Europe/Madrid"}
    assert client.get('/api/settings', headers=other).json["week_start"] == "monday"
    failure = client.patch('/api/settings', headers=own, json={"calendar_end_time": "06:00"})
    assert failure.status_code == 400
    assert "calendar_end_time" in failure.json["fields"]
    assert client.get('/api/settings', headers=own).json["calendar_end_time"] == "23:15"


def test_installed_adapter_uses_scoped_queries_and_preserves_omitted_fields():
    records, requests = {}, []
    def respond(request):
        requests.append(request)
        if request.method == 'POST':
            body = json.loads(request.read())
            records.setdefault(body['user_id'], {"user_id": body['user_id'], **Preferences().payload()})
            assert 'resolution=ignore-duplicates' in request.headers['prefer']
            return httpx.Response(201, json=[])
        user = request.url.params['user_id'].removeprefix('eq.')
        if request.method == 'PATCH':
            records[user].update(json.loads(request.read()))
        row = records.get(user)
        if not row:
            return httpx.Response(200, json=[])
        selected = request.url.params.get('select')
        return httpx.Response(200, json=[{k: v for k, v in row.items() if not selected or k in selected.split(',')}])
    with httpx.Client(transport=httpx.MockTransport(respond)) as transport:
        database = SyncPostgrestClient('https://example.invalid/rest/v1', schema='Task', http_client=transport)
        own = SupabasePreferencesRepository(database, User('own'))
        other = SupabasePreferencesRepository(database, User('other'))
        assert own.get() == Preferences()
        own.save({"routine_reset_time": "04:30", "week_start": "sunday"})
        assert own.save({"calendar_start_time": "07:30"}) == Preferences(routine_reset_time="04:30", week_start="sunday", calendar_start_time="07:30")
        assert other.get() == Preferences()
        assert all(request.headers.get('accept-profile', request.headers.get('content-profile')) == 'Task' for request in requests)


@pytest.mark.parametrize("instant,expected", [
    ("2026-10-09T04:29:59", "2026-10-08"),
    ("2026-10-09T04:30:00", "2026-10-09"),
    ("2026-10-09T04:30:01", "2026-10-09"),
    ("2027-01-01T02:00:00", "2026-12-31"),
])
def test_custom_day_boundaries(instant, expected):
    clock = SystemClock('Europe/Madrid', now=lambda tz: datetime.fromisoformat(instant).replace(tzinfo=tz))
    day = clock.day('04:30')
    assert day.date.isoformat() == expected
    assert day.day_started_at.startswith(expected + 'T04:30:00')
    assert clock.day().date == datetime.fromisoformat(instant).date()


@pytest.mark.parametrize("instant,fold,expected,next_boundary", [
    ("2026-03-29T01:59:00", 0, "2026-03-28", "2026-03-29T03:00:00+02:00"),
    ("2026-03-29T03:00:00", 0, "2026-03-29", "2026-03-30T02:30:00+02:00"),
    ("2026-10-25T02:29:00", 0, "2026-10-24", "2026-10-25T02:30:00+02:00"),
    ("2026-10-25T02:30:00", 0, "2026-10-25", "2026-10-26T02:30:00+01:00"),
    ("2026-10-25T02:15:00", 1, "2026-10-25", "2026-10-26T02:30:00+01:00"),
])
def test_missing_and_repeated_reset_times(instant, fold, expected, next_boundary):
    clock = SystemClock('Europe/Madrid', now=lambda tz: datetime.fromisoformat(instant).replace(tzinfo=tz, fold=fold))
    day = clock.day('02:30')
    assert day.date.isoformat() == expected
    assert day.next_day_at == next_boundary


def test_routines_use_custom_day_for_completions_creation_pauses_and_immediate_changes():
    calls = []
    def now(tz):
        calls.append(True)
        return datetime(2026, 10, 5, 2, tzinfo=tz)
    clock = SystemClock('Europe/Madrid', now=now)
    repository = MemoryRoutines()
    preferences = MemoryPreferences(Preferences(routine_reset_time='04:30'))
    service = RoutineService(repository, repository, User('own'), clock, preferences)
    assert service.list().day.date == date(2026, 10, 4)
    calls.clear()
    result = service.complete('r', {"date": "2026-10-04", "completed": True})
    assert result.day.date == date(2026, 10, 4)
    assert len(calls) == 1
    with pytest.raises(BusinessError) as failure:
        service.complete('r', {"date": "2026-10-05", "completed": True})
    assert failure.value.kind == 'conflict'
    assert repository.completions[date(2026, 10, 4)]
    service.create({"title": "Leer"})
    assert repository.writes[-1] == ('period', 'r', 'own', '2026-10-04')
    preferences.save({"routine_reset_time": "00:00"})
    assert service.list().day.date == date(2026, 10, 5)
    assert repository.completions[date(2026, 10, 4)]
    service.complete('r', {"date": "2026-10-05", "completed": False})
    repository.due = False
    with pytest.raises(BusinessError) as unavailable:
        service.complete('r', {"date": "2026-10-05", "completed": True})
    assert unavailable.value.kind == 'not_found'


def test_pause_and_reactivation_use_effective_day_without_touching_history():
    clock = SystemClock('Europe/Madrid', now=lambda tz: datetime(2026, 10, 5, 2, tzinfo=tz))
    preferences = MemoryPreferences(Preferences(routine_reset_time='04:30'))
    repository = MemoryRoutines(periods=[Period(id='period', routine_id='r', starts_on='2026-10-01')])
    service = RoutineService(repository, repository, User('own'), clock, preferences)
    service.update('r', {"active": False})
    assert repository.writes[-1] == ('close', 'period', '2026-10-03')
    repository.periods.clear()
    service.update('r', {"active": True})
    assert repository.writes[-1] == ('period', 'r', 'own', '2026-10-04')
    assert repository.completions == {date(2026, 10, 4): True}


def test_adapter_read_failure_is_not_replaced_by_defaults():
    with httpx.Client(transport=httpx.MockTransport(lambda request: httpx.Response(500, json={"message": "offline", "code": "XX000", "hint": None, "details": None}))) as transport:
        database = SyncPostgrestClient('https://example.invalid/rest/v1', schema='Task', http_client=transport)
        with pytest.raises(APIError):
            SupabasePreferencesRepository(database, User('own')).get()


def test_concurrent_range_conflict_is_reported_as_validation():
    def respond(request):
        if request.method == 'POST':
            return httpx.Response(201, json=[])
        return httpx.Response(400, json={"message": "range conflict", "code": "23514", "hint": None, "details": None})
    with httpx.Client(transport=httpx.MockTransport(respond)) as transport:
        database = SyncPostgrestClient('https://example.invalid/rest/v1', schema='Task', http_client=transport)
        with pytest.raises(BusinessError) as failure:
            SupabasePreferencesRepository(database, User('own')).save({"calendar_start_time": "08:00"})
        assert failure.value.kind == 'validation'
        assert 'calendar_end_time' in failure.value.details['fields']
