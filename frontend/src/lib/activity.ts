import type { ApiActivityDay } from './api'

export function activityTone(day: ApiActivityDay) {
  return day.status === 'pending' && day.completed > 0 ? 'partial' : day.status
}

export function summarizeActivity(days: ApiActivityDay[], today: string) {
  return days.reduce((summary, day) => {
    if (day.date > today || day.status === 'future') return summary
    summary.completed += day.completed
    if (day.completed > 0) summary.daysWithProgress++
    if (day.status === 'complete' && day.total > 0) summary.completeDays++
    return summary
  }, { completed: 0, daysWithProgress: 0, completeDays: 0 })
}

export function activityDayIndex(index: number, key: string, length: number): number | null {
  if (!length) return null
  if (key === 'Home') return 0
  if (key === 'End') return length - 1
  const steps: Record<string, number> = { ArrowUp: -1, ArrowDown: 1, ArrowLeft: -7, ArrowRight: 7 }
  if (!Object.prototype.hasOwnProperty.call(steps, key)) return null
  return Math.max(0, Math.min(length - 1, index + steps[key]))
}
