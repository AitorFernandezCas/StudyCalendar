package com.studycalendar.android

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.studycalendar.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ProjectGraphTest {
  @get:Rule val rule = createComposeRule()
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val project = Project(id = "graph-fixture", name = "Preparar el examen", color = "#16A085")
  private fun task(id: String, title: String = id, vararg parents: String) = Task(id = id, title = title,
    date = null, start = null, end = null, projectTask = true, projectId = project.id, prerequisiteIds = parents.toSet())
  private val initial = listOf(task("a", "Repasar álgebra"), task("b", "Resolver ejercicios", "a"),
    task("c", "Preparar geometría", "a"), task("d", "Simulacro final", "b", "x"), task("x", "Leer los apuntes"))
  private lateinit var session: ProjectGraphSession
  private lateinit var prefs: ProjectGraphPreferences
  private var tasks by mutableStateOf(initial)
  private var graph by mutableStateOf(true)
  private var links = 0
  private var inspected: Task? = null

  private fun content(readOnly: Boolean = false, narrow: Boolean = false): @Composable () -> Unit = {
    StudyCalendarTheme {
      Column(if (narrow) Modifier.width(320.dp) else Modifier.fillMaxWidth()) {
        ProjectViewSwitch(graph) { graph = it; prefs.graph = it }
        if (graph) ProjectGraphView(tasks, project, LocalDate.of(2026, 11, 16), session, prefs, readOnly, 360.dp,
          edit = { inspected = it }, complete = { selected ->
            val next = selected.copy(completed = !selected.completed)
            Planning.save(next, tasks)
            tasks = tasks.map { if (it.id == next.id) next else it }
          }, delete = { tasks = tasks.filter { task -> task.id != it.id } },
          links = { selected, desired, result ->
            val current = tasks.first { it.id == selected.id }
            val changed = Planning.withPrerequisites(current, project.id, selected.prerequisiteIds, desired)
            Planning.save(changed, tasks)
            tasks = tasks.map { if (it.id == changed.id) changed else it }
            links++; result(true)
          })
        else Text("Lista de tareas")
      }
    }
  }
  private fun fixture(readOnly: Boolean = false, narrow: Boolean = false) {
    context.getSharedPreferences("project-graph-graph-ui-test-${project.id}", 0).edit().clear().commit()
    prefs = ProjectGraphPreferences(context, "graph-ui-test", project.id)
    session = ProjectGraphSession().apply { fitPending = false }
    rule.setContent(content(readOnly, narrow))
  }
  private fun node(id: String) = rule.onNodeWithTag("plan-node-$id")

  @Test fun taskSheetExplainsBlockingAndSupportsReadOnlyInspection() {
    fixture(readOnly = true)
    node("b").performClick()
    rule.onNodeWithText("Antes: Repasar álgebra").assertExists()
    rule.onNodeWithText("Completar").assertIsNotEnabled()
    rule.onNodeWithText("Conectar con…").assertIsNotEnabled()
    rule.onNodeWithText("Ver detalles").performClick()
    assertEquals("b", inspected?.id)
    assertEquals(0, links)
  }

  @Test fun connectAndRemoveUsePrerequisiteDirectionAndExistingTasks() {
    tasks = listOf(task("a", "Primero"), task("b", "Después"))
    fixture()
    node("a").performTouchInput { click() }
    rule.onNodeWithText("Conectar con…").performClick()
    node("b").performClick()
    rule.runOnIdle { assertEquals(setOf("a"), tasks.first { it.id == "b" }.prerequisiteIds); assertEquals(1, links) }
    node("b").performClick()
    rule.onNodeWithContentDescription("Quitar conexión de Primero").performClick()
    rule.runOnIdle { assertTrue(tasks.first { it.id == "b" }.prerequisiteIds.isEmpty()); assertEquals(2, links) }
  }

  @Test fun branchExcludesSideInputsRestoresCameraAndKeepsFullBlockingRules() {
    fixture()
    rule.runOnIdle { session.camera = PlanCamera(.7f, 12f, 35f); session.place("b", PlanPoint(400f, 200f)); prefs.write(session.positions) }
    node("b").performClick()
    rule.onNodeWithText("Ver rama").performClick()
    rule.onNodeWithText("3 de 5 tareas").assertExists()
    node("x").assertDoesNotExist()
    node("c").assertDoesNotExist()
    node("d").performClick()
    rule.onNodeWithText("Leer los apuntes (fuera de rama)").assertExists()
    rule.onNodeWithText("Completar").assertIsNotEnabled()
    rule.runOnIdle { session.selected = null; session.place("b", PlanPoint(50f, 80f)) }
    rule.onNodeWithText("Ver proyecto completo").performClick()
    rule.runOnIdle {
      assertEquals(PlanCamera(.7f, 12f, 35f), session.camera)
      assertEquals(PlanPoint(400f, 200f), session.positions["b"])
      assertEquals(session.positions, prefs.read())
    }
  }

  @Test fun longPressMovesAtZoomAndCancelRestoresWithoutChangingTasks() {
    tasks = listOf(task("a", "Mover esta tarea"))
    fixture()
    rule.runOnIdle { session.camera = PlanCamera(.7f, 0f, 0f) }
    val before = tasks
    node("a").performTouchInput { down(center); advanceEventTime(650); moveBy(Offset(70f, 35f)); up() }
    rule.runOnIdle {
      assertNotNull(session.positions["a"])
      assertTrue(session.positions.getValue("a").x > 32f)
      assertEquals(before, tasks)
      assertEquals(session.positions, prefs.read())
    }
    val saved = session.positions
    node("a").performTouchInput { down(center); advanceEventTime(650); moveBy(Offset(30f, 0f)); cancel() }
    rule.runOnIdle { assertEquals(saved, session.positions); assertNull(session.drag); assertEquals(saved, prefs.read()) }
  }

  @Test fun viewSwitchRetainsSessionAndDevicePreferencesAreIsolated() {
    fixture()
    rule.runOnIdle { session.focus("b"); session.place("b", PlanPoint(70f, 100f)) }
    rule.onNodeWithText("Lista").performClick().assertIsSelected()
    rule.onNodeWithTag("project-graph").assertDoesNotExist()
    assertFalse(prefs.graph)
    rule.onNodeWithText("Gráfico").performClick().assertIsSelected()
    rule.onNodeWithText("3 de 5 tareas").assertExists()
    rule.runOnIdle { assertEquals(PlanPoint(70f, 100f), session.branchPositions["b"]) }
    val other = ProjectGraphPreferences(context, "another-graph-test-account", project.id)
    assertTrue(other.read().isEmpty())
    val sessions = ProjectGraphSessions()
    val first = sessions.get("owner", project.id, prefs)
    first.focus("a")
    assertSame(first, sessions.get("owner", project.id, prefs))
    assertNull(ProjectGraphSessions().get("owner", project.id, prefs).root)
  }

  @Test fun layoutSurvivesRecreationAndRemovedRootReturnsToFullGraph() {
    context.getSharedPreferences("project-graph-graph-ui-test-${project.id}", 0).edit().clear().commit()
    prefs = ProjectGraphPreferences(context, "graph-ui-test", project.id)
    session = ProjectGraphSession()
    val restore = StateRestorationTester(rule)
    restore.setContent(content())
    rule.runOnIdle { session.focus("b"); session.selected = "d" }
    restore.emulateSavedInstanceStateRestore()
    rule.runOnIdle { assertEquals("b", session.root); assertEquals("d", session.selected); session.selected = null; tasks = tasks.filter { it.id != "b" } }
    rule.onNodeWithText("Ver proyecto completo").assertDoesNotExist()
    node("x").assertExists()
  }

  @Test fun narrowLayoutWrapsLongTitleAndZoomButtonsRemainUsable() {
    tasks = listOf(task("a", "Repasar todos los conceptos de álgebra y geometría antes del examen final"))
    fixture(narrow = true)
    rule.onNodeWithContentDescription("Acercar plan").performClick()
    rule.runOnIdle { assertEquals(1.25f, session.camera.zoom) }
    node("a").assertExists()
    rule.onNodeWithText("Restablecer vista").performClick()
    val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
    File(context.cacheDir, "project-graph-narrow.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }

  @Test fun backgroundPanAndPinchChangeOnlyCameraAndEmptyPlansRemainUsable() {
    tasks = emptyList()
    fixture()
    rule.onNodeWithText("Este proyecto todavía no tiene tareas. Usa Nueva tarea para empezar.").assertExists()
    val view = rule.onNodeWithTag("project-graph")
    view.performTouchInput { swipe(Offset(width * .8f, height * .7f), Offset(width * .4f, height * .5f)) }
    rule.runOnIdle { assertTrue(session.camera.left > 0f) }
    view.performTouchInput {
      pinch(Offset(width * .4f, height * .5f), Offset(width * .6f, height * .5f), Offset(width * .2f, height * .5f), Offset(width * .8f, height * .5f))
    }
    rule.runOnIdle { assertTrue(session.camera.zoom > 1f); assertTrue(session.positions.isEmpty()); assertEquals(0, links) }
  }

  @Test fun completingPrerequisiteUnlocksDependentAndBackCancelsConnectionBeforeBranch() {
    tasks = listOf(task("a", "Primero"), task("b", "Después", "a"))
    fixture()
    node("a").performClick()
    rule.onNodeWithText("Completar").performClick()
    node("b").performClick()
    rule.onNodeWithText("Completar").assertIsEnabled()
    rule.onNodeWithText("Ver rama").performClick()
    rule.runOnIdle {
      session.connecting = "b"
      assertTrue(session.back()); assertNull(session.connecting); assertEquals("b", session.root)
      assertTrue(session.back()); assertNull(session.root)
      assertFalse(session.back())
    }
  }

  @Test fun captureBranchingPlanWithDisposableTasks() {
    fixture()
    rule.runOnIdle { session.fitPending = true }
    rule.waitForIdle()
    val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
    File(context.cacheDir, "project-graph-branches.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
  }

  @Test fun graphSupportsLargeTextInNarrowViewport() {
    tasks = listOf(task("a", "Repasar todos los conceptos de álgebra antes del examen"))
    prefs = ProjectGraphPreferences(context, "graph-large-text-test", project.id)
    session = ProjectGraphSession()
    rule.setContent {
      val deviceDensity = LocalDensity.current
      CompositionLocalProvider(LocalDensity provides Density(deviceDensity.density, 2f)) { content(narrow = true)() }
    }
    node("a").assertExists()
    node("a").performClick()
    rule.onNodeWithText("Completar").assertIsEnabled()
  }
}
