"""Per-application, per-process Supabase clients with safe LRU leases."""
import base64
import json
import math
import time
from collections import OrderedDict
from contextlib import contextmanager
from dataclasses import dataclass
from threading import RLock
from typing import Callable
import httpx
from supabase import Client, create_client
from supabase.client import ClientOptions
from ..config import Settings
from ..dependencies import Repositories
from ..categories.adapters.supabase import SupabaseCategoryRepository
from ..projects.adapters.supabase import SupabaseProjectRepository
from ..tasks.adapters.supabase import SupabaseTaskRepository
from ..routines.adapters.supabase import SupabaseRoutineRepository, SupabaseRoutineSummaries
from ..queries.adapters.supabase import SupabaseActivityRepository
from ..preferences.adapters.supabase import SupabasePreferencesRepository
from .domain import User


def token_expiry(token: str) -> float | None:
    # Only a cache lifetime hint. Identity is ALWAYS validated by Auth first.
    try:
        encoded = token.split(".")[1]
        encoded += "=" * (-len(encoded) % 4)
        expiry = float(json.loads(base64.urlsafe_b64decode(encoded).decode("utf-8"))["exp"])
        return expiry if math.isfinite(expiry) else None
    except (IndexError, KeyError, TypeError, ValueError, UnicodeDecodeError):
        return None


@dataclass
class ClientEntry:
    client: Client
    transport: httpx.Client
    references: int = 0
    retired: bool = False


class SupabaseRuntime:
    CACHE_LIMIT = 128

    def __init__(self, settings: Settings, client_factory: Callable = create_client,
                 transport_factory: Callable = httpx.Client, monotonic: Callable = time.monotonic,
                 wall_time: Callable = time.time):
        self.settings = settings
        self.client_factory = client_factory
        self.transport_factory = transport_factory
        self.monotonic = monotonic
        self.wall_time = wall_time
        self.lock = RLock()
        self.auth_cache = OrderedDict()
        self.data_clients = OrderedDict()
        self.auth_entry = None
        self.closed = False

    def new_entry(self, token: str | None = None) -> ClientEntry:
        if not self.settings.supabase_url or not self.settings.supabase_publishable_key:
            raise RuntimeError("SUPABASE_URL and SUPABASE_PUBLISHABLE_KEY are required")
        transport = self.transport_factory(timeout=120)
        try:
            options = ClientOptions(
                headers={"Authorization": f"Bearer {token}"} if token is not None else {},
                schema="Task", auto_refresh_token=False, persist_session=False, httpx_client=transport,
            )
            client = self.client_factory(self.settings.supabase_url, self.settings.supabase_publishable_key, options=options)
            if token is not None:
                # Eager initialization under the lock prevents lazy SDK client races.
                client.postgrest
            return ClientEntry(client, transport)
        except Exception:
            transport.close()
            raise

    @contextmanager
    def lease(self, token: str | None = None):
        with self.lock:
            if self.closed:
                raise RuntimeError("Supabase runtime is closed")
            if token is None:
                if self.auth_entry is None:
                    self.auth_entry = self.new_entry()
                entry = self.auth_entry
            else:
                entry = self.data_clients.get(token)
                if entry is None:
                    entry = self.new_entry(token)
                    self.data_clients[token] = entry
                self.data_clients.move_to_end(token)
                while len(self.data_clients) > self.CACHE_LIMIT:
                    _, old = self.data_clients.popitem(last=False)
                    self.retire(old)
            entry.references += 1
        try:
            yield entry.client
        finally:
            with self.lock:
                entry.references -= 1
                if entry.retired and entry.references == 0:
                    entry.transport.close()

    def retire(self, entry: ClientEntry) -> None:
        entry.retired = True
        if entry.references == 0:
            entry.transport.close()

    def authenticate(self, token: str) -> User | None:
        now = self.monotonic()
        expiry = token_expiry(token)
        with self.lock:
            cached = self.auth_cache.get(token)
            if cached and cached[0] > now and (expiry is None or expiry > self.wall_time()):
                self.auth_cache.move_to_end(token)
                return cached[1]
            self.auth_cache.pop(token, None)
        with self.lease() as client:
            response = client.auth.get_user(token)
        if not response or not response.user:
            return None
        user = User(str(response.user.id))
        ttl = self.settings.auth_cache_ttl_seconds
        if expiry is not None:
            ttl = min(ttl, max(0.0, expiry - self.wall_time()))
        if ttl > 0:
            with self.lock:
                if not self.closed:
                    self.auth_cache[token] = (now + ttl, user)
                    self.auth_cache.move_to_end(token)
                    while len(self.auth_cache) > self.CACHE_LIMIT:
                        self.auth_cache.popitem(last=False)
        return user

    @contextmanager
    def scope(self, token: str, user: User):
        with self.lease(token) as client:
            database = client.postgrest
            yield Repositories(
                SupabaseTaskRepository(database), SupabaseCategoryRepository(database),
                SupabaseProjectRepository(database), SupabaseRoutineRepository(database),
                SupabaseRoutineSummaries(database),
                SupabaseActivityRepository(database, user),
                SupabasePreferencesRepository(database, user),
            )

    def close(self) -> None:
        with self.lock:
            if self.closed:
                return
            self.closed = True
            self.auth_cache.clear()
            for entry in self.data_clients.values():
                self.retire(entry)
            self.data_clients.clear()
            if self.auth_entry is not None:
                self.retire(self.auth_entry)
                self.auth_entry = None
