from dataclasses import dataclass
import os
from dotenv import load_dotenv


@dataclass(frozen=True)
class Settings:
    supabase_url: str = ""
    supabase_publishable_key: str = ""
    frontend_origin: str = "http://localhost:5173"
    auth_cache_ttl_seconds: float = 30.0
    timezone: str = "Europe/Madrid"

    @classmethod
    def from_env(cls) -> "Settings":
        load_dotenv()
        try:
            ttl = max(0.0, float(os.environ.get("AUTH_CACHE_TTL_SECONDS", "30")))
        except ValueError:
            ttl = 30.0
        return cls(
            supabase_url=os.environ.get("SUPABASE_URL", "").strip(),
            supabase_publishable_key=os.environ.get("SUPABASE_PUBLISHABLE_KEY", "").strip(),
            frontend_origin=os.environ.get("FRONTEND_ORIGIN", "http://localhost:5173").strip(),
            auth_cache_ttl_seconds=ttl,
            timezone=os.environ.get("APP_TIMEZONE", "Europe/Madrid"),
        )
