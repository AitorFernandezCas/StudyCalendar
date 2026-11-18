import type { Task } from './taskTypes'
import type { ApiFeatureLink } from './api'

export type FeatureGroup = {
  featureId: string | null
  name: string
  tasks: Task[]
}

export function featureGraphNodes(groups: FeatureGroup[]) {
  const groupByTask = new Map<string, string>()
  const nodes = groups.map(group => {
    const id = group.featureId ?? 'ungrouped'
    for (const task of group.tasks) groupByTask.set(task.id, id)
    return {
      id,
      featureId: group.featureId,
      title: group.name,
      total: group.tasks.length,
      completed: group.tasks.filter(task => task.done).length,
    }
  })
  return nodes.map((node, index) => {
    const incoming = new Set<string>()
    for (const task of groups[index].tasks) {
      for (const prerequisiteId of task.prerequisiteIds ?? []) {
        const source = groupByTask.get(prerequisiteId)
        if (source && source !== node.id) incoming.add(source)
      }
    }
    return { ...node, prerequisiteIds: [...incoming].sort() }
  })
}

type Node = ReturnType<typeof featureGraphNodes>[number]
export type FeatureEdge = { source: string; target: string; inferred: boolean; saved?: ApiFeatureLink }

export function featureGraphEdges(nodes: Node[], saved: ApiFeatureLink[]): FeatureEdge[] {
  const ids = new Set(nodes.filter(node => node.featureId !== null).map(node => node.id))
  const edges = new Map<string, FeatureEdge>()
  for (const node of nodes) {
    for (const source of node.prerequisiteIds) {
      edges.set(source + '/' + node.id, { source, target: node.id, inferred: true })
    }
  }
  for (const link of saved) {
    const source = link.source_feature_id
    const target = link.target_feature_id
    if (!ids.has(source) || !ids.has(target)) continue
    const key = source + '/' + target
    edges.set(key, { source, target, inferred: edges.has(key), saved: link })
  }
  return [...edges.values()].sort((a, b) => a.source.localeCompare(b.source) || a.target.localeCompare(b.target))
}

function reachable(id: string, neighbors: Map<string, string[]>) {
  const visited = new Set<string>()
  const pending = [id]
  while (pending.length) {
    const next = pending.pop()!
    if (visited.has(next)) continue
    visited.add(next)
    for (const neighbor of neighbors.get(next) ?? []) pending.push(neighbor)
  }
  return visited
}

function appendNeighbor(map: Map<string, string[]>, id: string, neighbor: string) {
  const list = map.get(id)
  if (list) list.push(neighbor)
  else map.set(id, [neighbor])
}

export function featureBranch(root: string, edges: FeatureEdge[]) {
  const incoming = new Map<string, string[]>()
  const outgoing = new Map<string, string[]>()
  for (const edge of edges) {
    appendNeighbor(incoming, edge.target, edge.source)
    appendNeighbor(outgoing, edge.source, edge.target)
  }
  return new Set([...reachable(root, incoming), ...reachable(root, outgoing)])
}

export function featureLayoutNodes(nodes: Node[], edges: FeatureEdge[]) {
  const incoming = new Map<string, string[]>()
  const outgoing = new Map<string, string[]>()
  const add = ({ source, target }: FeatureEdge) => {
    appendNeighbor(incoming, target, source)
    appendNeighbor(outgoing, source, target)
  }
  edges.filter(edge => edge.saved).forEach(add)
  for (const edge of edges) {
    if (!edge.saved && !reachable(edge.target, outgoing).has(edge.source)) add(edge)
  }
  return nodes.map(node => ({ ...node, prerequisiteIds: incoming.get(node.id) ?? [] }))
}
