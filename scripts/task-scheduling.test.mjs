import test from 'node:test'
import assert from 'node:assert/strict'
import { droppedTaskSchedule, taskSchedule } from '../src/lib/taskScheduling.ts'

test('all-day saves retain valid hours and supply defaults for invalid hours', () => {
  assert.deepEqual(taskSchedule({ allDay: true, time: '08:30', endTime: '10:15' }), { all_day: true, start_time: '08:30', end_time: '10:15' })
  for (const [time, endTime] of [['', ''], ['10:00', '09:00'], ['09:00', '09:00']]) {
    assert.deepEqual(taskSchedule({ allDay: true, time, endTime }), { all_day: true, start_time: '09:00', end_time: '10:00' })
  }
})

test('drop converts all-day tasks to a one-hour interval at the nearest quarter hour', () => {
  assert.deepEqual(droppedTaskSchedule({ allDay: true, time: '08:00', endTime: '12:00' }, 607), { all_day: false, start_time: '10:00', end_time: '11:00' })
  assert.deepEqual(droppedTaskSchedule({ allDay: true, time: '08:00', endTime: '12:00' }, 608), { all_day: false, start_time: '10:15', end_time: '11:15' })
})

test('timed drops preserve duration and clamp both ends within the day', () => {
  const task = { allDay: false, time: '08:00', endTime: '09:30' }
  assert.deepEqual(droppedTaskSchedule(task, 600), { all_day: false, start_time: '10:00', end_time: '11:30' })
  assert.deepEqual(droppedTaskSchedule(task, -30), { all_day: false, start_time: '00:00', end_time: '01:30' })
  assert.deepEqual(droppedTaskSchedule(task, 1440), { all_day: false, start_time: '23:30', end_time: '23:45' })
  assert.deepEqual(droppedTaskSchedule({ ...task, endTime: '08:05' }, 600), { all_day: false, start_time: '10:00', end_time: '10:15' })
})
