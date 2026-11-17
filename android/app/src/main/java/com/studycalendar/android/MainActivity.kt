package com.studycalendar.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class CalendarViewModel(application: android.app.Application) : AndroidViewModel(application) {
  private val store = (application as CalendarApplication).store
  private val connection = (application as CalendarApplication).connection
  val cloud = connection.state
  val readOnly get() = cloud.value.session != null && cloud.value.snapshot?.protocol != 1
  val tasks = store.tasks
  private val preferences = application.getSharedPreferences("calendar-local", android.content.Context.MODE_PRIVATE)
  var localSettings by mutableStateOf(CalendarSettings(preferences.getInt("start", 0), preferences.getInt("end", 1440), preferences.getBoolean("sunday", false)))
    private set
  fun settings(value: CalendarSettings) {
    if (cloud.value.session != null) execute { connection.saveSettings(value) }
    else viewModelScope.launch {
      val saved = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { preferences.edit().putInt("start", value.start).putInt("end", value.end).putBoolean("sunday", value.sunday).commit() }
      if (saved) localSettings = value else error = "No se pudieron guardar los ajustes."
    }
  }
  val pending = store.pending
  val categories = store.categories
  val projects = store.projects
  val routines = store.routines
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
  fun execute(onSuccess: () -> Unit = {}, onFailure: () -> Unit = {}, action: suspend CalendarCommands.() -> Unit) {
    if (readOnly) { error = "El servidor todavía no tiene disponible el contrato de sincronización. Tu copia sigue disponible para consulta."; onFailure(); return }
    if (busy) { onFailure(); return }
    busy = true
    viewModelScope.launch {
      try {
        val commands: CalendarCommands = if (cloud.value.session == null) store else connection
        commands.action(); onSuccess()
        if (cloud.value.session != null) SyncScheduling.enqueue(getApplication())
      } catch (failure: Exception) {
        if (failure is kotlinx.coroutines.CancellationException) { onFailure(); throw failure }
        onFailure()
        error = if (failure is IllegalArgumentException) failure.message else "No se pudo guardar. Inténtalo de nuevo."
      } finally { busy = false }
    }
  }
}

class MainActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      StudyCalendarTheme { CalendarApp() }
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
  val cloud by model.cloud.collectAsStateWithLifecycle()
  key(cloud.session?.userId ?: "local") { CalendarContent(model) }
}

