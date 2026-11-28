package com.studycalendar.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.studycalendar.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class FrontendTest {
  @get:Rule val rule = createComposeRule()
  private val day = LocalDate.of(2026, 11, 16)
  private val project = Project(id = "project", name = "Preparar el examen", color = "#16A085")
  private val personal = Task(id = "personal", title = "Repasar el tema de álgebra", date = day)
  private val habit = RoutineRecord(RoutineRow.from(Routine("habit", "Leer veinte minutos", "#7056ED", true, listOf(ActivePeriod(day)))), emptyList())

  private fun screenshot(name: String) {
    val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
    File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }

  @Test fun navigationExposesFourDestinationsAndLoadingBadge() {
    rule.setContent {
      var selected by remember { mutableStateOf(Destination.TASKS) }
      StudyCalendarTheme { AppNavigation(selected, null) { selected = it } }
    }
    rule.onNodeWithContentDescription("Cargando pendientes", useUnmergedTree = true).assertExists()
    rule.onNodeWithText("Categorías").assertDoesNotExist()
    rule.onNodeWithText("Tareas").assertIsSelected()
    rule.onNodeWithText("Calendario").performClick().assertIsSelected()
    rule.onNodeWithText("Proyectos").performClick().assertIsSelected()
    rule.onNodeWithText("Rutinas").performClick().assertIsSelected()
  }

  @Test fun overviewFiltersCompletesAndRestoresTaskWithUndo() {
    val hidden = project.copy(id = "hidden", status = ProjectStatus.INACTIVE)
    rule.setContent {
      var tasks by remember { mutableStateOf(listOf(personal, personal.copy(id = "hidden-task", title = "Oculta", projectTask = true, projectId = hidden.id))) }
      var previous by remember { mutableStateOf<Task?>(null) }
      StudyCalendarTheme {
        Column {
          previous?.let { task -> TextButton(onClick = { tasks = tasks.map { if (it.id == task.id) task else it }; previous = null }) { Text("Deshacer") } }
          LazyColumn {
            taskOverview(tasks, listOf(hidden), emptyList(), listOf(habit), day, day, false, setOf("daily", "projects", "habits"), {}, {},
              complete = { changed -> previous = changed; tasks = tasks.map { if (it.id == changed.id) it.copy(completed = true) else it } }, delete = {}, completeHabit = {},
              newTask = {}, newRoutine = {}, openProjects = {}, openRoutines = {}, openRoutine = {})
          }
        }
      }
    }
    rule.onNodeWithText("Oculta").assertDoesNotExist()
    rule.onNodeWithContentDescription("Completar ${personal.title}").performClick()
    rule.onNodeWithText(personal.title).assertDoesNotExist()
    rule.onNodeWithText("Deshacer").performClick()
    rule.onNodeWithText(personal.title).assertExists()
  }

  @Test fun blockedTaskExplainsPrerequisiteAndReadOnlyAllowsInspection() {
    var inspected = false
    val task = personal.copy(title = "Resolver ejercicios", projectTask = true, projectId = project.id, prerequisiteIds = setOf(personal.id))
    rule.setContent { StudyCalendarTheme { TaskCard(task, project, null, true, {}, { inspected = true }, {}, day, listOf(personal, task)) } }
    rule.onNodeWithContentDescription("Completar ${task.title}").assertIsNotEnabled()
    rule.onNodeWithText("Antes: ${personal.title}").assertExists()
    rule.onNodeWithText(task.title).performClick()
    assertTrue(inspected)
  }

  @Test fun projectStatusUsesExclusiveRadioSelection() {
    var saved: Project? = null
    rule.setContent { StudyCalendarTheme { ProjectForm(project, false, {}, { saved = it }) } }
    rule.onNodeWithText("Completado").performClick().assertIsSelected()
    rule.onNodeWithText("Activo").assertIsNotSelected()
    rule.onNodeWithText("Guardar").performClick()
    assertEquals(ProjectStatus.COMPLETED, saved!!.status)
  }

  @Test fun nestedProjectCreationPreservesTaskDraftAndSchedule() {
    var saved: Task? = null
    rule.setContent {
      var projects by remember { mutableStateOf(emptyList<Project>()) }
      StudyCalendarTheme { TaskForm(null, day, null, projects, emptyList(), emptyList(), false,
        createProject = { value, success -> projects = projects + value; success() }, dismiss = {}, save = { saved = it }) }
    }
    rule.onNodeWithText("Título").performTextInput("Conservar borrador")
    rule.onNodeWithText("Tarea de proyecto").performScrollTo().performClick()
    rule.onNodeWithText("Crear proyecto").performScrollTo().performClick()
    rule.onNodeWithText("Nombre").performTextInput("Nuevo proyecto")
    rule.onAllNodesWithText("Guardar").onLast().performClick()
    rule.onNodeWithText("Conservar borrador").assertExists()
    rule.onNodeWithText("Guardar").performClick()
    assertEquals("Conservar borrador", saved!!.title)
    assertEquals(day, saved!!.date)
    assertTrue(saved!!.allDay)
    assertNotNull(saved!!.projectId)
  }

  @Test fun readOnlyFormCannotSaveAndHasNativeScheduleFields() {
    rule.setContent { StudyCalendarTheme { TaskForm(personal.copy(allDay = false), day, null, emptyList(), emptyList(), emptyList(), false, true,
      createProject = { _, _ -> }, dismiss = {}, save = { fail("Read-only save") }) } }
    rule.onNodeWithText("Guardar").assertIsNotEnabled()
    rule.onNodeWithText("Hora de inicio").performScrollTo().assertExists()
    rule.onNodeWithText("Formato: HH:MM").assertDoesNotExist()
    rule.onNodeWithText("Cerrar").assertIsEnabled()
  }

  @Test fun captureOverviewWithDisposableData() {
    rule.setContent {
      StudyCalendarTheme {
        Scaffold(bottomBar = { AppNavigation(Destination.TASKS, 4) {} }) { padding ->
          LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("StudyCalendar", color = MaterialTheme.colorScheme.primary); Text("Todas las tareas", style = MaterialTheme.typography.headlineLarge) }
            taskOverview(listOf(personal, personal.copy(id = "overdue", title = "Revisar los apuntes", date = day.minusDays(1)),
              personal.copy(id = "project-task", title = "Preparar ejercicios", projectTask = true, projectId = project.id)), listOf(project), emptyList(), listOf(habit),
              day, day, false, setOf("daily", "projects", "habits"), {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
          }
        }
      }
    }
    rule.waitForIdle()
    screenshot("frontend-overview")
  }

  @Test fun narrowLandscapeCalendarUsesAvailableWidthAndKeepsAgendaAccessible() {
    val owner = "frontend-layout-test"
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    context.getSharedPreferences("calendar-view-$owner", 0).edit()
      .putString("view", "Día").putBoolean("agenda", false).commit()
    rule.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
        StudyCalendarTheme {
          Box(Modifier.width(320.dp).height(360.dp)) {
            CalendarScreen(listOf(personal), emptyList(), emptyList(), day, day, {}, owner, CalendarSettings(480, 720), false,
              {}, {}, { _, _ -> }, {}, {}, {}, { _, _, result -> result(true) }, remember { CalendarDragController() }, availableHeight = 360.dp)
          }
        }
      }
    }
    val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
    val grid = rule.onNodeWithTag("calendar-grid-$day").fetchSemanticsNode().boundsInRoot
    assertTrue(grid.right <= root.right + 1f)
    rule.onNodeWithContentDescription("Mostrar agenda").assertIsDisplayed().performClick()
    rule.onNodeWithText(personal.title).assertExists()
  }

  @Test fun calendarToolbarFiltersAndMonthOverflowKeepTheDayAccessible() {
    val owner = "frontend-calendar-views-test"
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    context.getSharedPreferences("calendar-view-$owner", 0).edit()
      .putString("view", "Semana").putBoolean("agenda", false).putString("category", "*").commit()
    val category = Category(id = "study", name = "Estudio", color = "#BB5B27")
    val completed = personal.copy(id = "completed", title = "Leer el capítulo", completed = true, categoryId = category.id)
    val timed = personal.copy(id = "lecture", title = "Clase de álgebra lineal", allDay = false,
      start = LocalTime.of(9, 0), end = LocalTime.of(10, 30), categoryId = category.id)
    val outside = timed.copy(id = "outside", title = "Revisar los ejemplos de ecuaciones diferenciales", start = LocalTime.of(19, 0), end = LocalTime.of(20, 0))
    val overlap = timed.copy(id = "overlap", title = "Práctica", start = LocalTime.of(9, 30), end = LocalTime.of(11, 0),
      categoryId = null, projectTask = true, projectId = project.id)
    val nextDay = timed.copy(id = "next", title = "Preparar ejercicios", date = day.plusDays(1), categoryId = null,
      projectTask = true, projectId = project.id)
    var created: Pair<LocalDate, Int?>? = null
    rule.setContent {
      var selected by remember { mutableStateOf(day) }
      StudyCalendarTheme {
        BoxWithConstraints(Modifier.fillMaxSize()) {
          val height = maxHeight
          Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            CalendarScreen(listOf(personal, completed, timed, outside, overlap, nextDay), listOf(project), listOf(category),
              day, selected, { selected = it }, owner, CalendarSettings(480, 720), false, {}, {},
              create = { date, minute -> created = date to minute }, edit = {}, complete = {}, delete = {},
              move = { _, _, result -> result(true) }, drag = remember { CalendarDragController() }, availableHeight = height)
          }
        }
      }
    }
    rule.onNodeWithText("Semana").assertIsSelected()
    rule.onNodeWithContentDescription("Siguiente").performClick()
    rule.onNodeWithTag("calendar-grid-${day.plusWeeks(1)}").assertExists()
    rule.onNodeWithText("Hoy").performClick()
    rule.onNodeWithTag("calendar-task-${timed.id}").assertIsDisplayed()
    screenshot("calendar-week")
    rule.onNodeWithText("Día").performClick().assertIsSelected()
    rule.onNode(hasContentDescription("Nueva tarea,", substring = true) and hasContentDescription("11:30", substring = true))
      .performClick()
    rule.runOnIdle { assertEquals(day to 690, created) }
    screenshot("calendar-day")
    rule.onNodeWithContentDescription("Filtrar por categoría: Todas las categorías").performClick()
    rule.onNodeWithText(category.name).performClick()
    rule.onNodeWithContentDescription("Filtrar por categoría: Estudio").assertIsDisplayed()
    rule.onNodeWithTag("calendar-task-${personal.id}").assertDoesNotExist()
    rule.onNodeWithText("Ver agenda", substring = true).performClick()
    rule.onNodeWithText(outside.title).assertExists()
    screenshot("calendar-agenda")
    rule.onNodeWithText("Mes").performClick().assertIsSelected()
    rule.onNodeWithTag("calendar-month-$day").performScrollTo()
    screenshot("calendar-month")
    rule.onNodeWithContentDescription("Una tarea más").assertIsDisplayed().assertHasClickAction().performTouchInput { click() }
    rule.onNodeWithText("Cerrar agenda").assertIsDisplayed()
    rule.onNodeWithText(outside.title).assertExists()
    rule.onNodeWithContentDescription("Nueva tarea para el lunes 16 de noviembre").performClick()
    rule.runOnIdle { assertEquals(day to null, created) }
  }

  @Test fun largeTextKeepsCardActionsAndFullTitleAvailable() {
    val title = "Repasar todos los ejercicios de ecuaciones diferenciales antes del examen final"
    rule.setContent {
      val density = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
        StudyCalendarTheme { Box(Modifier.width(320.dp)) { TaskCard(personal.copy(title = title), null, null, false, {}, {}, {}, day) } }
      }
    }
    rule.onNodeWithText(title).assertIsDisplayed()
    rule.onNodeWithContentDescription("Acciones de $title").performClick()
    rule.onNodeWithText("Eliminar").assertIsDisplayed()
  }
}
