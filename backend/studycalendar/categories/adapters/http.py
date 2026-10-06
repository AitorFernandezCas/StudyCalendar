from flask import Blueprint, jsonify, request
from ...shared.http import require_user, serialize, services

blueprint = Blueprint("categories", __name__)


@blueprint.get("/api/categories")
@require_user
def list_categories():
    result = services().categories.list()
    return jsonify(serialize(result))


@blueprint.post("/api/categories")
@require_user
def create_category():
    return jsonify(serialize(services().categories.create(request.get_json(silent=True)))), 201


@blueprint.patch("/api/categories/<category_id>")
@require_user
def update_category(category_id):
    return jsonify(serialize(services().categories.update(category_id, request.get_json(silent=True))))


@blueprint.delete("/api/categories/<category_id>")
@require_user
def delete_category(category_id):
    return jsonify({"deleted": services().categories.delete(category_id)})
