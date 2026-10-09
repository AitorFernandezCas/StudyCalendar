import assert from 'node:assert/strict'
import test from 'node:test'
import { calendarWeekStart, timeMinutes, timeSegments, visibleTimedTask } from '../src/lib/calendarPreferences.ts'
import { scheduleAtMinute } from '../src/lib/calendarDrag.ts'
import { routineDayIsCurrent } from '../src/lib/routineDay.ts'

test('week start preserves the selected date and handles month/year boundaries', () => {
  const date = new Date(2027, 0, 1, 14, 30)
  assert.equal(calendarWeekStart(date, 'monday').getDate(), 28)
  assert.equal(calendarWeekStart(date, 'sunday').getDate(), 27)
  assert.equal(calendarWeekStart(date, 'sunday').getFullYear(), 2026)
  assert.equal(date.getDate(), 1)
  const sunday = new Date(2026, 10, 1)
  assert.equal(calendarWeekStart(sunday, 'sunday').getDate(), 1)
  assert.equal(calendarWeekStart(sunday, 'monday').getMonth(), 9)
})

test('time labels and partial segments cover the exact range including midnight', () => {
  assert.equal(timeMinutes('24:00'), 1440)
  const segments = timeSegments(450, 1335)
  assert.deepEqual(segments[0], { minute: 450, duration: 30 })
  assert.deepEqual(segments.at(-1), { minute: 1320, duration: 15 })
  assert.equal(segments.reduce((total, item) => total + item.duration, 0), 885)
  assert.deepEqual(timeSegments(1439, 1440), [{ minute: 1439, duration: 1 }])
  assert.equal(timeSegments(0, 1440).length, 24)
})

test('only intersecting timed tasks and all-day tasks are shown', () => {
  const timed = (time, endTime) => ({ time, endTime, allDay: false })
  assert.equal(visibleTimedTask(timed('06:00', '07:30'), 450, 1335), false)
  assert.equal(visibleTimedTask(timed('22:15', '23:00'), 450, 1335), false)
  assert.equal(visibleTimedTask(timed('07:00', '08:00'), 450, 1335), true)
  assert.equal(visibleTimedTask(timed('22:00', '23:00'), 450, 1335), true)
  assert.equal(visibleTimedTask({ ...timed('01:00', '02:00'), allDay: true }, 450, 1335), true)
})

test('dragging snaps inside minute-based bounds and keeps the entire duration', () => {
  const task = { time: '09:07', endTime: '09:59' }
  assert.deepEqual(scheduleAtMinute(task, '2026-10-09', 300, 451, 1335), { date: '2026-10-09', time: '07:45', endTime: '08:37' })
  assert.deepEqual(scheduleAtMinute(task, '2026-10-09', 1400, 450, 1335), { date: '2026-10-09', time: '21:15', endTime: '22:07' })
  assert.equal(scheduleAtMinute({ time: '09:00', endTime: '11:00' }, '2026-10-09', 480, 450, 500), null)
  assert.equal(scheduleAtMinute({ time: '09:00', endTime: '09:01' }, '2026-10-09', 1439, 1439, 1440), null)
})

test('routine snapshots remain valid after civil midnight and expire exactly at reset', () => {
  const snapshot = { day_started_at: '2026-10-08T04:30:00+02:00', next_day_at: '2026-10-09T04:30:00+02:00' }
  assert.equal(routineDayIsCurrent(snapshot, Date.parse('2026-10-09T02:00:00+02:00')), true)
  assert.equal(routineDayIsCurrent(snapshot, Date.parse(snapshot.day_started_at)), true)
  assert.equal(routineDayIsCurrent(snapshot, Date.parse(snapshot.day_started_at) - 1), false)
  assert.equal(routineDayIsCurrent(snapshot, Date.parse(snapshot.next_day_at)), false)
  assert.equal(routineDayIsCurrent({ day_started_at: '', next_day_at: '' }), false)
  const repeated = { day_started_at: '2026-10-25T02:30:00+02:00', next_day_at: '2026-10-26T02:30:00+01:00' }
  assert.equal(routineDayIsCurrent(repeated, Date.parse('2026-10-25T02:15:00+01:00')), true)
})
