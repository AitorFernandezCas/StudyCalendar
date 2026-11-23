package com.studycalendar.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.studycalendar.core.*
import java.time.LocalDate

@Composable
internal fun SectionHeading(title: String, count: Int, expanded: Boolean, toggle: () -> Unit) {
  TextButton(onClick = toggle, modifier = Modifier.fillMaxWidth().semantics { stateDescription = if (expanded) "Expandido" else "Contraído" }) {
    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
    Text(count.toString(), Modifier.padding(horizontal = 12.dp))
    Text(if (expanded) "−" else "+")
  }
}

internal fun LazyListScope.taskOverview(
  tasks: List<Task>, projects: List<Project>, categories: List<Category>, routines: List<RoutineRecord>,
  today: LocalDate, habitDate: LocalDate, busy: Boolean, expanded: Set<String>, toggle: (String) -> Unit,
  edit: (Task) -> Unit, complete: (Task) -> Unit, delete: (Task) -> Unit, completeHabit: (RoutineRecord) -> Unit,
  newTask: () -> Unit, newRoutine: () -> Unit, openProjects: () -> Unit, openRoutines: () -> Unit,
  featureNames: Map<String, String> = emptyMap(),
) {
  val pending = Overview.pending(tasks, projects)
  val personal = pending.filter { !it.projectTask }
  val projectTasks = pending.filter { it.projectTask }
  val habits = routines.filter { it.row.routine().due(habitDate) }
    .sortedWith { a, b -> Overview.compareNames(a.row.title, b.row.title) }
  val pendingHabits = habits.count { habitDate !in it.completedDates() }
  item("today-summary") {
    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text("Tu plan para hoy", style = MaterialTheme.typography.titleLarge)
      val todayCount = pending.count { it.date == today }
      Text("$todayCount " + (if (todayCount == 1) "tarea pendiente" else "tareas pendientes") + " · $pendingHabits " + (if (pendingHabits == 1) "rutina pendiente" else "rutinas pendientes"), style = MaterialTheme.typography.bodyMedium)
      val overdue = pending.count { it.date!! < today }
      if (overdue > 0) Text("$overdue " + (if (overdue == 1) "tarea atrasada" else "tareas atrasadas"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
    }
  }
  item("personal-heading") { SectionHeading("Tus tareas", personal.size, "daily" in expanded) { toggle("daily") } }
  if ("daily" in expanded) {
    if (personal.isEmpty()) item("personal-empty") {
      Text("No tienes tareas personales pendientes.")
      TextButton(enabled = !busy, onClick = newTask) { Text("Crear una tarea") }
    }
    Overview.dailyGroups(personal, today).filter { it.second.isNotEmpty() }.forEach { (label, group) ->
      item("daily-$label") { Text("$label (${group.size})", style = MaterialTheme.typography.labelLarge) }
      items(group, key = { "overview-${it.id}" }) { task -> TaskCard(task, null, categories.find { it.id == task.categoryId }, busy,
        { complete(task) }, { edit(task) }, { delete(task) }, today, tasks, featureNames[task.featureId]) }
    }
  }
  item("project-heading") { SectionHeading("Tareas de proyectos", projectTasks.size, "projects" in expanded) { toggle("projects") } }
  if ("projects" in expanded) {
    val groups = projects.filter { it.status == ProjectStatus.ACTIVE }.sortedWith { a, b -> Overview.compareNames(a.name, b.name) }
      .map { it.name to projectTasks.filter { task -> task.projectId == it.id } } + listOf("Sin proyecto" to projectTasks.filter { it.projectId == null })
    if (projectTasks.isEmpty()) item("projects-empty") { Text("No hay tareas de proyecto pendientes con fecha.") }
    groups.filter { it.second.isNotEmpty() }.forEachIndexed { index, (name, group) ->
      item("project-group-$index") { Text("$name (${group.size})", style = MaterialTheme.typography.labelLarge) }
      items(group, key = { "overview-${it.id}" }) { task -> TaskCard(task, projects.find { it.id == task.projectId }, categories.find { it.id == task.categoryId }, busy,
        { complete(task) }, { edit(task) }, { delete(task) }, today, tasks, featureNames[task.featureId]) }
    }
    item("manage-projects") { TextButton(onClick = openProjects) { Text("Ver proyectos") } }
  }
  item("habits-heading") { SectionHeading("Rutinas de hoy", pendingHabits, "habits" in expanded) { toggle("habits") } }
  if ("habits" in expanded) {
    listOf(false to "Pendientes de hoy", true to "Completadas hoy").forEach { (done, label) ->
      val group = habits.filter { (habitDate in it.completedDates()) == done }
      if (group.isNotEmpty()) item("habits-$done") { Text("$label (${group.size})", style = MaterialTheme.typography.labelLarge) }
      items(group, key = { "habit-${it.row.id}" }) { record ->
        RoutineCard(record, habitDate, busy, { completeHabit(record) }, {}, {}, {}, manage = false)
      }
    }
    if (habits.isEmpty()) item("habits-empty") {
      Text("No tienes rutinas para hoy.")
      if (routines.isEmpty()) TextButton(enabled = !busy, onClick = newRoutine) { Text("Crear una rutina") }
    }
    item("manage-routines") { TextButton(onClick = openRoutines) { Text("Ver rutinas") } }
  }
}
