package com.studycalendar.core

import java.util.Locale
import java.util.UUID

data class ProjectFeature(
  val id: String = UUID.randomUUID().toString(),
  val projectId: String,
  val name: String,
  val description: String? = null,
) {
  fun normalized() = copy(name = name.trim(), description = description?.trim()?.takeIf { it.isNotEmpty() })
}

data class ProjectFeatureLink(
  val id: String = UUID.randomUUID().toString(),
  val projectId: String,
  val sourceId: String,
  val targetId: String,
)

data class FeatureGroup(val feature: ProjectFeature?, val tasks: List<Task>) {
  val id get() = feature?.id ?: "ungrouped"
  val title get() = feature?.name ?: "Sin funcionalidad"
  val completed get() = tasks.count { it.completed }
  val progress get() = if (tasks.isEmpty()) "Sin tareas" else "$completed/${tasks.size} completadas"
}

data class FeatureEdge(val edge: PlanEdge, val inferred: Boolean, val saved: ProjectFeatureLink? = null)

object Features {
  fun groups(projectId: String, features: List<ProjectFeature>, tasks: List<Task>): List<FeatureGroup> {
    val ordered = features.filter { it.projectId == projectId }.sortedWith { a, b ->
      Overview.compareNames(a.name, b.name).takeIf { it != 0 } ?: a.id.compareTo(b.id)
    }
    val ids = ordered.map { it.id }.toSet()
    val members = tasks.filter { it.projectId == projectId }.groupBy { it.featureId?.takeIf(ids::contains) }
    return ordered.map { FeatureGroup(it, members[it.id].orEmpty()) } + FeatureGroup(null, members[null].orEmpty())
  }

  fun validate(feature: ProjectFeature, features: Collection<ProjectFeature>) {
    require(feature.name.isNotBlank()) { "Introduce un nombre para la funcionalidad." }
    val name = feature.name.lowercase(Locale.ROOT)
    require(features.none { it.id != feature.id && it.projectId == feature.projectId && it.name.lowercase(Locale.ROOT) == name }) {
      "Ya existe una funcionalidad con ese nombre."
    }
  }

  fun validateMembership(task: Task, features: Collection<ProjectFeature>) {
    require(task.featureId == null || features.any { it.id == task.featureId && it.projectId == task.projectId }) {
      "La funcionalidad ya no está disponible en este proyecto."
    }
  }

  fun validateLink(link: ProjectFeatureLink, features: Collection<ProjectFeature>, links: Collection<ProjectFeatureLink>) {
    require(link.sourceId != link.targetId) { "Una funcionalidad no puede depender de sí misma." }
    val ids = features.filter { it.projectId == link.projectId }.map { it.id }.toSet()
    require(link.sourceId in ids && link.targetId in ids) { "Las funcionalidades deben pertenecer al mismo proyecto." }
    require(links.none { it.id == link.id || (it.sourceId == link.sourceId && it.targetId == link.targetId) }) { "Las funcionalidades ya están conectadas." }
    val outgoing = links.filter { it.projectId == link.projectId }.groupBy({ it.sourceId }, { it.targetId })
    require(link.sourceId !in reachable(link.targetId, outgoing)) { "Las conexiones entre funcionalidades no pueden formar un ciclo." }
  }

  fun edges(groups: List<FeatureGroup>, links: List<ProjectFeatureLink>): List<FeatureEdge> {
    val membership = groups.flatMap { group -> group.tasks.map { it.id to group.id } }.toMap()
    val ids = groups.mapNotNull { it.feature?.id }.toSet()
    val edges = mutableMapOf<PlanEdge, FeatureEdge>()
    groups.forEach { group ->
      val sources = group.tasks.flatMap { it.prerequisiteIds }.mapNotNull(membership::get).toSet() - group.id
      sources.forEach { source ->
        val edge = PlanEdge(source, group.id)
        edges[edge] = FeatureEdge(edge, inferred = true)
      }
    }
    links.filter { it.sourceId in ids && it.targetId in ids }.forEach { link ->
      val edge = PlanEdge(link.sourceId, link.targetId)
      edges[edge] = FeatureEdge(edge, inferred = edge in edges, saved = link)
    }
    return edges.values.sortedWith(compareBy({ it.edge.source }, { it.edge.target }))
  }

  fun branch(root: String, edges: List<FeatureEdge>): Set<String> =
    reachable(root, edges.groupBy({ it.edge.source }, { it.edge.target })) +
      reachable(root, edges.groupBy({ it.edge.target }, { it.edge.source }))

  // Task-derived group edges can form cycles even when the task graph is acyclic.
  fun layoutEdges(edges: List<FeatureEdge>): List<PlanEdge> {
    val outgoing = mutableMapOf<String, MutableList<String>>()
    return edges.sortedByDescending { it.saved != null }.mapNotNull { (edge, _) ->
      edge.takeIf { it.source !in reachable(it.target, outgoing) }?.also {
        outgoing.getOrPut(it.source) { mutableListOf() }.add(it.target)
      }
    }
  }

  private fun reachable(root: String, neighbors: Map<String, List<String>>): Set<String> {
    val seen = mutableSetOf<String>()
    val pending = ArrayDeque(listOf(root))
    while (pending.isNotEmpty()) {
      val id = pending.removeLast()
      if (seen.add(id)) pending.addAll(neighbors[id].orEmpty())
    }
    return seen
  }
}
