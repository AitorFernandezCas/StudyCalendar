package com.studycalendar.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.studycalendar.core.*
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Composable
private fun Choice(label: String, checked: Boolean, enabled: Boolean = true, change: (Boolean) -> Unit) {
  Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = change).padding(vertical = 4.dp)) {
    Checkbox(checked, onCheckedChange = null, enabled = enabled)
    Text(label, Modifier.padding(start = 8.dp, top = 12.dp))
  }
}

@Composable
private fun ColorPicker(value: String, enabled: Boolean = true, change: (String) -> Unit) {
  var expanded by remember { mutableStateOf(false) }
  val colors = listOf("#4F46E5" to "Índigo", "#16A085" to "Verde", "#2563EB" to "Azul", "#D97706" to "Ámbar", "#DB2777" to "Rosa", "#DC2626" to "Rojo")
  Box {
    TextButton(enabled = enabled, onClick = { expanded = true }) {
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
fun CategoryForm(existing: Category?, categories: List<Category>, busy: Boolean, dismiss: () -> Unit, save: (Category) -> Unit) {
  val id = rememberSaveable { existing?.id ?: UUID.randomUUID().toString() }
  var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
  var color by rememberSaveable { mutableStateOf(existing?.color ?: "#4F46E5") }
  var error by remember { mutableStateOf<String?>(null) }
  AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text(if (existing == null) "Nueva categoría" else "Editar categoría") }, text = {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      OutlinedTextField(name, { name = it; error = null }, enabled = !busy, modifier = Modifier.fillMaxWidth(), label = { Text("Nombre") }, singleLine = true)
      ColorPicker(color, !busy) { color = it }
      error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
  }, confirmButton = { TextButton(enabled = !busy, onClick = {
    try { val category = Category(id, name.trim(), color); category.validate(categories); save(category) }
    catch (failure: IllegalArgumentException) { error = failure.message }
  }) { Text(if (busy) "Guardando…" else "Guardar") } }, dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("Cancelar") } })
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
      OutlinedTextField(name, { name = it; error = null }, enabled = !busy, label = { Text("Nombre") }, singleLine = true)
      ColorPicker(color, !busy) { color = it }
      Text("Estado", style = MaterialTheme.typography.titleSmall)
      Column(Modifier.selectableGroup()) {
        ProjectStatus.entries.forEach { choice ->
          Row(Modifier.fillMaxWidth().selectable(status == choice, enabled = !busy, role = Role.RadioButton, onClick = { status = choice }), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(status == choice, onClick = null, enabled = !busy)
            Text(choice.label, Modifier.padding(start = 8.dp))
          }
        }
      }
      Text("Cambiar el estado conserva las tareas y su historial. Completar el proyecto no completa sus tareas.")
      error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
  }, confirmButton = { TextButton(enabled = !busy, onClick = {
    try { val project = Project(id, name.trim(), color, status); project.validate(emptyList()); save(project) }
    catch (failure: IllegalArgumentException) { error = failure.message }
  }) { Text(if (busy) "Guardando…" else "Guardar") } }, dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("Cancelar") } })
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
      OutlinedTextField(title, { title = it; error = null }, enabled = !busy, label = { Text("Título") }, singleLine = true)
      ColorPicker(color, !busy) { color = it }
      Choice("Activa", active, !busy) { active = it }
      if (existing == null) {
        DateField("Fecha inicial", LocalDate.parse(start), !busy) { start = it.toString(); error = null }
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
  }) { Text(if (busy) "Guardando…" else "Guardar") } }, dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("Cancelar") } })
}

