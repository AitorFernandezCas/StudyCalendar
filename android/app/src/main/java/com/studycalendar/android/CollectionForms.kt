package com.studycalendar.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.studycalendar.core.*
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Composable
private fun Choice(label: String, checked: Boolean, change: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Checkbox, onValueChange = change).padding(vertical = 4.dp)) {
    Checkbox(checked, onCheckedChange = null)
    Text(label, Modifier.padding(start = 8.dp, top = 12.dp))
  }
}

@Composable
private fun ColorPicker(value: String, change: (String) -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  val colors = listOf("#4F46E5" to "Índigo", "#16A085" to "Verde", "#2563EB" to "Azul", "#D97706" to "Ámbar", "#DB2777" to "Rosa", "#DC2626" to "Rojo")
  Box {
    TextButton(onClick = { expanded = true }) {
      ColorMark(value)
      Spacer(Modifier.width(8.dp))
      Text("Color: ${colors.find { it.first == value }?.second ?: value}")
    }
    DropdownMenu(expanded, { expanded = false }) {
      colors.forEach { (color, name) -> DropdownMenuItem(text = { Text(name) }, leadingIcon = { ColorMark(color) }, onClick = { change(color); expanded = false }) }
    }
  }
}

@Composable
fun ProjectForm(existing: Project?, busy: Boolean, dismiss: () -> Unit, save: (Project) -> Unit) {
  val id = rememberSaveable { existing?.id ?: UUID.randomUUID().toString() }
  var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
  var color by rememberSaveable { mutableStateOf(existing?.color ?: "#4F46E5") }
  var status by rememberSaveable { mutableStateOf(existing?.status ?: ProjectStatus.ACTIVE) }
  var error by remember { mutableStateOf<String?>(null) }
  AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(if (existing == null) "Nuevo proyecto" else "Editar proyecto") }, text = {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(name, { name = it }, label = { Text("Nombre") }, singleLine = true)
      ColorPicker(color) { color = it }
      Text("Estado", style = MaterialTheme.typography.titleSmall)
      ProjectStatus.entries.forEach { choice ->
        Choice(choice.label, status == choice) { status = choice }
      }
      Text("Cambiar el estado conserva las tareas y su historial. Completar el proyecto no completa sus tareas.")
      error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
  }, confirmButton = { TextButton(enabled = !busy, onClick = {
    try { val project = Project(id, name.trim(), color, status); project.validate(emptyList()); save(project) }
    catch (failure: IllegalArgumentException) { error = failure.message }
  }) { Text("Guardar") } }, dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("Cancelar") } })
}

@Composable
fun RoutineForm(existing: Routine?, today: LocalDate, busy: Boolean, dismiss: () -> Unit, save: (Routine, LocalDate) -> Unit) {
  val id = rememberSaveable { existing?.id ?: UUID.randomUUID().toString() }
  var title by rememberSaveable { mutableStateOf(existing?.title ?: "") }
  var color by rememberSaveable { mutableStateOf(existing?.color ?: "#16A085") }
  var active by rememberSaveable { mutableStateOf(existing?.active ?: true) }
  var start by rememberSaveable { mutableStateOf(today.toString()) }
  var error by remember { mutableStateOf<String?>(null) }
  AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(if (existing == null) "Nueva rutina" else "Editar rutina") }, text = {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(title, { title = it }, label = { Text("Título") }, singleLine = true)
      ColorPicker(color) { color = it }
      Choice("Activa", active) { active = it }
      if (existing == null) {
        OutlinedTextField(start, { start = it }, label = { Text("Inicio (AAAA-MM-DD)") }, singleLine = true)
        if (!active) Text("Al activarla más adelante, empezará ese día.")
      }
      Text("Hábito diario, sin horario. Pausarlo conserva las fechas completadas.")
      error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
  }, confirmButton = { TextButton(enabled = !busy, onClick = {
    try {
      val day = LocalDate.parse(start)
      val routine = Routine(id, title.trim(), color, active)
      routine.validate()
      save(routine, day)
    } catch (_: java.time.format.DateTimeParseException) { error = "Usa una fecha válida: AAAA-MM-DD." }
    catch (failure: IllegalArgumentException) { error = failure.message }
  }) { Text("Guardar") } }, dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("Cancelar") } })
}

