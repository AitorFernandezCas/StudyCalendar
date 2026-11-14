package com.studycalendar.core

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class DomainTest {
  private val today = LocalDate.of(2026, 11, 13)
  @Test fun `missing DST reset advances to first real minute`() {
    assertEquals(Instant.parse("2026-03-29T01:00:00Z"), HabitClock.boundary(LocalDate.of(2026, 3, 29), LocalTime.of(2, 30)))
  }
  @Test fun `repeated reset uses first occurrence and boundaries are contiguous`() {
    val date = LocalDate.of(2026, 10, 25)
    assertEquals(Instant.parse("2026-10-25T00:30:00Z"), HabitClock.boundary(date, LocalTime.of(2, 30)))
    val before = HabitClock.day(Instant.parse("2026-10-25T00:29:59Z"), LocalTime.of(2, 30))
    val after = HabitClock.day(before.endsAt, LocalTime.of(2, 30))
    assertEquals(date.minusDays(1), before.date)
    assertEquals(date, after.date)
    assertEquals(before.endsAt, after.startsAt)
  }
  @Test fun `pending today preserves streak yesterday and future is ignored`() {
    val periods = listOf(ActivePeriod(today.minusDays(10)))
    val completed = setOf(today.minusDays(2), today.minusDays(1), today.plusDays(1))
    assertEquals(Streak(2, 2), streaks(today, true, periods, completed))
    assertEquals(Streak(0, 2), streaks(today, false, periods, completed))
    assertEquals(Streak(0, 1), streaks(today, true, periods, completed - today.minusDays(1)))
  }
  @Test fun `unmarking recalculates maximum without pagination limit`() {
    val dates = (0L..1500L).map { today.minusDays(it) }.toSet()
    val periods = listOf(ActivePeriod(today.minusDays(2000)))
    assertEquals(Streak(1501, 1501), streaks(today, true, periods, dates))
    assertEquals(Streak(10, 1490), streaks(today, true, periods, dates - today.minusDays(10)))
  }
  @Test fun `merge different fields explicit null and identical concurrent changes`() {
    val base = mapOf("title" to "Antes", "project_id" to "p")
    val result = mergeFields(base, mapOf("title" to "Después", "project_id" to null), base + ("hour" to "19:00"))
    assertTrue(result.conflicts.isEmpty())
    assertEquals("19:00", result.values["hour"])
    assertTrue(result.values.containsKey("project_id"))
    assertNull(result.values["project_id"])
    assertTrue(mergeFields(base, mapOf("title" to "Igual"), base + ("title" to "Igual")).conflicts.isEmpty())
  }
  @Test fun `same field conflicts and remote deletion never recreates entity`() {
    val base = mapOf("title" to "Antes")
    assertEquals(setOf("title"), mergeFields(base, mapOf("title" to "Móvil"), mapOf("title" to "Web")).conflicts)
    assertTrue(mergeFields(base, mapOf("title" to "Móvil"), null).deletedRemotely)
    assertTrue(deletionConflicts(base, mapOf("title" to "Web")))
  }
  @Test fun `planning enforces requirements reopen deletion cycles and project isolation`() {
    val a = Task(id = "a", title = "A", date = today, projectId = "p", projectTask = true)
    val b = a.copy(id = "b", prerequisiteIds = setOf("a"))
    assertThrows(IllegalArgumentException::class.java) { Planning.save(b.copy(completed = true), listOf(a, b)) }
    assertThrows(IllegalArgumentException::class.java) { Planning.save(a.copy(prerequisiteIds = setOf("b")), listOf(a, b)) }
    assertThrows(IllegalArgumentException::class.java) { Planning.save(b.copy(projectId = "other"), listOf(a, b)) }
    assertThrows(IllegalArgumentException::class.java) { Planning.delete("a", listOf(a, b)) }
    assertThrows(IllegalArgumentException::class.java) { Planning.save(a, listOf(a.copy(completed = true), b.copy(completed = true))) }
    Planning.save(b.copy(completed = true), listOf(a.copy(completed = true), b))
  }
}