@Composable
fun TaskForm(existing: Task?, date: LocalDate, initialProject: Project?, projects: List<Project>, categories: List<Category>, tasks: List<Task>, busy: Boolean, readOnly: Boolean = false, createProject: (Project, () -> Unit) -> Unit, seed: Task? = null, dismiss: () -> Unit, save: (Task) -> Unit) {
  val id = rememberSaveable { existing?.id ?: UUID.randomUUID().toString() }
  var title by rememberSaveable { mutableStateOf(existing?.title ?: "") }
  var day by rememberSaveable { mutableStateOf((existing?.date ?: seed?.date ?: date).toString()) }
  var projectTask by rememberSaveable { mutableStateOf(existing?.projectTask ?: (initialProject != null)) }
  var categoryId by rememberSaveable { mutableStateOf(existing?.categoryId) }
  var categoryMenu by remember { mutableStateOf(false) }
  var projectId by rememberSaveable { mutableStateOf(existing?.projectId ?: initialProject?.id) }
  var undated by rememberSaveable { mutableStateOf(existing?.let { it.date == null } ?: (initialProject != null)) }
  var allDay by rememberSaveable { mutableStateOf(existing?.allDay ?: seed?.allDay ?: true) }
  var start by rememberSaveable { mutableStateOf(existing?.start?.toString() ?: seed?.start?.toString() ?: "09:00") }
  var end by rememberSaveable { mutableStateOf(existing?.end?.toString() ?: seed?.end?.toString() ?: "10:00") }
  var prerequisiteIds by rememberSaveable { mutableStateOf(existing?.prerequisiteIds?.toList() ?: emptyList()) }
  var error by remember { mutableStateOf<String?>(null) }
  var projectMenu by remember { mutableStateOf(false) }
  var projectForm by remember { mutableStateOf(false) }
  Dialog(onDismissRequest = { if (!busy) dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(Modifier.fillMaxWidth().fillMaxHeight().systemBarsPadding().imePadding(), color = MaterialTheme.colorScheme.surface) {
      Column {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 16.dp)) {
          Text(if (existing == null) "Nueva tarea" else "Editar tarea", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
          Text("Organiza los detalles de tu tarea", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        val fieldColors = OutlinedTextFieldDefaults.colors(
          focusedBorderColor = MaterialTheme.colorScheme.primary,
          unfocusedBorderColor = Color(0xFF737B8C),
          focusedLabelColor = MaterialTheme.colorScheme.primary,
          unfocusedLabelColor = Color(0xFF515A6A),
          focusedContainerColor = Color.White,
          unfocusedContainerColor = Color.White
        )
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          OutlinedTextField(title, { title = it; error = null }, enabled = !busy && !readOnly, modifier = Modifier.fillMaxWidth(), label = { Text("Título") }, singleLine = true, colors = fieldColors, shape = RoundedCornerShape(10.dp))
          Box(Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { categoryMenu = true }, enabled = !busy && !readOnly, modifier = Modifier.fillMaxWidth()) {
              Text("Categoría: " + (categories.find { it.id == categoryId }?.name ?: "Sin categoría"))
            }
            DropdownMenu(categoryMenu, { categoryMenu = false }) {
              DropdownMenuItem(text = { Text("Sin categoría") }, onClick = { categoryId = null; categoryMenu = false })
              categories.forEach { category -> DropdownMenuItem(text = { Text(category.name) }, leadingIcon = { ColorMark(category.color) }, onClick = { categoryId = category.id; categoryMenu = false }) }
            }
          }
          Choice("Tarea de proyecto", projectTask, !busy && !readOnly) { projectTask = it; if (!it) { projectId = null; undated = false } }
          if (projectTask) {
            Box(Modifier.fillMaxWidth()) {
              val selected = projects.find { it.id == projectId }
              OutlinedButton(enabled = !busy && !readOnly, onClick = { projectMenu = true }, modifier = Modifier.fillMaxWidth()) { Text(selected?.let { "${it.name} · ${it.status.label}" } ?: "Sin proyecto") }
              DropdownMenu(projectMenu, { projectMenu = false }) {
                DropdownMenuItem(text = { Text("Sin proyecto") }, onClick = { projectId = null; projectMenu = false })
                projects.filter { it.status == ProjectStatus.ACTIVE || it.id == existing?.projectId }.forEach { project ->
                  DropdownMenuItem(text = { Text("${project.name} · ${project.status.label}") }, onClick = { projectId = project.id; projectMenu = false })
                }
              }
            }
            TextButton(enabled = !busy && !readOnly, onClick = { projectForm = true }) { Text("Crear proyecto") }
            Choice("Sin fecha", undated, !busy && !readOnly) { undated = it }
          }
          if (!undated) {
            DateField("Fecha", LocalDate.parse(day), !busy && !readOnly) { day = it.toString(); error = null }
            Choice("Todo el día", allDay, !busy && !readOnly) { allDay = it }
            if (!allDay) {
              TimeField("Hora de inicio", LocalTime.parse(start), !busy && !readOnly) { time ->
                end = Overview.endWithDuration(time, LocalTime.parse(start), LocalTime.parse(end)).toString()
                start = time.toString(); error = null
              }
              TimeField("Hora de fin", LocalTime.parse(end), !busy && !readOnly) { end = it.toString(); error = null }

            }
          }
          val candidates = tasks.filter { it.id != id && ((projectId != null && it.projectId == projectId) || it.id in prerequisiteIds) }
          if (candidates.isNotEmpty() && (projectTask || prerequisiteIds.isNotEmpty())) {
            Text("Tareas previas", style = MaterialTheme.typography.titleSmall)
            Text("Deben estar completadas antes de completar esta tarea.")
            candidates.forEach { task ->
              Choice(task.title, task.id in prerequisiteIds, !busy && !readOnly) { checked -> prerequisiteIds = if (checked) prerequisiteIds + task.id else prerequisiteIds - task.id }
            }
          }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
          error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp)) }
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(enabled = !busy, onClick = dismiss) { Text(if (readOnly) "Cerrar" else "Cancelar") }
            Spacer(Modifier.width(8.dp))
            Button(enabled = !busy && !readOnly, onClick = {
              try {
                val task = Task(id = id, title = title.trim(), date = if (undated) null else LocalDate.parse(day),
                  allDay = !undated && allDay, start = if (undated) null else if (allDay) existing?.start ?: LocalTime.of(9, 0) else LocalTime.parse(start),
                  end = if (undated) null else if (allDay) existing?.end ?: LocalTime.of(10, 0) else LocalTime.parse(end),
                  completed = existing?.completed ?: false, projectTask = projectTask, projectId = projectId, prerequisiteIds = prerequisiteIds.toSet(), categoryId = categoryId)
                task.validate()
                save(task)
              } catch (_: java.time.format.DateTimeParseException) { error = "Revisa la fecha y las horas." }
              catch (failure: IllegalArgumentException) { error = failure.message ?: "Revisa los datos." }
            }) { Text(if (busy) "Guardando…" else "Guardar") }
          }
        }
      }
    }
  }
  if (projectForm) ProjectForm(null, busy, dismiss = { projectForm = false }, save = { project ->
    createProject(project) { projectId = project.id; projectForm = false }
  })
}