@Composable
private fun CalendarContent(model: CalendarViewModel) {
  val graphSessions: ProjectGraphSessions = viewModel()
  val calendarDrag = remember { CalendarDragController() }
  val rows by model.tasks.collectAsStateWithLifecycle(initialValue = null)
  val categoryRows by model.categories.collectAsStateWithLifecycle(initialValue = null)
  val projectRows by model.projects.collectAsStateWithLifecycle(initialValue = null)
  val localRoutines by model.routines.collectAsStateWithLifecycle(initialValue = null)
  val pending by model.pending.collectAsStateWithLifecycle(initialValue = null)
  val cloud by model.cloud.collectAsStateWithLifecycle()
  val habitDate = habitToday(cloud.snapshot?.reset ?: java.time.LocalTime.MIDNIGHT)
  val today = habitToday(java.time.LocalTime.MIDNIGHT)
  var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
  val date = selectedDate?.let(LocalDate::parse) ?: today
  var screen by rememberSaveable { mutableStateOf(Destination.TASKS) }
  var previousScreen by rememberSaveable { mutableStateOf(Destination.TASKS) }
  val owner = cloud.session?.userId ?: "local"
  val context = LocalContext.current
  val sectionPrefs = remember(owner) { context.getSharedPreferences("overview-$owner", 0) }
  var expanded by remember(owner) { mutableStateOf(sectionPrefs.getStringSet("expanded", setOf("daily", "projects", "habits"))!!.toSet()) }
  val toggleSection: (String) -> Unit = { name ->
    expanded = if (name in expanded) expanded - name else expanded + name
    sectionPrefs.edit().putStringSet("expanded", expanded).apply()
  }
  var settingsForm by remember { mutableStateOf(false) }
  val screenState = rememberSaveableStateHolder()
  val snackbar = remember { SnackbarHostState() }
  val scope = rememberCoroutineScope()
  var noticeJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
  fun notice(message: String, undo: () -> Unit) {
    noticeJob?.cancel()
    noticeJob = scope.launch {
      if (snackbar.showSnackbar(message, "Deshacer", withDismissAction = true, duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) undo()
    }
  }
  var selectedProject by rememberSaveable { mutableStateOf<String?>(null) }
  var editingTask by remember { mutableStateOf<Task?>(null) }
  var deletingTask by remember { mutableStateOf<Task?>(null) }
  var taskSeed by remember { mutableStateOf<Task?>(null) }
  var taskForm by remember { mutableStateOf(false) }
  var taskProject by remember { mutableStateOf<Project?>(null) }
  var editingCategory by remember { mutableStateOf<Category?>(null) }
  var deletingCategory by remember { mutableStateOf<Category?>(null) }
  var categoryForm by remember { mutableStateOf(false) }
  var editingProject by remember { mutableStateOf<Project?>(null) }
  var deletingProject by remember { mutableStateOf<Project?>(null) }
  var projectForm by remember { mutableStateOf(false) }
  var editingRoutine by remember { mutableStateOf<Routine?>(null) }
  var deletingRoutine by remember { mutableStateOf<Routine?>(null) }
  var routineForm by remember { mutableStateOf(false) }
  val tasks = (if (cloud.session == null) rows else cloud.snapshot?.tasks).orEmpty().map(TaskRow::task)
  val projects = (if (cloud.session == null) projectRows else cloud.snapshot?.projects).orEmpty().map(ProjectRow::project)
  val categories = (if (cloud.session == null) categoryRows else cloud.snapshot?.categories).orEmpty().map(CategoryRow::category)
  val routines = if (cloud.session == null) localRoutines else cloud.snapshot?.routines
  val project = projects.find { it.id == selectedProject }
  val graphPreferences = remember(owner, selectedProject) { ProjectGraphPreferences(context, owner, selectedProject.orEmpty()) }
  var graphView by remember(owner, selectedProject) { mutableStateOf(graphPreferences.graph) }
  LaunchedEffect(owner, selectedProject) { graphSessions.activate(owner, selectedProject) }
  val loaded = if (cloud.session != null) cloud.snapshot != null else rows != null && categoryRows != null && projectRows != null && routines != null && !cloud.working
  val pendingCount = if (loaded) Overview.pendingCount(tasks, projects, routines.orEmpty().count { it.row.routine().due(habitDate) && habitDate !in it.completedDates() }) else null
  val completeTask: (Task) -> Unit = { task ->
    model.execute(onSuccess = {
      notice(if (task.completed) "Tarea marcada como pendiente" else "Tarea completada") {
        model.execute { setCompletion(task.id, !task.completed, task.completed) }
      }
    }) { setCompletion(task.id, task.completed, !task.completed) }
  }
  val completeHabit: (RoutineRecord) -> Unit = { record ->
    val wasCompleted = habitDate in record.completedDates()
    model.execute(onSuccess = {
      notice(if (wasCompleted) "Rutina marcada como pendiente" else "Rutina completada") {
        model.execute { setRoutineCompletion(record.row.id, habitDate, !wasCompleted, wasCompleted) }
      }
    }) { setRoutineCompletion(record.row.id, habitDate, wasCompleted, !wasCompleted) }
  }
  val editTask: (Task) -> Unit = { taskSeed = null; editingTask = it; taskProject = null; taskForm = true }
  fun projectBack() {
    val state = project?.let { graphSessions.get(owner, it.id, graphPreferences) }
    if (!graphView || state?.back() != true) selectedProject = null
  }
  BackHandler(screen == Destination.PROJECTS && selectedProject != null) { projectBack() }

  BackHandler(screen == Destination.SETTINGS) { screen = previousScreen }
  fun openSettings() { previousScreen = screen; screen = Destination.SETTINGS }
  Scaffold(snackbarHost = { SnackbarHost(snackbar) }, topBar = {
    Surface(color = MaterialTheme.colorScheme.surface) {
      Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (screen == Destination.SETTINGS || (screen == Destination.PROJECTS && project != null)) {
          IconButton(onClick = { if (screen == Destination.SETTINGS) screen = previousScreen else projectBack() }) { Icon(appIcon("Atrás"), "Volver") }
        }
        Text("StudyCalendar", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        if (screen != Destination.SETTINGS) {
          TextButton(onClick = { openSettings() }) {
            Text(when {
              cloud.conflicts.isNotEmpty() -> "Revisar conflicto"
              cloud.error != null -> "Revisar conexión"
              cloud.working -> "Sincronizando…"
              cloud.session == null -> "Local"
              cloud.pending > 0 -> "${cloud.pending} pendientes"
              else -> "Al día"
            }, style = MaterialTheme.typography.labelSmall)
          }
          IconButton(onClick = { openSettings() }) { Icon(appIcon("Configuración"), "Configuración") }
        }
      }
    }
  }, bottomBar = {
    AppNavigation(if (screen == Destination.SETTINGS) previousScreen else screen, pendingCount) { destination -> screen = destination }
  }, floatingActionButton = {
    if (loaded && !model.busy && (!cloud.working || cloud.session != null) && !model.readOnly && !(screen == Destination.PROJECTS && project != null && project.status != ProjectStatus.ACTIVE)) {
      ExtendedFloatingActionButton(onClick = {
        when (screen) {
          Destination.SETTINGS -> { editingCategory = null; categoryForm = true }
          Destination.ROUTINES -> { editingRoutine = null; routineForm = true }
          Destination.PROJECTS -> if (project == null) { editingProject = null; projectForm = true }
            else { editingTask = null; taskSeed = null; taskProject = project; taskForm = true }
          else -> { editingTask = null; taskSeed = null; taskProject = null; taskForm = true }
        }
      }) { Text(when { screen == Destination.SETTINGS -> "Nueva categoría"; screen == Destination.ROUTINES -> "Nueva rutina"; screen == Destination.PROJECTS && project == null -> "Nuevo proyecto"; else -> "Nueva tarea" }) }
    }
  }) { padding ->
    BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
    val calendarHeight = maxHeight
    screenState.SaveableStateProvider(screen.name + (if (screen == Destination.PROJECTS) selectedProject.orEmpty() else "")) {
    val calendarList = rememberLazyListState()
    LazyColumn(state = calendarList, modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(bottom = 100.dp, top = 24.dp)) {
      item {
        Text(if (screen == Destination.TASKS) "Todas las tareas" else screen.label, style = MaterialTheme.typography.headlineLarge, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
      }
      if (screen == Destination.SETTINGS) item { AccountPanel(cloud, pending, model::login, model::refresh, model::logout, model::resolve, initiallyExpanded = true) }
      if (cloud.error != null && screen != Destination.SETTINGS) item {
        Text(cloud.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = !cloud.working, onClick = model::refresh) { Text("Reintentar") }
      }
      if (!loaded) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(if (cloud.error != null) "No se pudieron cargar los datos." else "Cargando tus datos…") }

      else when (screen) {
        Destination.TASKS -> taskOverview(tasks, projects, categories, routines.orEmpty(), today, habitDate, model.busy || model.readOnly,
          expanded, toggleSection, editTask, completeTask, { deletingTask = it }, completeHabit,
          newTask = { editingTask = null; taskSeed = null; taskProject = null; taskForm = true },
          newRoutine = { editingRoutine = null; routineForm = true },
          openProjects = { screen = Destination.PROJECTS }, openRoutines = { screen = Destination.ROUTINES })
        Destination.CALENDAR -> {
          item {
            CalendarScreen(tasks, projects, categories, today, date, { selectedDate = it.toString() },
              cloud.session?.userId ?: "local", cloud.snapshot?.calendar ?: model.localSettings,
              model.busy || model.readOnly, model::settings, scrollOuter = { amount -> calendarList.scrollBy(amount) },
              create = { day, minute ->
                taskSeed = CalendarLayout.draft(day, minute)
                editingTask = null; taskProject = null; taskForm = true
              }, edit = editTask, complete = completeTask,
              delete = { deletingTask = it }, move = { before, after, result -> model.execute(onSuccess = { result(true) }, onFailure = { result(false) }) { reschedule(before, after) } }, drag = calendarDrag, availableHeight = calendarHeight)
          }
        }

        Destination.PROJECTS -> {
          if (project != null) {
            item {
              ProjectCard(project, tasks.filter { it.projectId == project.id }, (model.busy || model.readOnly),
                open = null, edit = { editingProject = project; projectForm = true }, delete = { deletingProject = project }, changeStatus = { status -> model.execute { saveProject(project.copy(status = status)) } })
            }
            val associated = tasks.filter { it.projectId == project.id }
            item("project-view") { ProjectViewSwitch(graphView) { graphView = it; graphPreferences.graph = it } }
            if (graphView) {
              item("project-plan") {
                ProjectGraphView(associated, project, today, graphSessions.get(owner, project.id, graphPreferences), graphPreferences,
                  model.busy || model.readOnly, calendarHeight - 320.dp, editTask, completeTask, { deletingTask = it },
                  links = { task, desired, result ->
                    model.execute(onSuccess = { result(true) }, onFailure = { result(false) }) {
                      setPrerequisites(task.id, project.id, task.prerequisiteIds, desired)
                    }
                  })
              }
            } else {
              if (associated.isEmpty()) item { Text("Este proyecto todavía no tiene tareas.") }
              items(associated, key = { it.id }) { task ->
                TaskCard(task, project, categories.find { it.id == task.categoryId }, (model.busy || model.readOnly), complete = { completeTask(task) }, edit = { editTask(task) }, delete = { deletingTask = task }, today = today, allTasks = tasks)
              }
            }
          } else {
            if (projects.isEmpty()) item { Text("Crea un proyecto para organizar sus tareas y ver tu progreso.") }
            ProjectStatus.entries.forEach { status ->
              val group = projects.filter { it.status == status }
              if (group.isNotEmpty()) item { Text(when (status) { ProjectStatus.ACTIVE -> "Activos"; ProjectStatus.INACTIVE -> "Inactivos"; ProjectStatus.COMPLETED -> "Completados" }, style = MaterialTheme.typography.titleLarge) }
              items(group, key = { it.id }) { item ->
                ProjectCard(item, tasks.filter { it.projectId == item.id }, (model.busy || model.readOnly),
                  open = { selectedProject = item.id }, edit = { editingProject = item; projectForm = true }, delete = { deletingProject = item }, changeStatus = { status -> model.execute { saveProject(item.copy(status = status)) } })
              }
            }
            val unassigned = tasks.filter { it.projectTask && it.projectId == null }
            if (unassigned.isNotEmpty()) item { Text("Sin proyecto", style = MaterialTheme.typography.titleLarge) }
            items(unassigned, key = { it.id }) { task ->
              TaskCard(task, null, categories.find { it.id == task.categoryId }, (model.busy || model.readOnly), complete = { completeTask(task) }, edit = { editTask(task) }, delete = { deletingTask = task }, today = today, allTasks = tasks)
            }
          }
        }
        Destination.SETTINGS -> {
          item { TextButton(onClick = { settingsForm = true }) { Text("Ajustes del calendario") }; Text("Categorías", style = MaterialTheme.typography.titleLarge) }
          if (categories.isEmpty()) item { Text("Todavía no tienes categorías. Añade una para organizar tus tareas.") }
          items(categories, key = { it.id }) { category ->
            OutlinedCard(Modifier.fillMaxWidth()) {
              Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                ColorMark(category.color)
                Text(category.name, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.titleMedium)
                MoreActions(category.name, model.busy || model.readOnly, { editingCategory = category; categoryForm = true }, { deletingCategory = category })
              }
            }
          }
        }
        Destination.ROUTINES -> {
          val today = habitDate
          item {
            Text("Hoy · ${today.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))}", style = MaterialTheme.typography.titleLarge)
            Text("El día se renueva a las ${cloud.snapshot?.reset ?: java.time.LocalTime.MIDNIGHT} de Europe/Madrid. Las rutinas no aparecen en la agenda.")
          }
          val all = routines.orEmpty().sortedWith { a, b -> Overview.compareNames(a.row.title, b.row.title) }
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
                complete = { completeHabit(record) },
                edit = { editingRoutine = routine; routineForm = true },
                activate = { model.execute { saveRoutine(routine.id, routine.title, routine.color, !routine.active, today) } },
                delete = { deletingRoutine = routine })
            }
          }
        }
      }
    }
    }
    CalendarDragOverlay(calendarDrag)
    }
  }
  if (settingsForm) CalendarSettingsDialog(cloud.snapshot?.calendar ?: model.localSettings, model.busy || model.readOnly, { settingsForm = false }) { model.settings(it); settingsForm = false }
  if (taskForm) TaskForm(editingTask, today, taskProject, projects, categories, tasks, model.busy, model.readOnly, createProject = { value, success -> model.execute(onSuccess = success) { saveProject(value) } },
    seed = taskSeed, dismiss = { taskForm = false }, save = { task -> model.execute(onSuccess = { taskForm = false }) { save(task) } })
  if (categoryForm) CategoryForm(editingCategory, categories, (model.busy || model.readOnly), dismiss = { categoryForm = false }, save = { value ->
    model.execute(onSuccess = { categoryForm = false }) { saveCategory(value) }
  })
  deletingCategory?.let { value ->
    DeleteConfirmation("¿Eliminar categoría?", "Las tareas de «${value.name}» se conservarán sin categoría.", (model.busy || model.readOnly), { deletingCategory = null }) {
      model.execute(onSuccess = { deletingCategory = null }) { deleteCategory(value.id) }
    }
  }
  if (projectForm) ProjectForm(editingProject, (model.busy || model.readOnly), dismiss = { projectForm = false }, save = { value ->
    model.execute(onSuccess = { projectForm = false }) { saveProject(value) }
  })
  if (routineForm) RoutineForm(editingRoutine, habitDate, (model.busy || model.readOnly), dismiss = { routineForm = false }, save = { value, start ->
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
