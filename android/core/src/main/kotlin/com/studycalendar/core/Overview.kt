package com.studycalendar.core

import java.text.Collator
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Presentation rules shared by the overview and its navigation badge. */
object Overview {
  private val spanish = Locale.forLanguageTag("es-ES")
  fun compareNames(a: String, b: String): Int = Collator.getInstance(spanish).compare(a, b)
  val order: Comparator<Task> = compareBy<Task> { it.date }.thenByDescending { it.allDay }
    .thenBy { it.start }.thenComparator { a, b -> compareNames(a.title, b.title) }.thenBy { it.id }

  fun pending(tasks: List<Task>, projects: List<Project>): List<Task> {
    val hidden = projects.filter { it.status != ProjectStatus.ACTIVE }.map { it.id }.toSet()
    return tasks.filter { !it.completed && it.date != null && (!it.projectTask || it.projectId !in hidden) }.sortedWith(order)
  }

  fun dailyGroups(tasks: List<Task>, today: LocalDate): List<Pair<String, List<Task>>> {
    val daily = tasks.filter { !it.projectTask && !it.completed && it.date != null }.sortedWith(order)
    return listOf("Atrasadas" to daily.filter { it.date!! < today }, "Hoy" to daily.filter { it.date == today },
      "Próximas" to daily.filter { it.date!! > today })
  }

  fun pendingCount(tasks: List<Task>, projects: List<Project>, pendingHabits: Int) = pending(tasks, projects).size + pendingHabits

  fun relativeDate(date: LocalDate?, today: LocalDate): String {
    if (date == null) return "Sin fecha"
    val days = ChronoUnit.DAYS.between(today, date)
    return when {
      days == 0L -> "Hoy"
      days == 1L -> "Mañana"
      days == -1L -> "Ayer"
      days < 0 -> "Hace ${-days} días"
      else -> date.format(DateTimeFormatter.ofPattern(if (date.year == today.year) "d MMM" else "d MMM yyyy", spanish))
    }
  }

  fun endWithDuration(start: LocalTime, previousStart: LocalTime, previousEnd: LocalTime): LocalTime {
    val duration = ChronoUnit.MINUTES.between(previousStart, previousEnd).coerceAtLeast(1)
    return LocalTime.ofSecondOfDay(((start.toSecondOfDay() / 60 + duration).coerceAtMost(1439)) * 60)
  }
}
