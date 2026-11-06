export const timeMinutes = (value: string) => Number(value.slice(0, 2)) * 60 + Number(value.slice(3, 5))

export function calendarWeekStart(value: Date, firstDay: 'monday' | 'sunday') {
  const result = new Date(value)
  result.setDate(result.getDate() - (result.getDay() + (firstDay === 'monday' ? 6 : 0)) % 7)
  result.setHours(0, 0, 0, 0)
  return result
}

export function visibleTimedTask(task: { allDay: boolean; time: string; endTime: string }, start: number, end: number) {
  return task.allDay || timeMinutes(task.time) < end && timeMinutes(task.endTime) > start
}

export function timeSegments(start: number, end: number) {
  const result: { minute: number; duration: number }[] = []
  for (let minute = start; minute < end;) {
    const next = Math.min(end, (Math.floor(minute / 60) + 1) * 60)
    result.push({ minute, duration: next - minute })
    minute = next
  }
  return result
}
