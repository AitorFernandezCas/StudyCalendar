package com.studycalendar.core

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class OverviewTest {
  private val today = LocalDate.of(2026, 12, 31)
  private fun task(id: String) = Task(id = id, title = id, date = today)

  @Test fun overviewAndBadgeUseDatedPendingWorkFromActiveProjectsOnly() {
    val projects = ProjectStatus.entries.map { Project(id = it.name, name = it.name, status = it) }
    val tasks = projects.map { task(it.id).copy(projectTask = true, projectId = it.id) } + listOf(
      task("Personal"), task("Unassigned").copy(projectTask = true), task("Done").copy(completed = true),
      task("Undated").copy(projectTask = true, date = null, start = null, end = null),
      task("Far future").copy(date = today.plusYears(1)))
    assertEquals(setOf("ACTIVE", "Personal", "Unassigned", "Far future"), Overview.pending(tasks, projects).map { it.id }.toSet())
    assertEquals(6, Overview.pendingCount(tasks, projects, 2))
    assertEquals(8, tasks.size) // Filtering must not alter calendar/project history.
  }

  @Test fun dateGroupsAndRelativeDatesCrossMidnightAndYear() {
    val tasks = listOf(task("Yesterday").copy(date = today.minusDays(1)), task("Today"), task("Tomorrow").copy(date = today.plusDays(1)))
    assertEquals(listOf(listOf("Yesterday"), listOf("Today"), listOf("Tomorrow")), Overview.dailyGroups(tasks, today).map { it.second.map(Task::id) })
    assertEquals(listOf("Yesterday", "Today"), Overview.dailyGroups(tasks, today.plusDays(1)).first().second.map { it.id })
    assertEquals("Mañana", Overview.relativeDate(today.plusDays(1), today))
    assertEquals("Ayer", Overview.relativeDate(today.minusDays(1), today))
    assertEquals("Sin fecha", Overview.relativeDate(null, today))
  }

  @Test fun orderUsesDateAllDayTimeSpanishTitleAndStableId() {
    val tasks = listOf(task("z").copy(title = "Álgebra"), task("a").copy(title = "Álgebra"), task("b").copy(title = "Biología"),
      task("early").copy(allDay = false, start = LocalTime.of(8, 0)), task("late").copy(allDay = false, start = LocalTime.of(12, 0)))
    assertEquals(listOf("a", "z", "b", "early", "late"), Overview.pending(tasks.reversed(), emptyList()).map { it.id })
  }

  @Test fun changingStartPreservesDurationAndClampsToDayEnd() {
    assertEquals(LocalTime.of(12, 30), Overview.endWithDuration(LocalTime.of(11, 0), LocalTime.of(9, 0), LocalTime.of(10, 30)))
    assertEquals(LocalTime.of(23, 59), Overview.endWithDuration(LocalTime.of(23, 30), LocalTime.of(9, 0), LocalTime.of(10, 30)))
  }
}
