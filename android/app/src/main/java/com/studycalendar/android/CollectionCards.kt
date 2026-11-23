package com.studycalendar.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import java.time.ZoneId
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.studycalendar.core.*
import java.time.LocalDate

@Composable
fun ColorMark(color: String) {
  Box(Modifier.size(12.dp).background(Color(android.graphics.Color.parseColor(color)), CircleShape))
}

@Composable
internal fun MoreActions(title: String, busy: Boolean, edit: () -> Unit, delete: () -> Unit, inspect: Boolean = false) {
  var expanded by remember { mutableStateOf(false) }
  Box {
    IconButton(onClick = { expanded = true }) { Icon(appIcon("Más"), "Acciones de $title") }
    DropdownMenu(expanded, { expanded = false }) {
      DropdownMenuItem(enabled = !busy || inspect, text = { Text(if (busy && inspect) "Ver detalles" else "Editar") }, onClick = { expanded = false; edit() })
      DropdownMenuItem(text = { Text("Eliminar", color = MaterialTheme.colorScheme.error) }, enabled = !busy, onClick = { expanded = false; delete() })
    }
  }
}

@Composable
fun TaskCard(task: Task, project: Project?, category: Category?, busy: Boolean, complete: () -> Unit, edit: () -> Unit, delete: () -> Unit,
  today: LocalDate = LocalDate.now(ZoneId.of("Europe/Madrid")), allTasks: List<Task>? = null, featureName: String? = null) {
  val accent = (project?.color ?: category?.color)?.let { Color(android.graphics.Color.parseColor(it)) } ?: MaterialTheme.colorScheme.primary
  val blockers = if (task.completed) emptyList() else task.prerequisiteIds.filter { id -> allTasks?.find { it.id == id }?.completed != true }
  OutlinedCard(onClick = edit, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
    Row(Modifier.height(IntrinsicSize.Min)) {
      Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
      Checkbox(task.completed, onCheckedChange = { complete() }, enabled = !busy && blockers.isEmpty(), colors = CheckboxDefaults.colors(checkedColor = accent),
        modifier = Modifier.semantics { contentDescription = "${if (task.completed) "Reabrir" else "Completar"} ${task.title}" })
      Column(Modifier.weight(1f).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(task.title, style = MaterialTheme.typography.titleSmall,
          textDecoration = if (task.completed) TextDecoration.LineThrough else null)
        Text(Overview.relativeDate(task.date, today) + when {
          task.date == null -> ""
          task.allDay -> " · Todo el día"
          task.start == null -> " · Sin horario"
          else -> " · ${task.start}–${task.end}"
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val metadata = listOfNotNull(project?.name, featureName, category?.name).joinToString(" · ")
        if (metadata.isNotEmpty()) Text(metadata, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (blockers.isNotEmpty()) Text("Antes: " + blockers.joinToString { id -> allTasks?.find { it.id == id }?.title ?: "Tarea previa" },
          style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      MoreActions(task.title, busy, edit, delete, inspect = true)
    }
  }
}

@Composable
fun ProjectCard(project: Project, tasks: List<Task>, busy: Boolean, open: (() -> Unit)?, edit: () -> Unit, delete: () -> Unit,
  changeStatus: ((ProjectStatus) -> Unit)? = null) {
  val completed = tasks.count { it.completed }
  var statusMenu by remember { mutableStateOf(false) }
  OutlinedCard(Modifier.fillMaxWidth(), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        ColorMark(project.color)
        Text(project.name, Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.titleMedium)
        MoreActions(project.name, busy, edit, delete)
      }
      LinearProgressIndicator(progress = { if (tasks.isEmpty()) 0f else completed.toFloat() / tasks.size }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$completed de ${tasks.size} tareas completadas" }, color = Color(android.graphics.Color.parseColor(project.color)), trackColor = MaterialTheme.colorScheme.surfaceVariant)
      Text("$completed de ${tasks.size} tareas completadas", style = MaterialTheme.typography.bodySmall)
      FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
          OutlinedButton(enabled = !busy && changeStatus != null, onClick = { statusMenu = true }) { Text(project.status.label) }
          DropdownMenu(statusMenu, { statusMenu = false }) {
            ProjectStatus.entries.forEach { value -> DropdownMenuItem(text = { Text(value.label) }, onClick = { statusMenu = false; changeStatus?.invoke(value) }) }
          }
        }
        open?.let { TextButton(onClick = it) { Text("Ver tareas") } }
      }
    }
  }
}

@Composable
fun RoutineCard(record: RoutineRecord, today: LocalDate, busy: Boolean, complete: () -> Unit, edit: () -> Unit, activate: () -> Unit, delete: () -> Unit,
  manage: Boolean = true) {
  val routine = record.row.routine()
  val dates = record.completedDates()
  val streak = streaks(today, routine.active, routine.periods, dates)
  OutlinedCard(Modifier.fillMaxWidth(), border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface)) {
    Row(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
      if (routine.due(today)) Checkbox(today in dates, { complete() }, enabled = !busy, colors = CheckboxDefaults.colors(checkedColor = Color(android.graphics.Color.parseColor(routine.color))),
        modifier = Modifier.semantics { contentDescription = "${if (today in dates) "Desmarcar" else "Completar"} ${routine.title} hoy" })
      else Box(Modifier.padding(16.dp)) { ColorMark(routine.color) }
      Column(Modifier.weight(1f).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(routine.title, style = MaterialTheme.typography.titleSmall)
        Text("Racha actual: ${streak.current} días", style = MaterialTheme.typography.bodySmall)
        Text("Máxima: ${streak.maximum} días", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!routine.due(today)) Text(if (!routine.active) "En pausa" else "Empieza el ${Overview.relativeDate(routine.periods.lastOrNull()?.start, today)}", style = MaterialTheme.typography.bodySmall)
        if (manage) TextButton(enabled = !busy, onClick = activate) { Text(if (routine.active) "Pausar" else "Activar") }
      }
      if (manage) MoreActions(routine.title, busy, edit, delete)
    }
  }
}

@Composable
fun DeleteConfirmation(title: String, message: String, busy: Boolean, dismiss: () -> Unit, confirm: () -> Unit) {
  AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(title) }, text = { Text(message) },
    confirmButton = { TextButton(enabled = !busy, onClick = confirm) { Text("Eliminar") } },
    dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("Cancelar") } })
}
