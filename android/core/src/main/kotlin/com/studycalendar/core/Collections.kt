package com.studycalendar.core

import java.time.LocalDate
import java.util.UUID

data class Category(val id: String = UUID.randomUUID().toString(), val name: String, val color: String = "#4F46E5") {
  fun validate(others: List<Category>) {
    require(name.isNotBlank()) { "Escribe un nombre." }
    validateColor(color)
    require(others.none { it.id != id && it.name.trim().equals(name.trim(), ignoreCase = true) }) { "Ya existe una categoría con ese nombre." }
  }
}

enum class ProjectStatus(val label: String) {
  ACTIVE("Activo"), INACTIVE("Inactivo"), COMPLETED("Completado")
}

data class Project(
  val id: String = UUID.randomUUID().toString(),
  val name: String,
  val color: String = "#4F46E5",
  val status: ProjectStatus = ProjectStatus.ACTIVE,
) {
  fun validate(others: List<Project>) {
    require(name.isNotBlank()) { "Escribe un nombre." }
    validateColor(color)
    require(others.none { it.id != id && it.name.trim().equals(name.trim(), ignoreCase = true) }) {
      "Ya existe un proyecto con ese nombre."
    }
  }
}

fun validateColor(color: String) {
  require(Regex("#[0-9a-fA-F]{6}").matches(color)) { "Elige un color válido." }
}

data class Routine(
  val id: String = UUID.randomUUID().toString(),
  val title: String,
  val color: String = "#16A085",
  val active: Boolean = true,
  val periods: List<ActivePeriod> = emptyList(),
) {
  fun validate() {
    require(title.isNotBlank()) { "Escribe un título." }
    validateColor(color)
    require(periods.all { it.end == null || it.end >= it.start }) { "El período no es válido." }
  }

  fun due(date: LocalDate) = active && periods.any { it.includes(date) }

  // Pausing closes scheduling, but never removes completion history.
  fun activation(enabled: Boolean, today: LocalDate): Routine {
    if (enabled == active) return this
    val changed = if (enabled) periods + ActivePeriod(today) else periods.mapNotNull {
      when {
        it.end != null -> it
        it.start >= today -> null
        else -> it.copy(end = today.minusDays(1))
      }
    }
    return copy(active = enabled, periods = changed)
  }
}
