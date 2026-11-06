import type { Task } from './taskTypes'

export function planLayout(tasks: Task[]) {
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
  const rows = new Map<number, number>()
  return [...tasks].sort((a, b) => a.title.localeCompare(b.title, 'es') || a.id.localeCompare(b.id)).map(task => {
    const level = depth(task.id)
    const row = rows.get(level) || 0
    rows.set(level, row + 1)
    return { task, x: 32 + level * 320, y: 32 + row * 240 }
  })
}

export function unfinishedPrerequisites(task: Task, tasks: Task[]) {
  return (task.prerequisiteIds || []).filter(id => !tasks.find(row => row.id === id)?.done)
}
