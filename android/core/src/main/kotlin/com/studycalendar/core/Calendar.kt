package com.studycalendar.core

import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt

data class CalendarSettings(val start: Int = 0, val end: Int = 1440, val sunday: Boolean = false) {
  init { require(start in 0..1439 && end in 1..1440 && end > start) { "El fin del horario debe ser posterior al inicio." } }
}

object CalendarLayout {
  fun week(date: LocalDate, sunday: Boolean) = date.minusDays(((date.dayOfWeek.value - (if (sunday) 7 else 1) + 7) % 7).toLong())
  fun month(date: LocalDate, sunday: Boolean): List<LocalDate> = week(date.withDayOfMonth(1), sunday).let { first -> (0L..41L).map(first::plusDays) }
  fun visible(task: Task, settings: CalendarSettings) = task.allDay || (task.start != null && task.end != null && minute(task.start) < settings.end && minute(task.end) > settings.start)
  fun minute(time: LocalTime) = time.hour * 60 + time.minute
  fun time(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)
  fun parse(value: String): Int {
    require(Regex("(?:[01]\\d|2[0-3]):[0-5]\\d|24:00").matches(value)) { "Usa horas válidas: HH:MM." }
    return value.take(2).toInt() * 60 + value.takeLast(2).toInt()
  }
  fun draft(date: LocalDate, minute: Int?): Task {
    if (minute == null) return Task(title = "", date = date)
    require(minute in 0..1438) { "No queda tiempo para una tarea en esta fecha." }
    val end = minOf(1439, minute + 60)
    return Task(title = "", date = date, allDay = false, start = LocalTime.of(minute / 60, minute % 60), end = LocalTime.of(end / 60, end % 60))
  }
  fun move(task: Task, date: LocalDate, target: Int?, allDay: Boolean = false): Task? {
    if (target == null) return task.copy(date = date, allDay = if (allDay) true else task.allDay)
    val duration = if (task.allDay) 60 else task.end?.let { minute(it) - minute(requireNotNull(task.start)) } ?: return null
    if (duration !in 1..1439) return null
    val latest = ((1439 - duration) / 15) * 15
    val start = ((target / 15.0).roundToInt() * 15).coerceIn(0, latest)
    return task.copy(date = date, allDay = false, start = LocalTime.of(start / 60, start % 60), end = LocalTime.of((start + duration) / 60, (start + duration) % 60))
  }
  data class Lane(val task: Task, val column: Int, val columns: Int)
  fun lanes(tasks: List<Task>): List<Lane> {
    val result = mutableListOf<Lane>()
    var group = mutableListOf<Pair<Task, Int>>()
    val ends = mutableListOf<LocalTime>()
    fun flush() { group.forEach { (task, column) -> result += Lane(task, column, ends.size) }; group = mutableListOf(); ends.clear() }
    tasks.filter { !it.allDay && it.start != null && it.end != null }.sortedWith(compareBy<Task> { it.start }.thenBy { it.id }).forEach { task ->
      if (ends.isNotEmpty() && ends.all { it <= task.start!! }) flush()
      var column = ends.indexOfFirst { it <= task.start!! }
      if (column < 0) { column = ends.size; ends += task.end!! } else ends[column] = task.end!!
      group += task to column
    }
    flush()
    return result
  }
}
