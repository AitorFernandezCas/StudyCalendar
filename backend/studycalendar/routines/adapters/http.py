from flask import Blueprint, jsonify, request
from ...shared.http import require_user, serialize, services

blueprint = Blueprint("routines", __name__)


@blueprint.get("/api/routines")
@require_user
def list_routines():
    result = services().routines.list()
    return jsonify({**result.day.metadata(), "routines": serialize(result.routines)})


@blueprint.post("/api/routines")
@require_user
def create_routine():
    return jsonify(serialize(services().routines.create(request.get_json(silent=True)))), 201


@blueprint.patch("/api/routines/<routine_id>")
@require_user
def update_routine(routine_id):
    return jsonify(serialize(services().routines.update(routine_id, request.get_json(silent=True))))


@blueprint.delete("/api/routines/<routine_id>")
@require_user
def delete_routine(routine_id):
    return jsonify({"deleted": services().routines.delete(routine_id)})


@blueprint.patch("/api/routines/<routine_id>/completion")
@require_user
def complete_routine(routine_id):
    result = services().routines.complete(routine_id, request.get_json(silent=True))
    return jsonify({**result.day.metadata(), "routine": serialize(result.routine)})
