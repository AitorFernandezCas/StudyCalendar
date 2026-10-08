"""Disposable localhost UI fixture. Never deploy this entrypoint.

All state is in memory. Run python backend/ui_smoke.py and a separate Vite
process configured for port 5001, as documented in README.md.
"""
from contextlib import contextmanager
from datetime import date, datetime, timedelta
from threading import RLock
from uuid import uuid4
import logging
import time

from flask import jsonify
from flask_cors import CORS
from app import create_app
from studycalendar.config import Settings
from studycalendar.dependencies import Dependencies, Repositories
from studycalendar.shared.clock import SystemClock
from studycalendar.shared.domain import User, DuplicateName
from studycalendar.tasks.domain import Task
from studycalendar.categories.domain import Category
from studycalendar.projects.domain import Project
from studycalendar.routines.domain import Routine, RoutineSummary, Period


class Store:
    def __init__(self):
        self.lock = RLock()
        self.rows = {name: {} for name in ("tasks", "categories", "projects", "routines", "periods", "completions")}


class Repository:
    def __init__(self, store, name, model):
        self.store, self.name, self.model = store, name, model
        self.rows = store.rows[name]

    def list(self, start=None, end=None):
        rows = list(self.rows.values())
        if self.name == "tasks":
            rows = [r for r in rows if r["task_type"] != "routine" and
                    (start is None or r["date"] >= start.isoformat()) and
                    (end is None or r["date"] <= end.isoformat())]
            rows.sort(key=lambda r: (r["date"], r["start_time"]))
        else:
            rows.sort(key=lambda r: r["name"])
        return [self.model.from_record(dict(r)) for r in rows]

    def belongs_to(self, identifier, user_id):
        return identifier in self.rows and self.rows[identifier]["user_id"] == user_id

    def check_name(self, payload, identifier=None):
        if self.name in ("categories", "projects") and "name" in payload:
            if any(key != identifier and row["name"].lower() == payload["name"].lower() for key, row in self.rows.items()):
                raise DuplicateName()

    def create(self, payload):
        self.check_name(payload)
        row = {"id": str(uuid4()), "color": "#7c5cff", **payload}
        if self.name == "tasks":
            row = {"category_id": None, "project_id": None, "task_type": "daily", "completed": False, "all_day": False, **row}
        if self.name in ("projects", "routines"):
            row.update(created_at=datetime.now().isoformat(), updated_at=datetime.now().isoformat())
        if self.name == "routines":
            row.setdefault("active", True)
        self.rows[row["id"]] = row
        return self.model.from_record(dict(row))

    def update(self, identifier, payload):
        if identifier not in self.rows:
            return None
        self.check_name(payload, identifier)
        self.rows[identifier].update(payload)
        return self.model.from_record(dict(self.rows[identifier]))

    def delete(self, identifier):
        if self.rows.pop(identifier, None) is None:
            return False
        association = {"categories": "category_id", "projects": "project_id"}.get(self.name)
        if association:
            for task in self.store.rows["tasks"].values():
                if task.get(association) == identifier:
                    task[association] = None
        if self.name == "routines":
            self.store.rows["periods"] = {key: row for key, row in self.store.rows["periods"].items() if row["routine_id"] != identifier}
            self.store.rows["completions"] = {key: value for key, value in self.store.rows["completions"].items() if key[0] != identifier}
        return True

    def set_category_color(self, identifier, color):
        for task in self.store.rows["tasks"].values():
            if task["category_id"] == identifier:
                task["color"] = color


