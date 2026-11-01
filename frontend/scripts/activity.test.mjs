import assert from 'node:assert/strict'
import test from 'node:test'
import { activityDayIndex, activityTone, summarizeActivity } from '../src/lib/activity.ts'

const day = (date, total, completed, status) => ({ date, total, completed, status })

test('activity colors distinguish partial progress without changing the API status', () => {
  const partial = day('2026-11-01', 6, 3, 'pending')
  assert.equal(activityTone(partial), 'partial')
  assert.equal(partial.status, 'pending')
  assert.equal(activityTone(day('2026-11-01', 6, 0, 'pending')), 'pending')
  assert.equal(activityTone(day('2026-11-01', 6, 6, 'complete')), 'complete')
  assert.equal(activityTone(day('2026-11-02', 6, 3, 'future')), 'future')
  assert.equal(activityTone(day('2026-11-01', 0, 0, 'empty')), 'empty')
})

test('annual totals count completed work through today and ignore future work', () => {
  const days = [day('2026-10-29', 0, 0, 'empty'), day('2026-10-30', 4, 4, 'complete'), day('2026-10-31', 4, 2, 'pending'), day('2026-11-01', 3, 0, 'pending'), day('2026-11-02', 9, 9, 'future'), day('2026-11-03', 8, 8, 'complete')]
  assert.deepEqual(summarizeActivity(days, '2026-11-01'), { completed: 6, daysWithProgress: 2, completeDays: 1 })
  assert.equal(days[4].completed, 9)
})

test('empty years have zero totals instead of an invalid percentage', () => {
  assert.deepEqual(summarizeActivity([], '2026-11-01'), { completed: 0, daysWithProgress: 0, completeDays: 0 })
})

test('calendar navigation moves vertically by day and horizontally by week, clamping at year edges', () => {
  assert.equal(activityDayIndex(20, 'ArrowUp', 365), 19)
  assert.equal(activityDayIndex(20, 'ArrowDown', 365), 21)
  assert.equal(activityDayIndex(20, 'ArrowLeft', 365), 13)
  assert.equal(activityDayIndex(20, 'ArrowRight', 365), 27)
  assert.equal(activityDayIndex(0, 'ArrowLeft', 365), 0)
  assert.equal(activityDayIndex(364, 'ArrowRight', 365), 364)
  assert.equal(activityDayIndex(20, 'Home', 365), 0)
  assert.equal(activityDayIndex(20, 'End', 365), 364)
  assert.equal(activityDayIndex(20, 'Tab', 365), null)
  assert.equal(activityDayIndex(0, 'Home', 0), null)
})

test('navigation includes leap day and the final date of a leap year', () => {
  const days = Array.from({ length: 366 }, (_, index) => new Date(Date.UTC(2024, 0, index + 1)).toISOString().slice(0, 10))
  assert.equal(days[activityDayIndex(58, 'ArrowDown', days.length)], '2024-02-29')
  assert.equal(days[activityDayIndex(59, 'ArrowDown', days.length)], '2024-03-01')
  assert.equal(days[activityDayIndex(0, 'End', days.length)], '2024-12-31')
})
