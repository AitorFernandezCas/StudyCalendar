from flask import Blueprint, g, jsonify, request
from ...shared.http import require_user
from ..application import SyncUnavailable

blueprint = Blueprint('sync', __name__)


def response(action):
    try:
        return jsonify(action())
    except SyncUnavailable:
        return jsonify({'error': 'El contrato de sincronización todavía no está disponible.', 'sync_unavailable': True}), 503


@blueprint.get('/api/sync/snapshot')
@require_user
def snapshot():
    return response(g.sync.snapshot)


@blueprint.post('/api/sync/apply')
@require_user
def apply():
    return response(lambda: g.sync.apply(request.get_json(silent=True)))