class Routines(Repository):
    def get(self, identifier):
        row = self.rows.get(identifier)
        return Routine.from_record(dict(row)) if row else None

    def open_periods(self, identifier):
        return [Period.from_record(dict(row)) for row in self.store.rows["periods"].values()
                if row["routine_id"] == identifier and row["ends_on"] is None]

    def add_period(self, identifier, user_id, starts_on):
        key = str(uuid4())
        self.store.rows["periods"][key] = {"id": key, "routine_id": identifier, "user_id": user_id, "starts_on": starts_on, "ends_on": None}

    def remove_period(self, identifier):
        self.store.rows["periods"].pop(identifier)

    def close_period(self, identifier, ends_on):
        self.store.rows["periods"][identifier]["ends_on"] = ends_on

    def complete(self, identifier, user_id, today, completed):
        self.store.rows["completions"][identifier, today.isoformat()] = completed

    def summaries(self, today, routine_id=None):
        results = []
        for identifier, row in self.rows.items():
            if routine_id and identifier != routine_id:
                continue
            periods = self.store.rows["periods"].values()
            due = row["active"] and any(p["routine_id"] == identifier and p["starts_on"] <= today.isoformat() and
                                      (p["ends_on"] is None or p["ends_on"] >= today.isoformat()) for p in periods)
            dates = sorted(date.fromisoformat(day) for (key, day), completed in self.store.rows["completions"].items()
                           if key == identifier and completed and day <= today.isoformat())
            current = maximum = run = 0
            previous = None
            for day in dates:
                run = run + 1 if previous == day - timedelta(days=1) else 1
                maximum = max(maximum, run)
                current = run if day in (today, today - timedelta(days=1)) else 0
                previous = day
            results.append(RoutineSummary.from_record({
                **row, "due_today": due, "completed_today": self.store.rows["completions"].get((identifier, today.isoformat()), False),
                "current_streak": current if due else 0, "max_streak": maximum,
            }))
        return sorted(results, key=lambda r: (r.title.lower(), r.id))


class DemoAuth:
    def authenticate(self, token):
        return User("demo") if token == "demo-token" else None


class Activity:
    def __init__(self, store):
        self.store = store

    def read(self, start, end):
        from studycalendar.queries.domain import ActivityRecords, DatedTask, RoutinePeriod, RoutineCompletion
        return ActivityRecords(
            [DatedTask(row["date"], row["completed"]) for row in self.store.rows["tasks"].values()
             if row["task_type"] != "routine" and start.isoformat() <= row["date"] <= end.isoformat()],
            [RoutinePeriod(row["routine_id"], row["starts_on"], row["ends_on"])
             for row in self.store.rows["periods"].values()],
            [RoutineCompletion(identifier, day) for (identifier, day), completed in self.store.rows["completions"].items()
             if completed],
        )


class Provider:
    def __init__(self):
        self.store = Store()

    @contextmanager
    def scope(self, token, user):
        with self.store.lock:
            routines = Routines(self.store, "routines", Routine)
            yield Repositories(Repository(self.store, "tasks", Task), Repository(self.store, "categories", Category),
                               Repository(self.store, "projects", Project), routines, routines, Activity(self.store))


def demo_app():
    app = create_app(Settings(), Dependencies(DemoAuth(), Provider(), SystemClock("Europe/Madrid")))
    CORS(app, resources={r"/auth/*": {"origins": ["http://localhost:5180", "http://127.0.0.1:5180"]}})
    user = {"id": "demo", "aud": "authenticated", "role": "authenticated", "email": "demo@example.invalid",
            "created_at": "2026-10-06T00:00:00Z", "app_metadata": {}, "user_metadata": {}}

    @app.post("/auth/v1/token")
    def token():
        return jsonify({"access_token": "demo-token", "token_type": "bearer", "expires_in": 3600,
                        "expires_at": int(time.time()) + 3600, "refresh_token": "demo-refresh", "user": user})

    @app.get("/auth/v1/user")
    def get_user():
        return jsonify(user)

    @app.post("/auth/v1/logout")
    def logout():
        return "", 204

    return app


if __name__ == "__main__":
    logging.getLogger("werkzeug").setLevel(logging.ERROR)
    demo_app().run(host="127.0.0.1", port=5001, use_reloader=False)
