package com.studycalendar.core

import java.time.*
import java.util.UUID

data class HabitAction(
  val operationId: String = UUID.randomUUID().toString(),
  val userId: String,
  val routineId: String,
  val day: LocalDate,
  val recordedAt: Instant,
  val resetTime: LocalTime,
  val completed: Boolean,
)

sealed interface HabitActionResult {
  data class Recorded(val action: HabitAction) : HabitActionResult
  data object OpenCurrentDay : HabitActionResult
}

fun recordHabitAction(
  userId: String,
  routineId: String,
  now: Instant,
  reset: LocalTime,
  active: Boolean,
  periods: List<ActivePeriod>,
  completed: Boolean,
  notificationDay: LocalDate? = null,
): HabitActionResult {
  val day = HabitClock.day(now, reset).date
  if (notificationDay != null && notificationDay != day) return HabitActionResult.OpenCurrentDay
  require(userId.isNotBlank() && routineId.isNotBlank()) { "Identifica la cuenta y el hábito." }
  require(active && periods.any { it.includes(day) }) { "Este hábito no está disponible hoy." }
  return HabitActionResult.Recorded(HabitAction(userId = userId, routineId = routineId, day = day, recordedAt = now, resetTime = reset, completed = completed))
}

sealed interface ReminderRule {
  data class BeforeStart(val minutes: Long = 10) : ReminderRule
  data class AtTime(val time: LocalTime) : ReminderRule
}

fun taskReminder(task: Task, rule: ReminderRule): Instant? {
  if (task.completed || task.date == null) return null
  return when (rule) {
    is ReminderRule.BeforeStart -> {
      require(rule.minutes >= 0) { "La antelación no puede ser negativa." }
      require(!task.allDay && task.start != null) { "Selecciona una hora de aviso para tareas de todo el día." }
      HabitClock.boundary(task.date, task.start).minusSeconds(Math.multiplyExact(rule.minutes, 60))
    }
    is ReminderRule.AtTime -> HabitClock.boundary(task.date, rule.time)
  }
}

fun postponeReminder(now: Instant) = now.plusSeconds(10 * 60)

enum class ReminderCapability { DISABLED, APPROXIMATE, PRECISE }
fun reminderCapability(notificationsAllowed: Boolean, exactAlarmsAllowed: Boolean) = when {
  !notificationsAllowed -> ReminderCapability.DISABLED
  !exactAlarmsAllowed -> ReminderCapability.APPROXIMATE
  else -> ReminderCapability.PRECISE
}
