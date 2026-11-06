import type { Task } from './taskTypes'

export type PlanPosition = { x: number; y: number }
export const PLAN_NODE_WIDTH = 220
export const PLAN_NODE_HEIGHT = 48

export function relatedTaskIds(tasks: Task[], rootId: string): Set<string> {
  const byId = new Map(tasks.map(task => [task.id, task]))
  if (!byId.has(rootId)) return new Set()
  const dependents = new Map<string, string[]>()
  for (const task of tasks) {
    for (const prerequisite of task.prerequisiteIds || []) {
      const children = dependents.get(prerequisite) || []
      children.push(task.id)
      dependents.set(prerequisite, children)
    }
  }
  const walk = (next: (id: string) => string[]) => {
    const seen = new Set<string>()
    const pending = [rootId]
    while (pending.length) {
      const id = pending.pop()!
      if (seen.has(id) || !byId.has(id)) continue
      seen.add(id)
      pending.push(...next(id))
    }
    return seen
  }
  // Separate traversals deliberately exclude siblings and shared inputs to
  // downstream tasks: they are neither ancestors nor descendants of the root.
  return new Set([...walk(id => byId.get(id)?.prerequisiteIds || []), ...walk(id => dependents.get(id) || [])])
}

export function movePlanPosition(position: PlanPosition, deltaX: number, deltaY: number, zoom = 1): PlanPosition {
  return { x: Math.max(16, Math.round(position.x + deltaX / zoom)), y: Math.max(16, Math.round(position.y + deltaY / zoom)) }
}

export function readPlanPositions(key: string): Record<string, PlanPosition> {
  try {
    const saved: unknown = JSON.parse(localStorage.getItem(key) || '{}')
    if (!saved || typeof saved !== 'object' || Array.isArray(saved)) return {}
    return Object.fromEntries(Object.entries(saved).filter(([, position]) => position &&
      typeof position.x === 'number' && Number.isFinite(position.x) && position.x >= 16 &&
      typeof position.y === 'number' && Number.isFinite(position.y) && position.y >= 16))
  } catch { return {} }
}

export function planLayout(tasks: Task[], heights: Record<string, number> = {}) {
  const byId = new Map(tasks.map(task => [task.id, task]))
  const levels = new Map<string, number>()
  const visiting = new Set<string>()
  function depth(id: string): number {
    if (levels.has(id)) return levels.get(id)!
    if (visiting.has(id)) return 0
    visiting.add(id)
    const parents = (byId.get(id)?.prerequisiteIds || []).filter(parent => byId.has(parent))
    const level = parents.length ? 1 + Math.max(...parents.map(depth)) : 0
    visiting.delete(id)
    levels.set(id, level)
    return level
  }
  const nextRow = new Map<number, number>()
  return [...tasks].sort((a, b) => a.title.localeCompare(b.title, 'es') || a.id.localeCompare(b.id)).map(task => {
    const level = depth(task.id)
    const height = Math.max(PLAN_NODE_HEIGHT, heights[task.id] || PLAN_NODE_HEIGHT)
    const y = nextRow.get(level) || 32
    nextRow.set(level, y + height + 44)
    return { task, x: 32 + level * (PLAN_NODE_WIDTH + 70), y, height }
  })
}

export function unfinishedPrerequisites(task: Task, tasks: Task[]) {
  return (task.prerequisiteIds || []).filter(id => !tasks.find(row => row.id === id)?.done)
}
