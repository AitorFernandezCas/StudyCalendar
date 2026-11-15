package com.studycalendar.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.studycalendar.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

class CalendarViewModel(application: android.app.Application) : AndroidViewModel(application) {
  private val store = (application as CalendarApplication).store
  private val connection = (application as CalendarApplication).connection
  val cloud = connection.state
  val readOnly get() = cloud.value.session != null && cloud.value.snapshot?.protocol != 1
  val tasks = combine(store.tasks, cloud) { local, remote -> if (remote.session == null) local else remote.snapshot?.tasks.orEmpty() }
  val pending = store.pending
  val projects = combine(store.projects, cloud) { local, remote -> if (remote.session == null) local else remote.snapshot?.projects.orEmpty() }
  val routines = combine(store.routines, cloud) { local, remote -> if (remote.session == null) local else remote.snapshot?.routines.orEmpty() }
  init { viewModelScope.launch { connection.restore(); if (cloud.value.session != null) { connection.refresh(); if (cloud.value.pending > 0) SyncScheduling.enqueue(application) } } }
  fun login(email: String, password: String) { viewModelScope.launch { connection.login(email, password) } }
  fun refresh() { viewModelScope.launch { connection.refresh() } }
  fun logout() { viewModelScope.launch { connection.logout() } }
  fun resolve(id: String, fields: Set<String>?) {
    viewModelScope.launch {
      try { connection.resolve(id, fields); SyncScheduling.enqueue(getApplication()) }
      catch (failure: Exception) {
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        error = failure.message ?: "No se pudo guardar la resolución. El cambio sigue pendiente."
      }
    }
  }
  var error by mutableStateOf<String?>(null)
    private set
  var busy by mutableStateOf(false)
    private set
  fun clearError() { error = null }
  fun execute(onSuccess: () -> Unit = {}, action: suspend CalendarCommands.() -> Unit) {
    if (readOnly) { error = "El servidor todavía no tiene disponible el contrato de sincronización. Tu copia sigue disponible para consulta."; return }
    if (busy) return
    busy = true
    viewModelScope.launch {
      try {
        val commands: CalendarCommands = if (cloud.value.session == null) store else connection
        commands.action(); onSuccess()
        if (cloud.value.session != null) SyncScheduling.enqueue(getApplication())
      } catch (failure: Exception) {
        if (failure is kotlinx.coroutines.CancellationException) throw failure
        error = if (failure is IllegalArgumentException) failure.message else "No se pudo guardar. Inténtalo de nuevo."
      } finally { busy = false }
    }
  }
}

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      val colors = lightColorScheme(
        primary = Color(0xFF7056ED), onPrimary = Color.White,
        primaryContainer = Color(0xFFF0EDFF), onPrimaryContainer = Color(0xFF543BCB),
        background = Color(0xFFF7F8FC), onBackground = Color(0xFF252D3D),
        surface = Color.White, onSurface = Color(0xFF252D3D),
        surfaceVariant = Color(0xFFF3F4F9), onSurfaceVariant = Color(0xFF747B89),
        outline = Color(0xFFE3E5EB)
      )
      MaterialTheme(colorScheme = colors) { CalendarApp() }
    }
  }
}

@Composable
private fun habitToday(reset: java.time.LocalTime): LocalDate {
  val lifecycle = LocalLifecycleOwner.current.lifecycle
  val today by produceState(HabitClock.day(Instant.now(), reset).date, lifecycle, reset) {
    lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      while (true) {
        val now = Instant.now()
        val day = HabitClock.day(now, reset)
        value = day.date
        // Refresh at the boundary, on resume, and after wall-clock changes.
        delay(java.time.Duration.between(now, day.endsAt).toMillis().coerceIn(100, 60_000))
      }
    }
  }
  return today
}

