"""Inbound HTTP support. Flask request state never crosses into the core."""
from dataclasses import dataclass, fields, is_dataclass
from functools import wraps
from contextlib import ExitStack
from flask import current_app, g, jsonify, request
from ..categories.application import CategoryService
from ..projects.application import ProjectService
from ..tasks.application import TaskService
from ..routines.application import RoutineService
from ..queries.application import QueryService
from ..preferences.application import PreferencesService
from .domain import Model


def serialize(value):
    if isinstance(value, Model):
        result = dict(value.extra)
        for item in fields(value):
            if item.name not in {"extra", "present"} and (value.present is None or item.name in value.present):
                result[item.name] = serialize(getattr(value, item.name))
        return result
    if isinstance(value, list):
        return [serialize(item) for item in value]
    if is_dataclass(value):
        return {item.name: serialize(getattr(value, item.name)) for item in fields(value)}
    return value


@dataclass(frozen=True)
class Services:
    tasks: TaskService
    categories: CategoryService
    projects: ProjectService
    routines: RoutineService
    queries: QueryService
    preferences: PreferencesService


def services():
    return g.services


def require_user(view):
    @wraps(view)
    def wrapped(*args, **kwargs):
        authorization = request.headers.get("Authorization", "")
        if not authorization.startswith("Bearer ") or not authorization.removeprefix("Bearer ").strip():
            return jsonify({"error": "Authentication required"}), 401
        token = authorization.removeprefix("Bearer ").strip()
        dependencies = current_app.extensions["studycalendar.dependencies"]
        stack = ExitStack()
        try:
            user = dependencies.authenticator.authenticate(token)
            if not user:
                return jsonify({"error": "Invalid access token"}), 401
            repositories = stack.enter_context(dependencies.repositories.scope(token, user))
        except Exception:
            stack.close()
            return jsonify({"error": "Invalid access token"}), 401
        # Initialization must release the lease too if composition unexpectedly fails.
        with stack:
            clock = dependencies.clock
            g.services = Services(
                TaskService(repositories.tasks, repositories.categories, repositories.projects, user, clock),
                CategoryService(repositories.categories, user, repositories.tasks),
                ProjectService(repositories.projects, user),
                RoutineService(repositories.routines, repositories.summaries, user, clock, repositories.preferences),
                QueryService(repositories.tasks, repositories.categories, repositories.projects, clock, repositories.activity),
                PreferencesService(repositories.preferences, user, current_app.extensions["studycalendar.settings"].timezone),
            )
            return view(*args, **kwargs)
    return wrapped
