package com.studycalendar.core

import org.junit.Assert.*
import org.junit.Test

class FeaturesTest {
  private val a = ProjectFeature("a", "p", "Análisis")
  private val b = ProjectFeature("b", "p", "Borrador")
  private val c = ProjectFeature("c", "p", "Corrección")
  private val features = listOf(c, a, b)
  private fun task(id: String, feature: ProjectFeature?, vararg parents: String) = Task(
    id = id, title = id, date = null, start = null, end = null, allDay = false, projectId = "p",
    projectTask = true, prerequisiteIds = parents.toSet(), featureId = feature?.id,
  )

  @Test fun groupedPlanMergesExplicitAndInferredLinksWithoutLosingCyclicDerivedEdges() {
    val tasks = listOf(task("one", a), task("two", b, "one"), task("three", a, "two"), task("four", null, "three"))
    val groups = Features.groups("p", features, tasks)
    assertEquals(listOf("a", "b", "c", "ungrouped"), groups.map { it.id })
    assertEquals("Sin tareas", groups[2].progress)
    val saved = ProjectFeatureLink("link", "p", "a", "b")
    val edges = Features.edges(groups, listOf(saved))
    assertEquals(3, edges.size)
    assertEquals(FeatureEdge(PlanEdge("a", "b"), true, saved), edges.first())
    assertEquals(setOf("a", "b", "ungrouped"), Features.branch("a", edges))
    assertEquals(listOf(PlanEdge("a", "b"), PlanEdge("a", "ungrouped")), Features.layoutEdges(edges))
    assertTrue(Features.edges(groups, emptyList()).all { it.inferred && it.saved == null })
  }

  @Test fun savedLinksUseProjectScopeAndTheSavedGraphForValidation() {
    val ab = ProjectFeatureLink("ab", "p", "a", "b")
    val bc = ProjectFeatureLink("bc", "p", "b", "c")
    val saved = listOf(ab, bc)
    val other = ProjectFeature("other", "other-project", "Otro")
    listOf(ab.copy(id = "again"), ab.copy(id = "self", targetId = "a"),
      ab.copy(id = "cross-project", targetId = other.id), ab.copy(id = "cycle", sourceId = "c", targetId = "a")).forEach {
      assertThrows(IllegalArgumentException::class.java) { Features.validateLink(it, features + other, saved) }
    }
    Features.validateLink(ab.copy(id = "ac", targetId = "c"), features, saved)
    assertThrows(IllegalArgumentException::class.java) { Features.validate(a.copy(id = "new", name = " ANÁLISIS ").normalized(), features) }
    Features.validate(a.copy(id = "new", projectId = "other-project"), features)
  }

  @Test fun taskEditPreservesConcurrentAssignmentAndRequiresWholeProjectForDependencyRules() {
    val prior = task("prior", a)
    val dependent = task("dependent", b, prior.id)
    val moved = dependent.copy(featureId = c.id)
    assertEquals(moved.copy(title = "Editada"), Planning.withEdit(moved, dependent, dependent.copy(title = "Editada")))
    assertThrows(IllegalArgumentException::class.java) { Planning.save(moved.copy(completed = true), listOf(prior, dependent)) }
    val completed = listOf(prior.copy(completed = true), dependent.copy(completed = true))
    assertThrows(IllegalArgumentException::class.java) { Planning.save(prior, completed) }
    assertThrows(IllegalArgumentException::class.java) { Planning.save(prior.copy(prerequisiteIds = setOf(dependent.id)), listOf(prior, dependent)) }
  }
}
