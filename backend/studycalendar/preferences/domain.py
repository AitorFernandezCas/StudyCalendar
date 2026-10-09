from dataclasses import asdict, dataclass, replace
import re
from ..shared.domain import BusinessError


@dataclass(frozen=True)
class Preferences:
    routine_reset_time: str = "00:00"
    calendar_start_time: str = "00:00"
    calendar_end_time: str = "24:00"
    week_start: str = "monday"

    def payload(self):
        return asdict(self)

    def patch(self, raw):
        if not isinstance(raw, dict) or not raw:
            raise BusinessError("Indica los ajustes que quieres cambiar.")
        if set(raw) - set(self.payload()):
            raise BusinessError("Hay ajustes no reconocidos.")
        errors = {}
        for name, value in raw.items():
            if name == "week_start":
                valid = isinstance(value, str) and value in ("monday", "sunday")
            else:
                valid = isinstance(value, str) and bool(re.fullmatch(r"(?:[01]\d|2[0-3]):[0-5]\d", value))
                valid = valid or (name == "calendar_end_time" and value == "24:00")
            if not valid:
                errors[name] = "Selecciona un valor válido."
        if errors:
            raise BusinessError("Revisa los ajustes.", details={"fields": errors})
        result = replace(self, **raw)
        if result.calendar_start_time >= result.calendar_end_time:
            raise BusinessError("La hora de fin debe ser posterior al inicio.", details={"fields": {
                "calendar_end_time": "La hora de fin debe ser posterior al inicio."}})
        return result
