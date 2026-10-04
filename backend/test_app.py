from app import app


def test_health_endpoint():
    response = app.test_client().get('/api/health')
    assert response.status_code == 200
    assert response.json == {'status': 'ok'}


def test_tasks_require_authentication():
    response = app.test_client().get('/api/tasks')
    assert response.status_code == 401
    assert response.json['error'] == 'Authentication required'


def test_categories_require_authentication():
    response = app.test_client().get('/api/categories')
    assert response.status_code == 401
    assert response.json['error'] == 'Authentication required'


def test_category_mutations_require_authentication():
    client = app.test_client()
    assert client.post('/api/categories', json={'name': 'Física'}).status_code == 401
    assert client.patch('/api/categories/00000000-0000-0000-0000-000000000000', json={'name': 'Física'}).status_code == 401
    assert client.delete('/api/categories/00000000-0000-0000-0000-000000000000').status_code == 401
