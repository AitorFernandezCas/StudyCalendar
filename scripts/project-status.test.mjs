import assert from 'node:assert/strict'
import test from 'node:test'
import { visibleOverviewTasks } from '../src/lib/projectVisibility.ts'

const projects = [
  { id: 'a', status: 'active' },
  { id: 'i', status: 'inactive' },
  { id: 'c', status: 'completed' },
]
const tasks = [
  { id: 'daily', taskType: 'daily', projectId: null, done: false },
  { id: 'active', taskType: 'project', projectId: 'a', done: false },
  { id: 'inactive', taskType: 'project', projectId: 'i', done: false },
  { id: 'completed-project', taskType: 'project', projectId: 'c', done: false },
  { id: 'unassigned', taskType: 'project', projectId: null, done: false },
  { id: 'deleted-project', taskType: 'project', projectId: 'missing', done: false },
  { id: 'completed-task', taskType: 'project', projectId: 'a', done: true },
]

test('overview excludes inactive and completed projects without relabelling their tasks as unassigned', () => {
  assert.deepEqual(visibleOverviewTasks(tasks, projects).map(task => task.id), ['daily', 'active', 'unassigned', 'deleted-project', 'completed-task'])
  assert.equal(visibleOverviewTasks(tasks, projects).filter(task => !task.done).length, 4)
})

test('reactivation restores associated tasks and preserves their completion history', () => {
  const reactivated = projects.map(project => ({ ...project, status: 'active' }))
  assert.deepEqual(visibleOverviewTasks(tasks, reactivated), tasks)
  assert.equal(tasks.find(task => task.id === 'completed-task').done, true)
  assert.equal(projects.find(project => project.id === 'i').status, 'inactive')
})

test('completing a project hides its pending tasks without completing or deleting them', () => {
  const completed = projects.map(project => ({ ...project, status: 'completed' }))
  assert.deepEqual(visibleOverviewTasks(tasks, completed).map(task => task.id), ['daily', 'unassigned', 'deleted-project'])
  assert.equal(tasks.find(task => task.id === 'active').done, false)
  assert.equal(tasks.length, 7)
})
