update "Task".routine_period as period
set ends_on = current_date - 1
from "Task".routine as routine
where period.routine_id = routine.id
  and routine.active = false
  and period.ends_on is null
  and period.starts_on <= current_date - 1;
