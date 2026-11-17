package com.studycalendar.core

import org.junit.Assert.*
import org.junit.Test

class ProjectGraphTest {
  private fun task(id: String, vararg parents: String) = Task(id = id, title = id, date = null, start = null, end = null,
    projectTask = true, projectId = "p", prerequisiteIds = parents.toSet())

  @Test fun `layout is deterministic measures titles and draws prerequisite to dependent`() {
    val tasks = listOf(task("d", "b", "c"), task("c", "a"), task("a"), task("b", "a"), task("e"))
    val nodes = ProjectGraph.layout(tasks, mapOf("a" to 130f))
    assertEquals(nodes, ProjectGraph.layout(tasks.reversed(), mapOf("a" to 130f)))
    val byId = nodes.associateBy { it.task.id }
    assertEquals(322f, byId.getValue("b").position.x)
    assertEquals(612f, byId.getValue("d").position.x)
    assertEquals(206f, byId.getValue("e").position.y)
    assertTrue(ProjectGraph.edges(tasks).contains(PlanEdge("a", "b")))
    val position = PlanPoint(73f, 82f)
    assertEquals(position, ProjectGraph.layout(tasks, positions = mapOf("b" to position)).find { it.task.id == "b" }!!.position)
    assertEquals(322f, ProjectGraph.layout(tasks, positions = mapOf("b" to PlanPoint(Float.NaN, 1f))).find { it.task.id == "b" }!!.position.x)
  }

  @Test fun `branch excludes siblings and additional inputs of downstream tasks`() {
    val tasks = listOf(task("a"), task("b", "a"), task("c", "a"), task("d", "b", "x"), task("x"), task("isolated"))
    assertEquals(setOf("a", "b", "d"), ProjectGraph.branch(tasks, "b"))
    assertEquals(listOf("b", "x"), ProjectGraph.blockers(tasks.first { it.id == "d" }, tasks))
    assertEquals(setOf("isolated"), ProjectGraph.branch(tasks, "isolated"))
    assertTrue(ProjectGraph.branch(tasks, "missing").isEmpty())
    assertEquals(setOf("a", "c"), ProjectGraph.branch(tasks.filter { it.id != "b" && it.id != "d" }, "a"))
  }

  @Test fun `long chains cycles and missing references remain finite`() {
    val chain = (0..3000).map { if (it == 0) task("0") else task(it.toString(), (it - 1).toString()) }
    assertEquals(3001, ProjectGraph.branch(chain, "1500").size)
    assertEquals(3001, ProjectGraph.layout(chain).size)
    val corrupt = listOf(task("a", "b", "gone"), task("b", "a"))
    assertEquals(setOf("a", "b"), ProjectGraph.branch(corrupt, "a"))
    assertTrue(ProjectGraph.layout(corrupt).all { it.position.valid() })
    assertEquals(2, ProjectGraph.edges(corrupt).size)
  }

  @Test fun `coordinate conversion zoom anchor fitting and movement use world units`() {
    val camera = PlanCamera(.5f, 10f, 20f)
    val point = PlanPoint(100f, 200f)
    assertEquals(point, ProjectGraph.world(ProjectGraph.screen(point, camera), camera))
    val anchor = PlanPoint(70f, 85f)
    assertEquals(ProjectGraph.world(anchor, camera), ProjectGraph.world(anchor, ProjectGraph.zoom(camera, 2f, anchor)))
    assertEquals(PlanPoint(140f, 220f), ProjectGraph.move(point, 20f, 10f, .5f))
    assertEquals(PlanPoint(16f, 16f), ProjectGraph.move(point, -500f, -500f, 1f))
    val nodes = ProjectGraph.layout(listOf(task("a"), task("b", "a")))
    val fit = ProjectGraph.fit(nodes, 600f, 400f)
    assertTrue(fit.zoom in .4f..1f)
    assertEquals(PlanCamera(), ProjectGraph.fit(emptyList(), 600f, 400f))
  }

  @Test fun `arrows skip unrelated cards without appearing to connect through them`() {
    val nodes = ProjectGraph.layout(listOf(task("a"), task("b", "a"), task("c", "a", "b")))
    val direct = ProjectGraph.route(PlanEdge("a", "b"), nodes)
    assertEquals(1, direct.size)
    val skip = ProjectGraph.route(PlanEdge("a", "c"), nodes)
    assertEquals(3, skip.size)
    assertTrue(skip[1].start.y < nodes.minOf { it.position.y })
    assertEquals(skip[0].end, skip[1].start)
    assertEquals(skip[1].end, skip[2].start)
    assertEquals(nodes.first { it.task.id == "c" }.position.x, skip.last().end.x)
    assertTrue(ProjectGraph.route(PlanEdge("gone", "c"), nodes).isEmpty())
  }

  @Test fun `guarded prerequisites preserve fields reject stale project links and validate current rules`() {
    val a = task("a")
    val b = task("b").copy(title = "Título posterior")
    val changed = Planning.withPrerequisites(b, "p", emptySet(), setOf(a.id))
    assertEquals(b.title, changed.title)
    Planning.save(changed, listOf(a, b))
    assertThrows(IllegalArgumentException::class.java) { Planning.withPrerequisites(changed, "p", emptySet(), emptySet()) }
    assertThrows(IllegalArgumentException::class.java) { Planning.withPrerequisites(b, "other", emptySet(), setOf(a.id)) }
    assertThrows(IllegalArgumentException::class.java) { Planning.save(changed.copy(completed = true), listOf(a, b)) }
    assertThrows(IllegalArgumentException::class.java) { Planning.save(a.copy(prerequisiteIds = setOf(b.id)), listOf(a, changed)) }
    assertThrows(IllegalArgumentException::class.java) { Planning.save(changed.copy(prerequisiteIds = setOf("missing")), listOf(a, b)) }
  }
}
