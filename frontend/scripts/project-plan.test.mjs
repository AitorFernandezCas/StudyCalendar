import assert from 'node:assert/strict'
import test from 'node:test'
import { movePlanPosition, planLayout, relatedTaskIds, unfinishedPrerequisites } from '../src/lib/projectPlan.ts'
import { visibleOverviewTasks } from '../src/lib/projectVisibility.ts'
import { featureBranch, featureGraphEdges, featureGraphNodes, featureLayoutNodes } from '../src/lib/featureGraph.ts'

test('saved and inferred arrows merge without letting inferred cycles reverse the planned layout', () => {
  const nodes = featureGraphNodes([
    { featureId: 'a', name: 'Análisis', tasks: [{ id: 'a1' }, { id: 'a2', prerequisiteIds: ['b1'] }] },
    { featureId: 'b', name: 'Entrega', tasks: [{ id: 'b1', prerequisiteIds: ['a1'] }] },
    { featureId: 'c', name: 'Aparte', tasks: [] },
  ])
  const link = { id: 'link', project_id: 'p', source_feature_id: 'a', target_feature_id: 'b' }
  const edges = featureGraphEdges(nodes, [link])
  assert.deepEqual(edges, [
    { source: 'a', target: 'b', inferred: true, saved: link },
    { source: 'b', target: 'a', inferred: true },
  ])
  assert.deepEqual(featureBranch('a', edges), new Set(['a', 'b']))
  const layout = new Map(planLayout(featureLayoutNodes(nodes, edges)).map(node => [node.task.id, node.x]))
  assert.ok(layout.get('a') < layout.get('b'))
  assert.deepEqual(featureGraphEdges(nodes, []).find(edge => edge.source === 'a'), { source: 'a', target: 'b', inferred: true })
})

test('feature graph aggregates cross-feature links and keeps ungrouped work and empty features', () => {
  const groups = [
    { featureId: 'a', name: 'Preparación', tasks: [
      { id: 'a1', done: true },
      { id: 'a2', prerequisiteIds: ['a1', 'outside'] },
    ] },
    { featureId: 'b', name: 'Entrega', tasks: [
      { id: 'b1', prerequisiteIds: ['a1', 'a2', 'missing'] },
      { id: 'b2', prerequisiteIds: ['a1'] },
    ] },
    { featureId: 'empty', name: 'Seguimiento', tasks: [] },
    { featureId: null, name: 'Sin funcionalidad', tasks: [{ id: 'outside' }] },
  ]
  const nodes = featureGraphNodes(groups)
  assert.deepEqual(nodes.map(node => [node.id, node.prerequisiteIds]), [
    ['a', ['ungrouped']], ['b', ['a']], ['empty', []], ['ungrouped', []],
  ])
  assert.deepEqual(nodes.map(node => [node.completed, node.total]), [[1, 2], [0, 2], [0, 0], [0, 1]])
  assert.equal(nodes[3].featureId, null)
  const byId = new Map(planLayout(nodes).map(node => [node.task.id, node]))
  assert.ok(byId.get('ungrouped').x < byId.get('a').x && byId.get('a').x < byId.get('b').x)
})

test('reciprocal feature links preserve both directions without a task cycle', () => {
  const groups = [
    { featureId: 'a', name: 'Preparación', tasks: [{ id: 'a1' }, { id: 'a2', prerequisiteIds: ['b1'] }] },
    { featureId: 'b', name: 'Entrega', tasks: [{ id: 'b1', prerequisiteIds: ['a1'] }] },
  ]
  const nodes = featureGraphNodes(groups)
  assert.deepEqual(nodes.map(node => node.prerequisiteIds), [['b'], ['a']])
  assert.deepEqual(planLayout(nodes), planLayout(featureGraphNodes([...groups].reverse())))
})

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


test('moving a card respects zoom and canvas bounds without altering dependencies', () => {
  const task = { id: 'b', title: 'B', prerequisiteIds: ['a'] }
  const position = { x: 352, y: 32 }
  assert.deepEqual(movePlanPosition(position, 100, 60, .5), { x: 552, y: 152 })
  assert.deepEqual(movePlanPosition(position, -2000, -2000, 1.6), { x: 16, y: 16 })
  assert.deepEqual(position, { x: 352, y: 32 })
  assert.deepEqual(task.prerequisiteIds, ['a'])
})


