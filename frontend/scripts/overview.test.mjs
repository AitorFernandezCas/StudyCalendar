import assert from 'node:assert/strict'
import test from 'node:test'
import { filterOverviewTasks, groupDailyTasks, relativeTaskDate, startWithDuration } from '../src/lib/overview.ts'
import { authErrorMessage } from '../src/lib/authFeedback.ts'

const task = (id, date, overrides = {}) => ({ id, title: id, date, time: '09:00', endTime: '10:00', allDay: false, done: false, categoryId: null, projectId: null, taskType: 'daily', color: '#7c5cff', ...overrides })
const defaults = { query: '', category: '', status: 'pending', sort: 'date' }

test('auth feedback explains recovery and login errors without exposing raw provider messages', () => {
  assert.equal(authErrorMessage({ code: 'invalid_credentials' }), 'El email o la contraseña no son correctos.')
  assert.match(authErrorMessage({ code: 'otp_expired' }), /caducado/)
  assert.match(authErrorMessage({ code: 'over_email_send_rate_limit' }), /Espera/)
  const fallback = authErrorMessage({ code: 'unknown', message: 'Internal provider detail' })
  assert.match(fallback, /conexión/)
  assert.doesNotMatch(fallback, /Internal provider detail/)
  assert.equal(authErrorMessage(null), fallback)
})

test('date groups keep overdue and future work distinct and completed work recoverable', () => {
  const tasks = [task('past', '2026-10-23'), task('today', '2026-10-26'), task('future', '2026-11-02'), task('done', '2026-10-23', { done: true })]
  assert.deepEqual(groupDailyTasks(tasks, '2026-10-26').map(group => [group.id, group.tasks.map(task => task.id)]), [
    ['overdue', ['past']], ['today', ['today']], ['upcoming', ['future']], ['completed', ['done']],
  ])
})

test('search ignores accents and case and finds associated project names', () => {
  const tasks = [task('a', '2026-10-26', { title: 'Revisión de Física' }), task('b', '2026-10-26', { projectId: 'p', title: 'Preparar resumen' })]
  assert.deepEqual(filterOverviewTasks(tasks, { ...defaults, query: 'revision' }).map(task => task.id), ['a'])
  assert.deepEqual(filterOverviewTasks(tasks, { ...defaults, query: 'OPOSICION' }, new Map([['p', 'Oposición']])).map(task => task.id), ['b'])
})

test('status and category filters combine without mutating saved tasks', () => {
  const tasks = [task('a', '2026-10-26', { done: true, categoryId: 'study' }), task('b', '2026-10-26', { categoryId: 'study' }), task('c', '2026-10-26')]
  assert.deepEqual(filterOverviewTasks(tasks, { ...defaults, status: 'completed', category: 'study' }).map(task => task.id), ['a'])
  assert.deepEqual(filterOverviewTasks(tasks, { ...defaults, category: 'none' }).map(task => task.id), ['c'])
  assert.equal(filterOverviewTasks(tasks, { ...defaults, status: 'all' }).length, 3)
  assert.equal(tasks[0].done, true)
})

test('sorting supports date direction and Spanish titles while preserving all-day precedence', () => {
  const tasks = [task('z', '2026-10-26', { title: 'Zeta' }), task('a', '2026-10-26', { allDay: true, title: 'Álgebra' }), task('f', '2026-11-02', { title: 'Física' })]
  assert.deepEqual(filterOverviewTasks(tasks, defaults).map(task => task.id), ['a', 'z', 'f'])
  assert.deepEqual(filterOverviewTasks(tasks, { ...defaults, sort: 'date-desc' }).map(task => task.id), ['f', 'a', 'z'])
  assert.deepEqual(filterOverviewTasks(tasks, { ...defaults, sort: 'title' }).map(task => task.id), ['a', 'f', 'z'])
  assert.deepEqual(tasks.map(task => task.id), ['z', 'a', 'f'])
})

test('relative dates count calendar days across DST and year boundaries', () => {
  assert.equal(relativeTaskDate('2026-10-25', '2026-10-26'), 'Ayer')
  assert.equal(relativeTaskDate('2026-10-23', '2026-10-26'), 'Hace 3 días')
  assert.equal(relativeTaskDate('2026-10-26', '2026-10-26'), 'Hoy')
  assert.equal(relativeTaskDate('2027-01-01', '2026-12-31'), 'Mañana')
  assert.match(relativeTaskDate('2027-01-03', '2026-12-31'), /2027/)
})

test('changing start preserves arbitrary duration and never crosses midnight', () => {
  assert.deepEqual(startWithDuration('11:15', '09:00', '10:00'), { time: '11:15', endTime: '12:15' })
  assert.deepEqual(startWithDuration('12:00', '09:10', '09:37'), { time: '12:00', endTime: '12:27' })
  assert.deepEqual(startWithDuration('23:30', '09:00', '10:00'), { time: '23:30', endTime: '23:59' })
  assert.deepEqual(startWithDuration('', '09:00', '10:00'), { time: '', endTime: '10:00' })
})
