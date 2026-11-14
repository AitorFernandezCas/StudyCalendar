package com.studycalendar.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
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
fun TaskCard(task: Task, project: Project?, busy: Boolean, complete: () -> Unit, edit: () -> Unit, delete: () -> Unit) {
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      project?.let { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ColorMark(it.color); Text(it.name, style = MaterialTheme.typography.labelMedium) } }
      Text(task.title, style = MaterialTheme.typography.titleMedium)
      Text(when { task.date == null -> "Sin fecha"; task.allDay -> "${task.date} · Todo el día"; task.start == null -> "${task.date} · Sin horario"; else -> "${task.date} · ${task.start} – ${task.end}" })
      Text(if (task.completed) "Completada" else "Pendiente", style = MaterialTheme.typography.labelMedium)
      if (task.prerequisiteIds.isNotEmpty()) Text("Tareas previas: ${task.prerequisiteIds.size}", style = MaterialTheme.typography.labelMedium)
      Row {
        TextButton(enabled = !busy, onClick = complete) { Text(if (task.completed) "Reabrir" else "Completar") }
        TextButton(enabled = !busy, onClick = edit) { Text("Editar") }
        TextButton(enabled = !busy, onClick = delete) { Text("Eliminar") }
      }
    }
  }
}

@Composable
fun ProjectCard(project: Project, tasks: List<Task>, busy: Boolean, open: (() -> Unit)?, edit: () -> Unit, delete: () -> Unit) {
  val completed = tasks.count { it.completed }
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      ColorMark(project.color)
      Text(project.name, style = MaterialTheme.typography.titleLarge)
      Text(project.status.label)
      LinearProgressIndicator(progress = { if (tasks.isEmpty()) 0f else completed.toFloat() / tasks.size }, modifier = Modifier.fillMaxWidth())
      Text("$completed de ${tasks.size} tareas completadas")
      Row {
        open?.let { TextButton(onClick = it) { Text("Ver tareas") } }
        TextButton(enabled = !busy, onClick = edit) { Text("Editar") }
        TextButton(enabled = !busy, onClick = delete) { Text("Eliminar") }
      }
    }
  }
}

@Composable
fun RoutineCard(record: RoutineRecord, today: LocalDate, busy: Boolean, complete: () -> Unit, edit: () -> Unit, activate: () -> Unit, delete: () -> Unit) {
  val routine = record.row.routine()
  val dates = record.completedDates()
  val streak = streaks(today, routine.active, routine.periods, dates)
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      ColorMark(routine.color)
      Text(routine.title, style = MaterialTheme.typography.titleLarge)
      Text("Racha actual: ${streak.current} días · Máxima: ${streak.maximum} días")
      if (routine.due(today)) {
        Text(if (today in dates) "Completada hoy" else "Pendiente de hoy")
        OutlinedButton(enabled = !busy, onClick = complete) { Text(if (today in dates) "Desmarcar hoy" else "Completar hoy") }
      } else if (routine.active) {
        routine.periods.lastOrNull()?.let { Text("Empieza el ${it.start}") }
      }
      Row {
        TextButton(enabled = !busy, onClick = activate) { Text(if (routine.active) "Pausar" else "Activar") }
        TextButton(enabled = !busy, onClick = edit) { Text("Editar") }
        TextButton(enabled = !busy, onClick = delete) { Text("Eliminar") }
      }
    }
  }
}

@Composable
fun DeleteConfirmation(title: String, message: String, busy: Boolean, dismiss: () -> Unit, confirm: () -> Unit) {
  AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(title) }, text = { Text(message) },
    confirmButton = { TextButton(enabled = !busy, onClick = confirm) { Text("Eliminar") } },
    dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("Cancelar") } })
}