@Composable
private fun CalendarApp(model: CalendarViewModel = viewModel()) {
  val rows by model.tasks.collectAsStateWithLifecycle(initialValue = null)
  val projectRows by model.projects.collectAsStateWithLifecycle(initialValue = null)
  val routines by model.routines.collectAsStateWithLifecycle(initialValue = null)
  val pending by model.pending.collectAsStateWithLifecycle(initialValue = null)
  val cloud by model.cloud.collectAsStateWithLifecycle()
  val habitDate = habitToday(cloud.snapshot?.reset ?: java.time.LocalTime.MIDNIGHT)
  val today = habitToday(java.time.LocalTime.MIDNIGHT)
  var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
  val date = selectedDate?.let(LocalDate::parse) ?: today
  var screen by rememberSaveable { mutableStateOf("Agenda") }
  var selectedProject by rememberSaveable { mutableStateOf<String?>(null) }
  var editingTask by remember { mutableStateOf<Task?>(null) }
  var deletingTask by remember { mutableStateOf<Task?>(null) }
  var taskForm by remember { mutableStateOf(false) }
  var taskProject by remember { mutableStateOf<Project?>(null) }
  var editingProject by remember { mutableStateOf<Project?>(null) }
  var deletingProject by remember { mutableStateOf<Project?>(null) }
  var projectForm by remember { mutableStateOf(false) }
  var editingRoutine by remember { mutableStateOf<Routine?>(null) }
  var deletingRoutine by remember { mutableStateOf<Routine?>(null) }
  var routineForm by remember { mutableStateOf(false) }
  val tasks = rows.orEmpty().map(TaskRow::task)
  val projects = projectRows.orEmpty().map(ProjectRow::project)
  val project = projects.find { it.id == selectedProject }
  val loaded = rows != null && projectRows != null && routines != null && (cloud.session == null || cloud.snapshot != null)
  val editTask: (Task) -> Unit = { editingTask = it; taskProject = null; taskForm = true }
  BackHandler(screen == "Proyectos" && selectedProject != null) { selectedProject = null }

  Scaffold(topBar = {
    if (screen == "Proyectos" && project != null) {
      Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Box(Modifier.statusBarsPadding()) {
        TextButton(onClick = { selectedProject = null }, modifier = Modifier.fillMaxWidth()) {
          Text("‹  Volver a proyectos")
        }
        }
      }
    }
  }, bottomBar = {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
      listOf("Agenda" to "▦", "Proyectos" to "▤", "Rutinas" to "✓").forEach { (name, symbol) ->
        NavigationBarItem(selected = screen == name, onClick = {
          if (name == "Proyectos") selectedProject = null
          screen = name
        },
          icon = { Text(symbol) }, label = { Text(name) })
      }
    }
  }, floatingActionButton = {
    if (loaded && (!cloud.working || cloud.session != null) && !model.readOnly && !(screen == "Proyectos" && project != null && project.status != ProjectStatus.ACTIVE)) {
      ExtendedFloatingActionButton(onClick = {
        when (screen) {
          "Rutinas" -> { editingRoutine = null; routineForm = true }
          "Proyectos" -> if (project == null) { editingProject = null; projectForm = true }
            else { editingTask = null; taskProject = project; taskForm = true }
          else -> { editingTask = null; taskProject = null; taskForm = true }
        }
      }) { Text(when { screen == "Rutinas" -> "Nueva rutina"; screen == "Proyectos" && project == null -> "Nuevo proyecto"; else -> "Nueva tarea" }) }
    }
  }) { padding ->
    LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 100.dp, top = 24.dp)) {
      item {
        Text("StudyCalendar", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        Text(if (screen == "Agenda") "Calendario" else screen, style = MaterialTheme.typography.headlineLarge, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
        Text(when (screen) { "Agenda" -> "Organiza tu día"; "Proyectos" -> "Tus proyectos y su progreso"; else -> "Tus hábitos diarios" }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
      }
      item { AccountPanel(cloud, pending, model::login, model::refresh, model::logout, model::resolve) }
      if (!loaded) item { Text("Cargando tus datos…") }
      else when (screen) {
        "Agenda" -> {
          item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              TextButton(onClick = { selectedDate = date.minusDays(1).toString() }) { Text("Anterior") }
              TextButton(onClick = { selectedDate = null }) { Text("Hoy") }
              TextButton(onClick = { selectedDate = date.plusDays(1).toString() }) { Text("Siguiente") }
            }
            Text(date.format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", Locale.forLanguageTag("es"))), style = MaterialTheme.typography.titleLarge)
          }
          val daily = tasks.filter { it.date == date }
          if (daily.isEmpty()) item { Text("No tienes tareas para este día.") }
          items(daily, key = { it.id }) { task ->
            TaskCard(task, projects.find { it.id == task.projectId }, (model.busy || model.readOnly),
              complete = { model.execute { complete(task.id) } }, edit = { editTask(task) }, delete = { deletingTask = task })
          }
        }
        "Proyectos" -> {
          if (project != null) {
            item {
              ProjectCard(project, tasks.filter { it.projectId == project.id }, (model.busy || model.readOnly),
                open = null, edit = { editingProject = project; projectForm = true }, delete = { deletingProject = project })
            }
            val associated = tasks.filter { it.projectId == project.id }
            if (associated.isEmpty()) item { Text("Este proyecto todavía no tiene tareas.") }
            items(associated, key = { it.id }) { task ->
              TaskCard(task, project, (model.busy || model.readOnly), complete = { model.execute { complete(task.id) } }, edit = { editTask(task) }, delete = { deletingTask = task })
            }
          } else {
            if (projects.isEmpty()) item { Text("Crea un proyecto para organizar sus tareas y ver tu progreso.") }
            ProjectStatus.entries.forEach { status ->
              val group = projects.filter { it.status == status }
              if (group.isNotEmpty()) item { Text(when (status) { ProjectStatus.ACTIVE -> "Activos"; ProjectStatus.INACTIVE -> "Inactivos"; ProjectStatus.COMPLETED -> "Completados" }, style = MaterialTheme.typography.titleLarge) }
              items(group, key = { it.id }) { item ->
                ProjectCard(item, tasks.filter { it.projectId == item.id }, (model.busy || model.readOnly),
                  open = { selectedProject = item.id }, edit = { editingProject = item; projectForm = true }, delete = { deletingProject = item })
              }
            }
            val unassigned = tasks.filter { it.projectTask && it.projectId == null }
            if (unassigned.isNotEmpty()) item { Text("Sin proyecto", style = MaterialTheme.typography.titleLarge) }
            items(unassigned, key = { it.id }) { task ->
              TaskCard(task, null, (model.busy || model.readOnly), complete = { model.execute { complete(task.id) } }, edit = { editTask(task) }, delete = { deletingTask = task })
            }
          }
        }
        "Rutinas" -> {
          val today = habitDate
          item {
            Text("Hoy · ${today.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}", style = MaterialTheme.typography.titleLarge)
            Text("El día se renueva a las ${cloud.snapshot?.reset ?: java.time.LocalTime.MIDNIGHT} de Europe/Madrid. Las rutinas no aparecen en la agenda.")
          }
          val all = routines.orEmpty()
          if (all.isEmpty()) item { Text("Añade tu primer hábito diario y empieza a registrar tu racha.") }
          val groups = listOf(
            "Pendientes de hoy" to all.filter { it.row.routine().due(today) && today !in it.completedDates() },
            "Completadas hoy" to all.filter { it.row.routine().due(today) && today in it.completedDates() },
            "Próximas" to all.filter { it.row.active && !it.row.routine().due(today) },
            "Inactivas" to all.filter { !it.row.active },
          )
          groups.forEach { (label, group) ->
            if (group.isNotEmpty()) item { Text("$label (${group.size})", style = MaterialTheme.typography.titleMedium) }
            items(group, key = { it.row.id }) { record ->
              val routine = record.row.routine()
              RoutineCard(record, today, (model.busy || model.readOnly),
                complete = { model.execute { completeRoutine(routine.id, today, today !in record.completedDates()) } },
                edit = { editingRoutine = routine; routineForm = true },
                activate = { model.execute { saveRoutine(routine.id, routine.title, routine.color, !routine.active, today) } },
                delete = { deletingRoutine = routine })
            }
          }
        }
      }
    }
  }
  if (taskForm) TaskForm(editingTask, today, taskProject, projects, tasks, model,
    dismiss = { taskForm = false }, save = { task -> model.execute(onSuccess = { taskForm = false }) { save(task) } })
  if (projectForm) ProjectForm(editingProject, (model.busy || model.readOnly), dismiss = { projectForm = false }, save = { value ->
    model.execute(onSuccess = { projectForm = false }) { saveProject(value) }
  })
  if (routineForm) RoutineForm(editingRoutine, today, (model.busy || model.readOnly), dismiss = { routineForm = false }, save = { value, start ->
    model.execute(onSuccess = { routineForm = false }) { saveRoutine(value.id, value.title, value.color, value.active, start) }
  })
  deletingTask?.let { task ->
    DeleteConfirmation("¿Eliminar tarea?", task.title, (model.busy || model.readOnly), { deletingTask = null }) {
      model.execute(onSuccess = { deletingTask = null }) { delete(task.id) }
    }
  }
  deletingProject?.let { value ->
    DeleteConfirmation("¿Eliminar proyecto?", "Se eliminarán «${value.name}» y sus ${tasks.count { it.projectId == value.id }} tareas, incluidas las completadas y sus dependencias. Esta acción no se puede deshacer.", (model.busy || model.readOnly), { deletingProject = null }) {
      model.execute(onSuccess = { deletingProject = null; selectedProject = null }) { deleteProject(value.id) }
    }
  }
  deletingRoutine?.let { value ->
    DeleteConfirmation("¿Eliminar rutina?", "Se eliminarán «${value.title}» y todo su historial de completados. Puedes pausarla para conservarlo.", (model.busy || model.readOnly), { deletingRoutine = null }) {
      model.execute(onSuccess = { deletingRoutine = null }) { deleteRoutine(value.id) }
    }
  }
  model.error?.let { message ->
    AlertDialog(onDismissRequest = model::clearError, title = { Text("No se pudo guardar") }, text = { Text(message) },
      confirmButton = { TextButton(onClick = model::clearError) { Text("Aceptar") } })
  }
}

