package com.studycalendar.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.studycalendar.core.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CalendarDragTest {
  @get:Rule val rule = createComposeRule()
  private val day = LocalDate.of(2026, 11, 2)
  private val original = Task(title = "Tarea de prueba", date = day)
  private lateinit var drag: CalendarDragController
  private var writes = 0
  private var saved: Task? = null
  private fun fixture(view: String = "Mes", fail: Boolean = false, timed: Boolean = false) {
    val owner = "drag-test-${UUID.randomUUID()}"
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    context.getSharedPreferences("calendar-view-$owner", 0).edit().putString("view", view).commit()
    val task = if (timed) original.copy(allDay = false, start = LocalTime.of(9, 0), end = LocalTime.of(10, 0)) else original
    drag = CalendarDragController()
    rule.setContent {
      var tasks by remember { mutableStateOf(listOf(task)) }
      MaterialTheme {
        Box(Modifier.fillMaxSize()) {
          val scroll = rememberScrollState()
          Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            CalendarScreen(tasks, emptyList(), emptyList(), day, day, {}, owner, CalendarSettings(480, 720), false,
              {}, { amount -> scroll.scrollTo(scroll.value + amount.toInt()) }, { _, _ -> }, {}, {}, {},
              move = { before, after, result ->
                writes++
                assertEquals(task.id, before.id)
                if (!fail) { tasks = listOf(after); saved = after }
                result(!fail)
              }, drag = drag)
          }
          CalendarDragOverlay(drag)
        }
      }
    }
    rule.waitForIdle()
    rule.mainClock.autoAdvance = false
  }
  private fun begin() {
    rule.onNodeWithTag("calendar-task-${original.id}").performTouchInput {
      down(center)
      advanceEventTime(800)
      moveBy(Offset(1f, 0f))
    }
    rule.mainClock.advanceTimeByFrame()
    rule.waitForIdle()
    rule.runOnIdle { assertNotNull("Long press starts a drag", drag.session) }
  }
  private fun settle() {
    repeat(30) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle() }
    rule.runOnIdle { assertNull("Floating card finishes landing", drag.session) }
  }
  @Test fun cardTracksFingerAndCancelNeverWrites() {
    fixture()
    begin()
    val before = rule.runOnIdle { drag.session!!.floating }
    val drawnBefore = rule.onNodeWithTag("calendar-drag-card", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    rule.onNodeWithTag("calendar-task-${original.id}").performTouchInput { moveBy(Offset(24f, 16f)); advanceEventTime(16) }
    rule.mainClock.advanceTimeByFrame()
    rule.runOnIdle {
      val after = drag.session!!.floating
      assertEquals(before.left + 24f, after.left, 1f)
      assertEquals(before.top + 16f, after.top, 1f)
      assertEquals(before.size, after.size)
      assertEquals(0, writes)
    }
    val drawnAfter = rule.onNodeWithTag("calendar-drag-card", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    assertEquals(drawnBefore.left + 24f, drawnAfter.left, 1f)
    assertEquals(drawnBefore.top + 16f, drawnAfter.top, 1f)
    rule.onNodeWithTag("calendar-task-${original.id}").performTouchInput { cancel() }
    settle()
    assertEquals(0, writes)
  }
  @Test fun monthDropMovesDateExactlyOnce() {
    fixture()
    val source = rule.onNodeWithTag("calendar-task-${original.id}").fetchSemanticsNode().boundsInRoot
    val target = rule.onNodeWithTag("calendar-month-${day.plusDays(1)}").fetchSemanticsNode().boundsInRoot
    begin()
    rule.onNodeWithTag("calendar-task-${original.id}").performTouchInput { moveTo(target.center - source.topLeft); advanceEventTime(16) }
    rule.mainClock.advanceTimeByFrame()
    rule.runOnIdle { assertEquals(day.plusDays(1), drag.session!!.preview?.date); assertEquals(0, writes) }
    rule.onNodeWithTag("calendar-task-${original.id}").performTouchInput { up() }
    settle()
    assertEquals(1, writes)
    assertEquals(day.plusDays(1), saved?.date)
    rule.onNodeWithText("Deshacer").assertExists()
  }
  @Test fun failedSaveReturnsToSourceAndClearsGhost() {
    fixture(fail = true)
    val source = rule.onNodeWithTag("calendar-task-${original.id}").fetchSemanticsNode().boundsInRoot
    val target = rule.onNodeWithTag("calendar-month-${day.plusDays(1)}").fetchSemanticsNode().boundsInRoot
    begin()
    rule.onNodeWithTag("calendar-task-${original.id}").performTouchInput { moveTo(target.center - source.topLeft); advanceEventTime(16); up() }
    settle()
    assertEquals(1, writes)
    assertNull(saved)
    rule.onNodeWithTag("calendar-task-${original.id}").assertExists()
  }
  @Test fun invalidDropReturnsWithoutSaving() {
    fixture()
    val source = rule.onNodeWithTag("calendar-task-${original.id}").fetchSemanticsNode().boundsInRoot
    begin()
    rule.onNodeWithTag("calendar-task-${original.id}").performTouchInput { moveTo(Offset(5f, 5f) - source.topLeft); advanceEventTime(16); up() }
    settle()
    assertEquals(0, writes)
  }
  @Test fun timedCardKeepsGrabOffsetAcrossDayColumns() {
    fixture(view = "Semana", timed = true)
    val source = rule.onNodeWithTag("calendar-task-${original.id}").fetchSemanticsNode().boundsInRoot
    val target = rule.onNodeWithTag("calendar-grid-${day.plusDays(1)}").fetchSemanticsNode().boundsInRoot
    begin()
    val destination = Offset(target.left + 15f, source.center.y)
    rule.onNodeWithTag("calendar-task-${original.id}").performTouchInput { moveTo(destination - source.topLeft); advanceEventTime(16) }
    rule.mainClock.advanceTimeByFrame()
    rule.runOnIdle {
      assertEquals(destination.x, drag.session!!.pointer.x, 2f)
      assertEquals(day.plusDays(1), drag.session!!.preview?.date)
      assertEquals(0, writes)
    }
    rule.onNodeWithTag("calendar-task-${original.id}").performTouchInput { up() }
    settle()
    assertEquals(1, writes)
    assertEquals(60L, java.time.Duration.between(saved!!.start, saved!!.end).toMinutes())
    assertEquals(LocalTime.of(9, 0), saved!!.start)
  }
  @Test fun allDayCardCanLandInTimetable() {
    fixture(view = "Semana")
    val source = rule.onNodeWithTag("calendar-task-${original.id}").fetchSemanticsNode().boundsInRoot
    val grid = rule.onNodeWithTag("calendar-grid-$day").fetchSemanticsNode().boundsInRoot
    begin()
    rule.onNodeWithTag("calendar-task-${original.id}").performTouchInput {
      moveTo(Offset(grid.left + 30f, grid.top + 40f) - source.topLeft); advanceEventTime(16); up()
    }
    settle()
    assertEquals(1, writes)
    assertFalse(saved!!.allDay)
    assertEquals(60L, java.time.Duration.between(saved!!.start, saved!!.end).toMinutes())
  }
  @Test fun timedCardCanLandInAllDayBand() {
    fixture(view = "Semana", timed = true)
    val source = rule.onNodeWithTag("calendar-task-${original.id}").fetchSemanticsNode().boundsInRoot
    val band = rule.onNodeWithTag("calendar-all-$day").fetchSemanticsNode().boundsInRoot
    begin()
    rule.onNodeWithTag("calendar-task-${original.id}").performTouchInput { moveTo(band.center - source.topLeft); advanceEventTime(16); up() }
    settle()
    assertEquals(1, writes)
    assertTrue(saved!!.allDay)
  }
  @Test fun edgeScrollingDoesNotMoveGhostAwayFromFinger() {
    fixture(view = "Semana")
    val source = rule.onNodeWithTag("calendar-task-${original.id}").fetchSemanticsNode().boundsInRoot
    val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
    begin()
    val destination = Offset(root.right - 5f, source.center.y)
    rule.onNodeWithTag("calendar-task-${original.id}").performTouchInput { moveTo(destination - source.topLeft); advanceEventTime(16) }
    rule.mainClock.advanceTimeByFrame()
    val before = rule.runOnIdle { drag.session!!.floating }
    repeat(30) { rule.mainClock.advanceTimeByFrame(); rule.waitForIdle() }
    rule.runOnIdle {
      assertEquals(before, drag.session!!.floating)
      assertTrue(drag.session!!.preview!!.date!! > day.plusDays(1))
      assertEquals(0, writes)
    }
    rule.onRoot().performTouchInput { cancel() }
    settle()
  }

}
