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
import androidx.compose.foundation.shape.RoundedCornerShape
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
  val features = store.features
  val featureLinks = store.featureLinks
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
  val featureRows by model.features.collectAsStateWithLifecycle(initialValue = null)
  val featureLinkRows by model.featureLinks.collectAsStateWithLifecycle(initialValue = null)
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
  var selectedGroup by rememberSaveable(selectedProject) { mutableStateOf<String?>(null) }
  var featureGraph by rememberSaveable(selectedProject) { mutableStateOf(false) }
  var editingTask by remember { mutableStateOf<Task?>(null) }
  var deletingTask by remember { mutableStateOf<Task?>(null) }
  var taskSeed by remember { mutableStateOf<Task?>(null) }
  var taskForm by remember { mutableStateOf(false) }
  var taskProject by remember { mutableStateOf<Project?>(null) }
  var taskFeatureId by remember { mutableStateOf<String?>(null) }
  var editingCategory by remember { mutableStateOf<Category?>(null) }
  var deletingCategory by remember { mutableStateOf<Category?>(null) }
  var categoryForm by remember { mutableStateOf(false) }
  var editingProject by remember { mutableStateOf<Project?>(null) }
  var deletingProject by remember { mutableStateOf<Project?>(null) }
  var projectForm by remember { mutableStateOf(false) }
  var editingFeature by remember { mutableStateOf<ProjectFeature?>(null) }
  var deletingFeature by remember { mutableStateOf<ProjectFeature?>(null) }
  var featureForm by remember { mutableStateOf(false) }
  var editingRoutine by remember { mutableStateOf<Routine?>(null) }
  var deletingRoutine by remember { mutableStateOf<Routine?>(null) }
  var routineForm by remember { mutableStateOf(false) }
  val tasks = (if (cloud.session == null) rows else cloud.snapshot?.tasks).orEmpty().map(TaskRow::task)
  val projects = (if (cloud.session == null) projectRows else cloud.snapshot?.projects).orEmpty().map(ProjectRow::project)
  val features = (if (cloud.session == null) featureRows else cloud.snapshot?.features)?.map(ProjectFeatureRow::feature)
  val featureLinks = (if (cloud.session == null) featureLinkRows else cloud.snapshot?.featureLinks)?.map(ProjectFeatureLinkRow::link)
  val featureNames = remember(features) { features.orEmpty().associate { it.id to it.name } }
  val categories = (if (cloud.session == null) categoryRows else cloud.snapshot?.categories).orEmpty().map(CategoryRow::category)
  val routines = if (cloud.session == null) localRoutines else cloud.snapshot?.routines
  val project = projects.find { it.id == selectedProject }
  val groups = remember(project?.id, features, tasks) {
    if (project != null && features != null) Features.groups(project.id, features, tasks) else emptyList()
  }
  val group = groups.find { it.id == selectedGroup }
  val graphPreferences = remember(owner, selectedProject) { ProjectGraphPreferences(context, owner, selectedProject.orEmpty()) }
  val featureGraphPreferences = remember(owner, selectedProject) {
    ProjectGraphPreferences(context, owner, selectedProject.orEmpty(), "features")
  }
  var graphView by remember(owner, selectedProject) { mutableStateOf(graphPreferences.graph) }
  LaunchedEffect(owner, selectedProject) { graphSessions.activate(owner, selectedProject) }
  val loaded = if (cloud.session != null) cloud.snapshot != null else rows != null && categoryRows != null && projectRows != null &&
    featureRows != null && featureLinkRows != null && routines != null && !cloud.working
  LaunchedEffect(loaded, project, group) {
    if (loaded && project == null) selectedProject = null
    if (loaded && group == null) selectedGroup = null
  }
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
  fun newTask(targetProject: Project? = null, seed: Task? = null, featureId: String? = null) {
    editingTask = null; taskProject = targetProject; taskSeed = seed; taskFeatureId = featureId; taskForm = true
  }
  val editTask: (Task) -> Unit = { taskSeed = null; editingTask = it; taskProject = null; taskFeatureId = null; taskForm = true }
  val editFeature: (ProjectFeature) -> Unit = { editingFeature = it; featureForm = true }
  fun groupSession(project: Project, group: FeatureGroup): Pair<ProjectGraphSession, ProjectGraphPreferences> {
    val diagram = group.feature?.let { "feature-${it.id}" }
    val preferences = ProjectGraphPreferences(context, owner, project.id, diagram)
    return graphSessions.get(owner, project.id, preferences, diagram) to preferences
  }
  fun openGroup(target: FeatureGroup, taskId: String? = null) {
    if (project != null && taskId != null) {
      val state = groupSession(project, target).first
      if (state.root != null) { state.full(); state.fitPending = true }
      state.selected = taskId
    }
    selectedGroup = target.id
  }
  fun projectBack() {
    if (project == null) { selectedProject = null; return }
    if (features == null && graphView && graphSessions.get(owner, project.id, graphPreferences).back()) return
    if (group != null) {
      if (!groupSession(project, group).first.back()) selectedGroup = null
      return
    }
    if (features != null && featureGraph && graphSessions.get(owner, project.id, featureGraphPreferences, "features").back()) return
    selectedProject = null
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
    val creatingFeature = screen == Destination.PROJECTS && project != null && features != null && group == null
    val inactiveTaskPlan = screen == Destination.PROJECTS && project != null && project.status != ProjectStatus.ACTIVE && !creatingFeature
    if (loaded && !model.busy && (!cloud.working || cloud.session != null) && !model.readOnly && !inactiveTaskPlan) {
      val create = {
        when (screen) {
          Destination.SETTINGS -> { editingCategory = null; categoryForm = true }
          Destination.ROUTINES -> { editingRoutine = null; routineForm = true }
          Destination.PROJECTS -> if (project == null) { editingProject = null; projectForm = true }
            else if (creatingFeature) { editingFeature = null; featureForm = true }
            else newTask(project, featureId = group?.feature?.id)
          else -> newTask()
        }
      }
      if (screen == Destination.CALENDAR) {
        FloatingActionButton(onClick = create, shape = RoundedCornerShape(18.dp),
          containerColor = MaterialTheme.colorScheme.onPrimaryContainer, contentColor = MaterialTheme.colorScheme.onPrimary) {
          Icon(appIcon("Añadir"), "Nueva tarea")
        }
      } else {
        ExtendedFloatingActionButton(onClick = create) { Text(when {
          screen == Destination.SETTINGS -> "Nueva categoría"
          screen == Destination.ROUTINES -> "Nueva rutina"
          screen == Destination.PROJECTS && project == null -> "Nuevo proyecto"
          creatingFeature -> "Nueva funcionalidad"
          else -> "Nueva tarea"
        }) }
      }
    }
  }) { padding ->
    BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
    val calendarHeight = maxHeight
    screenState.SaveableStateProvider(if (screen == Destination.PROJECTS) "${screen.name}/${selectedProject.orEmpty()}/${selectedGroup.orEmpty()}" else screen.name) {
    val calendarList = rememberLazyListState()
    val calendarVisible = screen == Destination.CALENDAR && loaded
    val screenInsets = if (calendarVisible) 0.dp else 20.dp
    LazyColumn(state = calendarList, modifier = Modifier.fillMaxSize().padding(horizontal = screenInsets),
      verticalArrangement = Arrangement.spacedBy(12.dp),
      contentPadding = PaddingValues(bottom = if (calendarVisible) 76.dp else 100.dp, top = if (calendarVisible) 4.dp else 24.dp)) {
      if (!calendarVisible) item {
        Text(if (screen == Destination.TASKS) "Todas las tareas" else screen.label, style = MaterialTheme.typography.headlineLarge, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold)
      }
      if (screen == Destination.SETTINGS) item { AccountPanel(cloud, pending, model::login, model::refresh, model::logout, model::resolve, initiallyExpanded = true) }
      if (cloud.error != null && screen != Destination.SETTINGS) item {
        Column(Modifier.padding(horizontal = if (calendarVisible) 12.dp else 0.dp)) {
          Text(cloud.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
          TextButton(enabled = !cloud.working, onClick = model::refresh) { Text("Reintentar") }
        }
      }
      if (!loaded) item { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(if (cloud.error != null) "No se pudieron cargar los datos." else "Cargando tus datos…") }

      else when (screen) {
        Destination.TASKS -> taskOverview(tasks, projects, categories, routines.orEmpty(), today, habitDate, model.busy || model.readOnly,
          expanded, toggleSection, editTask, completeTask, { deletingTask = it }, completeHabit,
          newTask = { newTask() },
          newRoutine = { editingRoutine = null; routineForm = true },
          openProjects = { screen = Destination.PROJECTS }, openRoutines = { screen = Destination.ROUTINES }, featureNames = featureNames)
        Destination.CALENDAR -> {
          item {
            CalendarScreen(tasks, projects, categories, today, date, { selectedDate = it.toString() },
              cloud.session?.userId ?: "local", cloud.snapshot?.calendar ?: model.localSettings,
              model.busy || model.readOnly, model::settings, scrollOuter = { amount -> calendarList.scrollBy(amount) },
              create = { day, minute ->
                newTask(seed = CalendarLayout.draft(day, minute))
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
            if (features != null && group == null) {
              item("feature-view") {
                Text("Funcionalidades", style = MaterialTheme.typography.titleLarge)
                ProjectViewSwitch(featureGraph) { featureGraph = it }
              }
              if (featureGraph) item("feature-graph") {
                FeatureGraphView(project, groups, featureLinks?.filter { it.projectId == project.id }, model.busy || model.readOnly,
                  graphSessions.get(owner, project.id, featureGraphPreferences, "features"), featureGraphPreferences,
                  calendarHeight - 320.dp, open = { openGroup(it) }, edit = editFeature, delete = { deletingFeature = it },
                  createLink = { link, saved -> model.execute(onSuccess = saved) { createFeatureLink(link) } },
                  deleteLink = { link -> model.execute { deleteFeatureLink(link) } })
              } else items(groups, key = { "group-${it.id}" }) { target ->
                FeatureCard(target, project.color, model.busy || model.readOnly, open = { openGroup(target) },
                  edit = { target.feature?.let(editFeature) }, delete = { deletingFeature = target.feature })
              }
            } else if (group != null) {
              item("feature-heading") {
                TextButton(onClick = { groupSession(project, group).first.apply { if (root != null) full(); selected = null }; selectedGroup = null }) {
                  Text("Volver a funcionalidades")
                }
                Text(group.title, style = MaterialTheme.typography.titleLarge)
                group.feature?.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                group.feature?.let { feature ->
                  FlowRow {
                    TextButton(enabled = !model.busy && !model.readOnly, onClick = { editFeature(feature) }) { Text("Editar funcionalidad") }
                    TextButton(enabled = !model.busy && !model.readOnly, onClick = { deletingFeature = feature }) { Text("Eliminar funcionalidad") }
                  }
                }
              }
              item("feature-plan-${group.id}") {
                val (session, preferences) = remember(owner, project.id, group.id) { groupSession(project, group) }
                ProjectGraphView(group.tasks, project, today, session, preferences, model.busy || model.readOnly,
                  calendarHeight - 320.dp, editTask, completeTask, { deletingTask = it },
                  links = { task, desired, result ->
                    model.execute(onSuccess = { result(true) }, onFailure = { result(false) }) {
                      setPrerequisites(task.id, project.id, task.prerequisiteIds, desired)
                    }
                  }, projectTasks = associated, groupName = group.title, featureNames = featureNames,
                  openExternal = { task -> groups.find { target -> target.tasks.any { it.id == task.id } }?.let { openGroup(it, task.id) } })
              }
            } else {
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
    seed = taskSeed, features = features, initialFeatureId = taskFeatureId, dismiss = { taskForm = false }, save = { task ->
      val original = editingTask
      model.execute(onSuccess = { taskForm = false }) { if (original == null) save(task) else this.editTask(original, task) }
    })
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
  if (featureForm && project != null && features != null) FeatureForm(project, editingFeature, model.busy || model.readOnly,
    dismiss = { featureForm = false }, save = { value ->
      val original = editingFeature
      model.execute(onSuccess = { featureForm = false }) { saveFeature(value, original) }
    })
  deletingFeature?.let { feature ->
    DeleteConfirmation("¿Eliminar funcionalidad?",
      "Las tareas de «${feature.name}» pasarán a «Sin funcionalidad» y conservarán sus dependencias. Se eliminarán las conexiones de planificación de esta funcionalidad.",
      model.busy || model.readOnly, { deletingFeature = null }) {
      model.execute(onSuccess = { deletingFeature = null }) { deleteFeature(feature) }
    }
  }
  if (routineForm) RoutineForm(editingRoutine, habitDate, (model.busy || model.readOnly), dismiss = { routineForm = false }, save = { value, start ->
    model.execute(onSuccess = { routineForm = false }) { saveRoutine(value.id, value.title, value.color, value.active, start) }
  })
  deletingTask?.let { task ->
    DeleteConfirmation("¿Eliminar tarea?", task.title, (model.busy || model.readOnly), { deletingTask = null }) {
      model.execute(onSuccess = { deletingTask = null }) { delete(task.id) }
    }
  }
  deletingProject?.let { value ->
    val featureMessage = if (features.orEmpty().any { it.projectId == value.id }) " También se eliminarán sus funcionalidades y conexiones." else ""
    DeleteConfirmation("¿Eliminar proyecto?", "Se eliminarán «${value.name}» y sus ${tasks.count { it.projectId == value.id }} tareas, incluidas las completadas y sus dependencias.$featureMessage Esta acción no se puede deshacer.", (model.busy || model.readOnly), { deletingProject = null }) {
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
