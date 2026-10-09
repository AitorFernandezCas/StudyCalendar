from flask import Blueprint, jsonify, request
from ...shared.http import require_user, services

blueprint = Blueprint("preferences", __name__)


@blueprint.get("/api/settings")
@require_user
def get_settings():
    return jsonify(services().preferences.get())


@blueprint.patch("/api/settings")
@require_user
def update_settings():
    return jsonify(services().preferences.update(request.get_json(silent=True)))
