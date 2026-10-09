import atexit
import time
from urllib.parse import urlparse
from flask import Flask, g, jsonify, request
from flask_cors import CORS
from werkzeug.exceptions import HTTPException
from .config import Settings
from .dependencies import Dependencies
from .shared.clock import SystemClock
from .shared.domain import BusinessError
from .shared.supabase_runtime import SupabaseRuntime


def create_app(settings: Settings | None = None, dependencies: Dependencies | None = None) -> Flask:
    settings = settings if settings is not None else Settings.from_env()
    app = Flask(__name__)
    if dependencies is None:
        runtime = SupabaseRuntime(settings)
        dependencies = Dependencies(runtime, runtime, SystemClock(settings.timezone))
        app.extensions["studycalendar.runtime"] = runtime
        atexit.register(runtime.close)
    app.extensions["studycalendar.dependencies"] = dependencies
    app.extensions["studycalendar.settings"] = settings

    origin = settings.frontend_origin
    origins = [origin]
    if urlparse(origin).hostname in {"localhost", "127.0.0.1"}:
        origins.extend(f"http://{host}:{port}" for host in ("localhost", "127.0.0.1") for port in range(5173, 5184))
    CORS(app, resources={r"/api/*": {"origins": origins}},
         expose_headers=["Server-Timing", "X-Response-Time-ms"], supports_credentials=False)

    @app.errorhandler(Exception)
    def handle_api_error(error):
        if not request.path.startswith("/api/"):
            if isinstance(error, HTTPException):
                return error
            raise error
        if isinstance(error, BusinessError):
            statuses = {"validation": 400, "not_found": 404, "conflict": 409}
            return jsonify({"error": str(error), **error.details}), statuses[error.kind]
        if isinstance(error, HTTPException):
            return jsonify({"error": error.description}), error.code
        app.logger.exception("API request failed")
        return jsonify({"error": "No se pudo completar la operación. Inténtalo de nuevo."}), 500

    @app.before_request
    def start_request_timer():
        g.request_started_at = time.perf_counter()

    @app.after_request
    def add_response_timing(response):
        started_at = getattr(g, "request_started_at", None)
        if started_at is not None and request.path.startswith("/api/"):
            duration_ms = (time.perf_counter() - started_at) * 1000
            response.headers["Server-Timing"] = f"app;dur={duration_ms:.1f}"
            response.headers["X-Response-Time-ms"] = f"{duration_ms:.1f}"
        return response

    @app.get("/api/health")
    def health():
        return jsonify({"status": "ok"})

    from .categories.adapters.http import blueprint as categories
    from .projects.adapters.http import blueprint as projects
    from .tasks.adapters.http import blueprint as tasks
    from .routines.adapters.http import blueprint as routines
    from .queries.http import blueprint as queries
    from .preferences.adapters.http import blueprint as preferences
    for blueprint in (categories, projects, tasks, routines, queries, preferences):
        app.register_blueprint(blueprint)
    return app