const branchTasks = [
  { id: 'a', title: 'A', prerequisiteIds: [] },
  { id: 'b', title: 'B', prerequisiteIds: ['a'] },
  { id: 'd', title: 'D', prerequisiteIds: ['b', 'x'], done: true },
  { id: 'sibling', title: 'Sibling', prerequisiteIds: ['a'] },
  { id: 'x', title: 'Outside prerequisite', prerequisiteIds: ['y'] },
  { id: 'y', title: 'Y', prerequisiteIds: [] },
  { id: 'isolated', title: 'Isolated', prerequisiteIds: [] },
]
const related = (tasks, id) => [...relatedTaskIds(tasks, id)].sort()

test('branch focus includes transitive ancestors and descendants but excludes sideways branches', () => {
  assert.deepEqual(related(branchTasks, 'b'), ['a', 'b', 'd'])
  assert.deepEqual(related(branchTasks, 'a'), ['a', 'b', 'd', 'sibling'])
  assert.deepEqual(related(branchTasks, 'd'), ['a', 'b', 'd', 'x', 'y'])
  assert.deepEqual(related(branchTasks, 'isolated'), ['isolated'])
  assert.deepEqual(related(branchTasks, 'missing'), [])
  assert.deepEqual(related([], 'a'), [])
})

test('hidden prerequisites still determine whether a visible downstream task can complete', () => {
  const tasks = branchTasks.map(task => ({ ...task, done: task.id === 'a' || task.id === 'b' }))
  const visible = tasks.filter(task => relatedTaskIds(tasks, 'b').has(task.id))
  assert.equal(visible.some(task => task.id === 'x'), false)
  assert.deepEqual(unfinishedPrerequisites(visible.find(task => task.id === 'd'), tasks), ['x'])
})

test('switching focus uses the whole graph and dependency changes update visibility', () => {
  assert.deepEqual(related(branchTasks, 'b'), ['a', 'b', 'd'])
  assert.deepEqual(related(branchTasks, 'd'), ['a', 'b', 'd', 'x', 'y'])
  const disconnected = branchTasks.map(task => task.id === 'b' ? { ...task, prerequisiteIds: [] } : task)
  assert.deepEqual(related(disconnected, 'b'), ['b', 'd'])
  assert.deepEqual(related(branchTasks.filter(task => task.id !== 'b'), 'b'), [])
  assert.deepEqual(branchTasks.find(task => task.id === 'd').prerequisiteIds, ['b', 'x'])
})

test('branch traversal ignores missing references, tolerates cycles, and handles long chains iteratively', () => {
  assert.deepEqual(related([{ id: 'a', prerequisiteIds: ['missing'] }], 'a'), ['a'])
  assert.deepEqual(related([{ id: 'a', prerequisiteIds: ['b'] }, { id: 'b', prerequisiteIds: ['a'] }], 'a'), ['a', 'b'])
  const chain = Array.from({ length: 5000 }, (_, index) => ({ id: String(index), prerequisiteIds: index ? [String(index - 1)] : [] }))
  assert.equal(relatedTaskIds(chain, '2500').size, 5000)
})


test('automatic layout leaves space for each title height within its column', () => {
  const tasks = [
    { id: 'a', title: 'A long title', prerequisiteIds: [] },
    { id: 'b', title: 'B', prerequisiteIds: [] },
    { id: 'c', title: 'C long title', prerequisiteIds: ['a'] },
    { id: 'd', title: 'D', prerequisiteIds: ['a'] },
  ]
  const nodes = planLayout(tasks, { a: 140, c: 94 })
  assert.equal(nodes[0].height, 140)
  assert.equal(nodes[1].y, nodes[0].y + 140 + 44)
  assert.equal(nodes[2].height, 94)
  assert.equal(nodes[3].y, nodes[2].y + 94 + 44)
  assert.equal(nodes[0].x, nodes[1].x)
  assert.ok(nodes[2].x > nodes[0].x)
  assert.equal(nodes[1].height, 48)
})
