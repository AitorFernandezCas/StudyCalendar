"""Supabase-specific helpers, used only by outbound adapters."""
from .domain import DuplicateName


def execute_named(query):
    try:
        return query.execute()
    except Exception as exc:
        text = str(exc).lower()
        if getattr(exc, "code", None) == "23505" or "duplicate" in text or "unique" in text:
            raise DuplicateName() from exc
        raise
