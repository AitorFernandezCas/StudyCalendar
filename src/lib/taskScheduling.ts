const minutes = (value: string) => {
  const [hour, minute] = value.split(':').map(Number)
  return hour * 60 + minute
}

const time = (value: number) => String(Math.floor(value / 60)).padStart(2, '0') + ':' + String(value % 60).padStart(2, '0')

export function taskSchedule(task: { allDay: boolean; time: string; endTime: string }) {
  const valid = Number.isFinite(minutes(task.time)) && Number.isFinite(minutes(task.endTime)) && minutes(task.endTime) > minutes(task.time)
  return { all_day: task.allDay, start_time: task.allDay && !valid ? '09:00' : task.time, end_time: task.allDay && !valid ? '10:00' : task.endTime }
}

export function droppedTaskSchedule(task: { allDay: boolean; time: string; endTime: string }, destinationMinutes: number) {
  const start = Math.max(0, Math.min(23 * 60 + 30, Math.round(destinationMinutes / 15) * 15))
  const duration = task.allDay ? 60 : Math.max(15, minutes(task.endTime) - minutes(task.time))
  const end = Math.min(23 * 60 + 45, start + duration)
  return { all_day: false, start_time: time(start), end_time: time(end) }
}
