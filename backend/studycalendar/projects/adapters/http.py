from flask import Blueprint, jsonify, request
from ...shared.http import require_user, serialize, services

blueprint = Blueprint("projects", __name__)


@blueprint.get("/api/projects")
@require_user
def list_projects():
    result = services().projects.list()
    return jsonify({"projects": serialize(result)})


@blueprint.post("/api/projects")
@require_user
def create_project():
    return jsonify(serialize(services().projects.create(request.get_json(silent=True)))), 201


@blueprint.patch("/api/projects/<project_id>")
@require_user
def update_project(project_id):
    return jsonify(serialize(services().projects.update(project_id, request.get_json(silent=True))))


@blueprint.delete("/api/projects/<project_id>")
@require_user
def delete_project(project_id):
    return jsonify({"deleted": services().projects.delete(project_id)})


@blueprint.get("/api/project-features")
@require_user
def list_features():
    return jsonify({"features": serialize(services().features.list())})


@blueprint.post("/api/project-features")
@require_user
def create_feature():
    return jsonify(serialize(services().features.create(request.get_json(silent=True)))), 201


@blueprint.patch("/api/project-features/<identifier>")
@require_user
def update_feature(identifier):
    return jsonify(serialize(services().features.update(identifier, request.get_json(silent=True))))


@blueprint.delete("/api/project-features/<identifier>")
@require_user
def delete_feature(identifier):
    return jsonify({"deleted": services().features.delete(identifier)})


@blueprint.post("/api/project-feature-links")
@require_user
def create_feature_link():
    return jsonify(serialize(services().features.create_link(request.get_json(silent=True)))), 201


@blueprint.delete("/api/project-feature-links/<identifier>")
@require_user
def delete_feature_link(identifier):
    return jsonify({"deleted": services().features.delete_link(identifier)})
