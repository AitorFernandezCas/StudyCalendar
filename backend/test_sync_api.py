from contextlib import contextmanager
from dataclasses import replace
import json
import httpx
from postgrest import SyncPostgrestClient
from studycalendar.config import Settings
from studycalendar.dependencies import Dependencies
from studycalendar.factory import create_app
from studycalendar.sync.adapters.supabase import SupabaseSyncGateway
from tests_support import DatabaseProvider, FixedAuth, FixedClock


def test_sync_http_uses_authenticated_scope_and_installed_sdk():
    requests = []
    def respond(request):
        requests.append(request)
        if request.url.path.endswith('sync_snapshot'):
            return httpx.Response(200, json={'protocol': 1, 'owner': 'user-1', 'data': {}})
        return httpx.Response(200, json={'status': 'conflict', 'reason': 'deleted', 'remote': None})
    with httpx.Client(transport=httpx.MockTransport(respond), trust_env=False) as transport:
        db = SyncPostgrestClient('https://example.invalid/rest/v1', schema='Task', http_client=transport)
        class Provider:
            @contextmanager
            def scope(self, token, user):
                assert token == 'test-token' and user.id == 'user-1'
                with DatabaseProvider(db).scope(token, user) as repositories:
                    yield replace(repositories, sync=SupabaseSyncGateway(db))
        client = create_app(Settings(), Dependencies(FixedAuth(), Provider(), FixedClock())).test_client()
        assert client.get('/api/sync/snapshot').status_code == 401
        headers = {'Authorization': 'Bearer test-token'}
        assert client.get('/api/sync/snapshot', headers=headers).json['protocol'] == 1
        operation = {'protocol': 1, 'operation_id': '11111111-1111-4111-8111-111111111111', 'id': '22222222-2222-4222-8222-222222222222', 'entity': 'tasks', 'action': 'delete', 'base': {'revision': 1, 'state': {}}, 'changes': {}, 'context': {}}
        response = client.post('/api/sync/apply', headers=headers, json=operation)
        assert response.status_code == 200 and response.json['reason'] == 'deleted'
        assert json.loads(requests[-1].read()) == {'p_operation': operation}
        assert requests[-1].headers['content-profile'] == 'Task'
        assert client.post('/api/sync/apply', headers=headers, json={**operation, 'user_id': 'other'}).status_code == 400
        assert client.post('/api/sync/apply', headers=headers, json={**operation, 'protocol': 0}).status_code == 409


def test_missing_sync_contract_is_a_distinct_unavailable_response():
    def respond(request):
        return httpx.Response(404, json={'code': 'PGRST202', 'message': 'function not found', 'details': '', 'hint': ''})
    with httpx.Client(transport=httpx.MockTransport(respond), trust_env=False) as transport:
        db = SyncPostgrestClient('https://example.invalid/rest/v1', schema='Task', http_client=transport)
        class Provider:
            @contextmanager
            def scope(self, token, user):
                with DatabaseProvider(db).scope(token, user) as repositories:
                    yield replace(repositories, sync=SupabaseSyncGateway(db))
        client = create_app(Settings(), Dependencies(FixedAuth(), Provider(), FixedClock())).test_client()
        response = client.get('/api/sync/snapshot', headers={'Authorization': 'Bearer test-token'})
        assert response.status_code == 503 and response.json['sync_unavailable'] is True
