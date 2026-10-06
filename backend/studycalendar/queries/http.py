from flask import Blueprint, jsonify, request
from ..shared.http import require_user, serialize, services

blueprint = Blueprint("queries", __name__)


@blueprint.get("/api/calendar")
@require_user
def calendar():
    rows = services().queries.calendar(request.args.get("from"), request.args.get("to"))
    return jsonify({"tasks": serialize(rows)})


@blueprint.get("/api/bootstrap")
@require_user
def bootstrap():
    result = services().queries.bootstrap(request.args.get("from"), request.args.get("to"))
    return jsonify(serialize(result))
