from datetime import date, datetime, timedelta, time, timezone
from typing import Callable
from zoneinfo import ZoneInfo
from .domain import Day


class SystemClock:
    def __init__(self, timezone: str, now: Callable = datetime.now, local_date: Callable = date.today):
        self.timezone = timezone
        self.now = now
        self._local_date = local_date

    def local_date(self) -> date:
        # Calendar's historic default uses the host date; habits use APP_TIMEZONE.
        return self._local_date()

    def day(self, reset_time: str = "00:00") -> Day:
        now = self.now(ZoneInfo(self.timezone))
        hour, minute = map(int, reset_time.split(":"))

        def boundary(value):
            candidate = datetime.combine(value, time(hour, minute), now.tzinfo)
            # fold=0 selects the first occurrence of a repeated wall time. A
            # round trip detects missing times; advance to the first real minute.
            while candidate.astimezone(timezone.utc).astimezone(now.tzinfo).replace(tzinfo=None) != candidate.replace(tzinfo=None):
                candidate += timedelta(minutes=1)
            return candidate

        today = now.date()
        if now.astimezone(timezone.utc) < boundary(today).astimezone(timezone.utc):
            today -= timedelta(days=1)
        start, end = boundary(today), boundary(today + timedelta(days=1))
        return Day(today, self.timezone, end.isoformat(), start.isoformat())
