package com.studycalendar.core

import kotlin.math.max

data class PlanPoint(val x: Float, val y: Float) {
  fun valid() = x.isFinite() && y.isFinite() && x >= 16f && y >= 16f
}
data class PlanCamera(val zoom: Float = 1f, val left: Float = 0f, val top: Float = 0f)
data class PlanNode(val task: Task, val position: PlanPoint, val height: Float)
data class PlanEdge(val source: String, val target: String)
data class PlanCurve(val start: PlanPoint, val first: PlanPoint, val second: PlanPoint, val end: PlanPoint)

/** Geometry is in unscaled dp, independent of Compose, storage and task schedules. */
object ProjectGraph {
  const val NODE_WIDTH = 220f
  const val NODE_HEIGHT = 48f

  fun layout(tasks: List<Task>, heights: Map<String, Float> = emptyMap(), positions: Map<String, PlanPoint> = emptyMap()): List<PlanNode> {
    val byId = tasks.associateBy { it.id }
    val parents = tasks.associate { task -> task.id to task.prerequisiteIds.filter { it in byId }.toSet() }
    val children = mutableMapOf<String, MutableList<String>>()
    parents.forEach { (id, ids) -> ids.forEach { children.getOrPut(it) { mutableListOf() }.add(id) } }
    val remaining = parents.mapValues { it.value.size }.toMutableMap()
    val levels = tasks.associate { it.id to 0 }.toMutableMap()
    val pending = ArrayDeque(remaining.filterValues { it == 0 }.keys)
    // Iteration avoids overflowing the stack for long plans. Corrupt cycles stay renderable.
    while (pending.isNotEmpty()) {
      val id = pending.removeFirst()
      children[id].orEmpty().forEach { child ->
        levels[child] = max(levels.getValue(child), levels.getValue(id) + 1)
        remaining[child] = remaining.getValue(child) - 1
        if (remaining[child] == 0) pending.addLast(child)
      }
    }
    val rows = mutableMapOf<Int, Float>()
    return tasks.sortedWith { a, b -> Overview.compareNames(a.title, b.title).takeIf { it != 0 } ?: a.id.compareTo(b.id) }.map { task ->
      val level = levels.getValue(task.id)
      val height = heights[task.id]?.takeIf { it.isFinite() }?.coerceAtLeast(NODE_HEIGHT) ?: NODE_HEIGHT
      val y = rows[level] ?: 32f
      rows[level] = y + height + 44f
      PlanNode(task, positions[task.id]?.takeIf { it.valid() } ?: PlanPoint(32f + level * (NODE_WIDTH + 70f), y), height)
    }
  }

  fun edges(tasks: List<Task>): List<PlanEdge> {
    val ids = tasks.map { it.id }.toSet()
    return tasks.flatMap { task -> task.prerequisiteIds.filter { it in ids }.sorted().map { PlanEdge(it, task.id) } }
  }

  fun route(edge: PlanEdge, nodes: List<PlanNode>): List<PlanCurve> {
    val source = nodes.find { it.task.id == edge.source } ?: return emptyList()
    val target = nodes.find { it.task.id == edge.target } ?: return emptyList()
    val start = PlanPoint(source.position.x + NODE_WIDTH, source.position.y + source.height / 2)
    val end = PlanPoint(target.position.x, target.position.y + target.height / 2)
    val obstructed = nodes.any { node ->
      node.task.id != edge.source && node.task.id != edge.target &&
        node.position.x < maxOf(start.x, end.x) && node.position.x + NODE_WIDTH > minOf(start.x, end.x) &&
        node.position.y < maxOf(start.y, end.y) + 8 && node.position.y + node.height > minOf(start.y, end.y) - 8
    }
    if (!obstructed) return listOf(PlanCurve(start, start.copy(x = start.x + 55), end.copy(x = end.x - 55), end))
    // A skipped column must not look as though it connects through an unrelated card.
    val lane = nodes.minOf { it.position.y } - 24f
    val first = PlanPoint(start.x + 28f, lane)
    val last = PlanPoint(end.x - 28f, lane)
    return listOf(
      PlanCurve(start, start.copy(x = start.x + 28f), first, first),
      PlanCurve(first, first, last, last),
      PlanCurve(last, last, end.copy(x = end.x - 28f), end),
    )
  }

  fun branch(tasks: List<Task>, root: String): Set<String> {
    val byId = tasks.associateBy { it.id }
    if (root !in byId) return emptySet()
    val children = mutableMapOf<String, MutableList<String>>()
    tasks.forEach { task -> task.prerequisiteIds.forEach { children.getOrPut(it) { mutableListOf() }.add(task.id) } }
    fun walk(next: (String) -> Collection<String>): Set<String> {
      val seen = mutableSetOf<String>()
      val pending = ArrayDeque(listOf(root))
      while (pending.isNotEmpty()) {
        val id = pending.removeLast()
        if (id in byId && seen.add(id)) pending.addAll(next(id))
      }
      return seen
    }
    return walk { byId[it]?.prerequisiteIds.orEmpty() } + walk { children[it].orEmpty() }
  }

  fun blockers(task: Task, tasks: List<Task>) = task.prerequisiteIds.filter { id -> tasks.none { it.id == id && it.completed } }
  fun screen(point: PlanPoint, camera: PlanCamera) = PlanPoint((point.x - camera.left) * camera.zoom, (point.y - camera.top) * camera.zoom)
  fun world(point: PlanPoint, camera: PlanCamera) = PlanPoint(point.x / camera.zoom + camera.left, point.y / camera.zoom + camera.top)
  fun move(point: PlanPoint, dx: Float, dy: Float, zoom: Float) = PlanPoint(max(16f, point.x + dx / zoom), max(16f, point.y + dy / zoom))

  fun zoom(camera: PlanCamera, scale: Float, anchor: PlanPoint): PlanCamera {
    val before = world(anchor, camera)
    val zoom = (camera.zoom * scale).coerceIn(.4f, 1.6f)
    return PlanCamera(zoom, before.x - anchor.x / zoom, before.y - anchor.y / zoom)
  }

  fun fit(nodes: List<PlanNode>, width: Float, height: Float): PlanCamera {
    if (nodes.isEmpty() || width <= 0 || height <= 0) return PlanCamera()
    val left = nodes.minOf { it.position.x }
    val top = nodes.minOf { it.position.y } - 24f
    val right = nodes.maxOf { it.position.x + NODE_WIDTH }
    val bottom = nodes.maxOf { it.position.y + it.height }
    val zoom = minOf((width - 32).coerceAtLeast(1f) / (right - left), (height - 32).coerceAtLeast(1f) / (bottom - top), 1f).coerceAtLeast(.4f)
    return PlanCamera(zoom, (left + right) / 2 - width / (2 * zoom), (top + bottom) / 2 - height / (2 * zoom))
  }
}
