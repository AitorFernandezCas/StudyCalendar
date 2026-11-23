package com.studycalendar.android

import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.studycalendar.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.LocalDate

class FeaturePlansTest {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
  private val project = Project(id = "feature-ui", name = "Curso")
  private val planning = ProjectFeature("a", project.id, "Planificar")
  private val writing = ProjectFeature("b", project.id, "Escribir")
  private val day = LocalDate.of(2026, 11, 20)
  private val prior = Task(id = "prior", title = "Leer", date = day, projectTask = true, projectId = project.id, featureId = planning.id)
  private val dependent = prior.copy(id = "dependent", title = "Redactar", featureId = writing.id, prerequisiteIds = setOf(prior.id))

  @Test fun projectFeatureGraphKeepsTaskDerivedLinksAfterRemovalAndOpensTheFeaturePlan() {
    val preferences = ProjectGraphPreferences(rule.activity, "test", project.id, "feature-overview-test")
    val session = ProjectGraphSession()
    val notes = prior.copy(id = "notes", title = "Tomar notas", featureId = null, completed = true)
    val groups = Features.groups(project.id, listOf(planning, writing),
      listOf(prior, notes, dependent.copy(prerequisiteIds = setOf(prior.id, notes.id))))
    var saved = emptyList<ProjectFeatureLink>()
    var opened: FeatureGroup? = null
    rule.setContent {
      StudyCalendarTheme {
        var links by remember { mutableStateOf(saved) }
        Column(Modifier.verticalScroll(rememberScrollState())) {
          FeatureGraphView(project, groups, links, false, session, preferences, 260.dp,
            open = { opened = it }, edit = {}, delete = {},
            createLink = { link, success -> links = links + link; saved = links; success() },
            deleteLink = { link -> links = links - link; saved = links })
        }
      }
    }
    rule.onNodeWithTag("feature-node-b").performClick()
    rule.runOnIdle { assertEquals(writing, opened?.feature) }
    rule.onNodeWithContentDescription("Opciones de Escribir").performClick()
    rule.onNodeWithText("Planificar · Tareas").assertExists()
    rule.onNodeWithText("Añadir funcionalidad previa").performScrollTo().performClick()
    rule.onNodeWithTag("feature-prior-a").performScrollTo().performClick()
    rule.onNodeWithText("Planificar · Planificación y tareas").assertExists()
    rule.runOnIdle { assertEquals(planning.id, saved.single().sourceId); assertEquals(writing.id, saved.single().targetId) }
    rule.runOnIdle { session.selected = null }
    val graph = rule.onNodeWithTag("feature-graph").captureToImage().asAndroidBitmap()
    File(rule.activity.cacheDir, "feature-planning-android.png").outputStream().use { graph.compress(Bitmap.CompressFormat.PNG, 100, it) }
    rule.onNodeWithContentDescription("Opciones de Escribir").performClick()
    rule.onNodeWithContentDescription("Quitar conexión de Planificar").performClick()
    rule.onNodeWithText("Planificar · Tareas").assertExists()
    rule.runOnIdle { assertTrue(saved.isEmpty()) }
  }

  @Test fun taskDiagramKeepsNodesScopedAndNavigatesExternalPrerequisites() {
    val preferences = ProjectGraphPreferences(rule.activity, "test", project.id, "feature-${writing.id}")
    val sourcePreferences = ProjectGraphPreferences(rule.activity, "test", project.id, "feature-${planning.id}")
    val session = ProjectGraphSession()
    var opened: Task? = null
    rule.setContent {
      StudyCalendarTheme {
        Column {
          ProjectGraphView(listOf(dependent), project, day, session, preferences, false, 240.dp,
            edit = {}, complete = {}, delete = {}, links = { _, _, _ -> }, projectTasks = listOf(prior, dependent),
            groupName = writing.name, featureNames = mapOf(planning.id to planning.name, writing.id to writing.name),
            openExternal = { opened = it })
        }
      }
    }
    rule.onNodeWithTag("plan-node-prior").assertDoesNotExist()
    rule.onNodeWithTag("plan-node-dependent").performClick()
    rule.onNodeWithText("Completar").assertIsNotEnabled()
    rule.onNodeWithText("Leer · Planificar").performScrollTo().performClick()
    rule.runOnIdle {
      assertEquals(prior, opened)
      assertNull(session.selected)
      preferences.write(mapOf(dependent.id to PlanPoint(80f, 100f)))
      assertTrue(sourcePreferences.read().isEmpty())
    }
  }

  @Test fun featureCreationDefaultsToUndatedAndCalendarKeepsItsSelectedDate() {
    var saved: Task? = null
    var fromCalendar by mutableStateOf(false)
    rule.setContent {
      StudyCalendarTheme {
        key(fromCalendar) {
          TaskForm(null, day, if (fromCalendar) null else project, listOf(project), emptyList(), emptyList(), false,
            createProject = { _, _ -> }, seed = if (fromCalendar) CalendarLayout.draft(day.plusDays(4), null) else null,
            features = listOf(planning), initialFeatureId = if (fromCalendar) null else planning.id,
            dismiss = {}, save = { saved = it })
        }
      }
    }
    rule.onNodeWithText("Funcionalidad: Planificar").performScrollTo().assertExists()
    rule.onNode(hasSetTextAction() and hasText("Título")).performTextInput("Borrador")
    rule.onNodeWithText("Guardar").performClick()
    rule.runOnIdle {
      assertEquals(planning.id, saved!!.featureId)
      assertNull(saved!!.date)
      fromCalendar = true
    }
    rule.onNode(hasSetTextAction() and hasText("Título")).performTextInput("Cita")
    rule.onNodeWithText("Guardar").performClick()
    rule.runOnIdle {
      assertEquals(day.plusDays(4), saved!!.date)
      assertNull(saved!!.featureId)
    }
  }
}
