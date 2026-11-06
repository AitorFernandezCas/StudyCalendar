import type { Task } from './taskTypes'

export const HOUR_HEIGHT = 84
export const SLOT_MINUTES = 15
export type TaskSchedule = { date: string; time: string; endTime: string; allDay?: boolean }

export const minutes = (time: string) => Number(time.slice(0, 2)) * 60 + Number(time.slice(3, 5))
export const clockTime = (value: number) => `${String(Math.floor(value / 60)).padStart(2, '0')}:${String(value % 60).padStart(2, '0')}`

export function scheduleAtMinute(task: Pick<Task, 'time' | 'endTime'>, date: string, target: number, first = 0, last = 1440): TaskSchedule | null {
  const duration = minutes(task.endTime) - minutes(task.time)
  if (!Number.isFinite(target) || !Number.isFinite(duration) || duration <= 0 || duration > 1439) return null
  // The existing task API stores start/end on one date and does not accept 24:00.
  const earliest = Math.ceil(first / SLOT_MINUTES) * SLOT_MINUTES
  const latest = Math.floor((Math.min(1439, last) - duration) / SLOT_MINUTES) * SLOT_MINUTES
  if (latest < earliest) return null
  const start = Math.max(earliest, Math.min(latest, Math.round(target / SLOT_MINUTES) * SLOT_MINUTES))
  return { date, time: clockTime(start), endTime: clockTime(start + duration) }
}
