import assert from 'node:assert/strict'
import test from 'node:test'
import { scheduleAtMinute } from '../src/lib/calendarDrag.ts'

test('moves to quarter-hour slots and keeps the original duration and target day', () => {
  const task = { time: '09:00', endTime: '10:15' }
  assert.deepEqual(scheduleAtMinute(task, '2026-10-09', 9 * 60 + 8), { date: '2026-10-09', time: '09:15', endTime: '10:30' })
  assert.deepEqual(scheduleAtMinute(task, '2026-10-08', 9 * 60 + 7), { date: '2026-10-08', time: '09:00', endTime: '10:15' })
  assert.deepEqual(scheduleAtMinute(task, '2026-10-08', 10 * 60 + 40), { date: '2026-10-08', time: '10:45', endTime: '12:00' })
})

test('keeps the full task within the existing same-day API time limits', () => {
  const task = { time: '09:00', endTime: '10:00' }
  assert.deepEqual(scheduleAtMinute(task, '2026-10-08', -60), { date: '2026-10-08', time: '00:00', endTime: '01:00' })
  assert.deepEqual(scheduleAtMinute(task, '2026-10-08', 24 * 60), { date: '2026-10-08', time: '22:45', endTime: '23:45' })
  assert.deepEqual(scheduleAtMinute({ time: '09:00', endTime: '09:15' }, '2026-10-08', 24 * 60), { date: '2026-10-08', time: '23:30', endTime: '23:45' })
})

test('preserves imported durations that are not multiples of fifteen minutes', () => {
  assert.deepEqual(scheduleAtMinute({ time: '09:07', endTime: '09:59' }, '2026-10-08', 12 * 60), { date: '2026-10-08', time: '12:00', endTime: '12:52' })
})

test('does not offer a move for invalid time ranges or pointer coordinates', () => {
  assert.equal(scheduleAtMinute({ time: '10:00', endTime: '09:00' }, '2026-10-08', 600), null)
  assert.equal(scheduleAtMinute({ time: '10:00', endTime: '10:00' }, '2026-10-08', 600), null)
  assert.equal(scheduleAtMinute({ time: 'invalid', endTime: '10:00' }, '2026-10-08', 600), null)
  assert.equal(scheduleAtMinute({ time: '09:00', endTime: '10:00' }, '2026-10-08', NaN), null)
})