@Composable
fun TaskForm(existing: Task?, date: LocalDate, initialProject: Project?, projects: List<Project>, tasks: List<Task>, model: CalendarViewModel, dismiss: () -> Unit, save: (Task) -> Unit) {
  val id = rememberSaveable { existing?.id ?: UUID.randomUUID().toString() }
  var title by rememberSaveable { mutableStateOf(existing?.title ?: "") }
  var day by rememberSaveable { mutableStateOf((existing?.date ?: date).toString()) }
  var projectTask by rememberSaveable { mutableStateOf(existing?.projectTask ?: (initialProject != null)) }
  var projectId by rememberSaveable { mutableStateOf(existing?.projectId ?: initialProject?.id) }
  var undated by rememberSaveable { mutableStateOf(existing?.let { it.date == null } ?: (initialProject != null)) }
  var allDay by rememberSaveable { mutableStateOf(existing?.allDay ?: true) }
  var start by rememberSaveable { mutableStateOf(existing?.start?.toString() ?: "09:00") }
  var end by rememberSaveable { mutableStateOf(existing?.end?.toString() ?: "10:00") }
  var prerequisiteIds by remember { mutableStateOf(existing?.prerequisiteIds ?: emptySet()) }
  var error by remember { mutableStateOf<String?>(null) }
  var projectMenu by remember { mutableStateOf(false) }
  var createProject by remember { mutableStateOf(false) }
  AlertDialog(onDismissRequest = { if (!model.busy) dismiss() }, title = { Text(if (existing == null) "Nueva tarea" else "Editar tarea") }, text = {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(title, { title = it }, label = { Text("Título") }, singleLine = true)
      Choice("Tarea de proyecto", projectTask) { projectTask = it; if (!it) { projectId = null; undated = false } }
      if (projectTask) {
        Box {
          val selected = projects.find { it.id == projectId }
          OutlinedButton(onClick = { projectMenu = true }) { Text(selected?.let { "${it.name} · ${it.status.label}" } ?: "Sin proyecto") }
          DropdownMenu(projectMenu, { projectMenu = false }) {
            DropdownMenuItem(text = { Text("Sin proyecto") }, onClick = { projectId = null; projectMenu = false })
            projects.filter { it.status == ProjectStatus.ACTIVE || it.id == existing?.projectId }.forEach { project ->
              DropdownMenuItem(text = { Text("${project.name} · ${project.status.label}") }, onClick = { projectId = project.id; projectMenu = false })
            }
          }
        }
        TextButton(enabled = !model.busy, onClick = { createProject = true }) { Text("Crear proyecto") }
        Choice("Sin fecha", undated) { undated = it }
      }
      if (!undated) {
        OutlinedTextField(day, { day = it }, label = { Text("Fecha (AAAA-MM-DD)") }, singleLine = true)
        Choice("Todo el día", allDay) { allDay = it }
        if (!allDay) {
          OutlinedTextField(start, { start = it }, label = { Text("Inicio (HH:MM)") }, singleLine = true)
          OutlinedTextField(end, { end = it }, label = { Text("Fin (HH:MM)") }, singleLine = true)
        }
      }
      val candidates = tasks.filter { it.id != id && ((projectId != null && it.projectId == projectId) || it.id in prerequisiteIds) }
      if (candidates.isNotEmpty() && (projectTask || prerequisiteIds.isNotEmpty())) {
        Text("Tareas previas", style = MaterialTheme.typography.titleSmall)
        Text("Deben estar completadas antes de completar esta tarea.")
        candidates.forEach { task ->
          Choice(task.title, task.id in prerequisiteIds) { checked -> prerequisiteIds = if (checked) prerequisiteIds + task.id else prerequisiteIds - task.id }
        }
      }
      error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
  }, confirmButton = {
    TextButton(enabled = !model.busy, onClick = {
      try {
        val task = Task(id = id, title = title.trim(), date = if (undated) null else LocalDate.parse(day),
          allDay = !undated && allDay, start = if (undated) null else if (allDay) existing?.start ?: LocalTime.of(9, 0) else LocalTime.parse(start),
          end = if (undated) null else if (allDay) existing?.end ?: LocalTime.of(10, 0) else LocalTime.parse(end),
          completed = existing?.completed ?: false, projectTask = projectTask, projectId = projectId, prerequisiteIds = prerequisiteIds)
        task.validate()
        save(task)
      } catch (_: java.time.format.DateTimeParseException) { error = "Revisa la fecha y las horas." }
      catch (failure: IllegalArgumentException) { error = failure.message ?: "Revisa los datos." }
    }) { Text("Guardar") }
  }, dismissButton = { TextButton(enabled = !model.busy, onClick = dismiss) { Text("Cancelar") } })
  if (createProject) ProjectForm(null, model.busy, dismiss = { createProject = false }, save = { project ->
    model.execute(onSuccess = { projectId = project.id; createProject = false }) { saveProject(project) }
  })
}
