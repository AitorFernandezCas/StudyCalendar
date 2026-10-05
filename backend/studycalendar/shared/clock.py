from datetime import date, datetime, timedelta
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

    def day(self) -> Day:
        now = self.now(ZoneInfo(self.timezone))
        tomorrow = datetime.combine(now.date() + timedelta(days=1), datetime.min.time(), now.tzinfo)
        return Day(now.date(), self.timezone, tomorrow.isoformat())
