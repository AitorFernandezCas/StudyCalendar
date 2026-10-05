from flask import Blueprint, jsonify, request
from ...shared.http import require_user, serialize, services

blueprint = Blueprint("tasks", __name__)


@blueprint.get("/api/tasks")
@require_user
def list_tasks():
    result = services().tasks.list(request.args.get("from"), request.args.get("to"))
    return jsonify(serialize(result))


@blueprint.post("/api/tasks")
@require_user
def create_task():
    return jsonify(serialize(services().tasks.create(request.get_json(silent=True)))), 201


@blueprint.patch("/api/tasks/<task_id>")
@require_user
def update_task(task_id):
    return jsonify(serialize(services().tasks.update(task_id, request.get_json(silent=True))))


@blueprint.delete("/api/tasks/<task_id>")
@require_user
def delete_task(task_id):
    return jsonify({"deleted": services().tasks.delete(task_id)})
