import assert from 'node:assert/strict'
import test from 'node:test'
import { planLayout, unfinishedPrerequisites } from '../src/lib/projectPlan.ts'
import { visibleOverviewTasks } from '../src/lib/projectVisibility.ts'

test('branches merge after all parents and retain positions across input order', () => {
  const tasks = [
    { id: 'a', title: 'A', prerequisiteIds: [] },
    { id: 'b', title: 'B', prerequisiteIds: ['a'] },
    { id: 'c', title: 'C', prerequisiteIds: ['a'] },
    { id: 'd', title: 'D', prerequisiteIds: ['b', 'c'] },
  ]
  const layout = planLayout(tasks)
  assert.equal(layout[1].x, layout[2].x)
  assert.notEqual(layout[1].y, layout[2].y)
  assert.ok(layout[3].x > layout[2].x)
  assert.deepEqual(planLayout([...tasks].reverse()), layout)
  assert.deepEqual(unfinishedPrerequisites(tasks[3], tasks.map(task => ({ ...task, done: task.id === 'b' }))), ['c'])
})

test('undated work is excluded from overview, including unassigned project tasks', () => {
  const tasks = [{ id: 'a', taskType: 'project', date: '' }, { id: 'b', taskType: 'project', date: '2026-11-06' }]
  assert.deepEqual(visibleOverviewTasks(tasks, []).map(task => task.id), ['b'])
})
