package com.studycalendar.core

import java.math.BigDecimal
import java.time.*
import java.time.temporal.TemporalAdjusters

enum class GoalPeriod(val label: String) { WEEK("Semanal"), MONTH("Mensual"), YEAR("Anual");
  fun start(date: LocalDate): LocalDate = when (this) {
    WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    MONTH -> date.withDayOfMonth(1)
    YEAR -> date.withDayOfYear(1)
  }
  fun end(start: LocalDate): LocalDate = when (this) {
    WEEK -> start.plusWeeks(1)
    MONTH -> start.plusMonths(1)
    YEAR -> start.plusYears(1)
  }.minusDays(1)
}

data class Goal(val id: String, val title: String, val period: GoalPeriod, val startsOn: LocalDate,
  val target: BigDecimal? = null, val unit: String = "", val achieved: Boolean = false) {
  val endsOn get() = period.end(startsOn)
  fun validate() {
    require(title.isNotBlank()) { "Introduce un título." }
    require(startsOn.year in 1..9999) { "Selecciona un año entre 1 y 9999." }
    require(startsOn == period.start(startsOn)) { "Selecciona el inicio del período." }
    require(target == null || target > BigDecimal.ZERO) { "La meta debe ser mayor que cero." }
  }
  fun active(today: LocalDate) = !achieved && today >= startsOn && today <= endsOn
}

data class GoalReview(val id: String, val goalId: String, val recordedAt: Instant,
  val amount: BigDecimal? = null, val note: String = "") {
  fun validate() { require(amount == null || amount >= BigDecimal.ZERO) { "El progreso debe ser cero o mayor." } }
}

enum class ReviewFrequency(val label: String) { DAILY("Diaria"), WEEKLY("Semanal"), MONTHLY("Mensual") }
data class GoalSchedule(val id: String, val period: GoalPeriod, val enabled: Boolean = false,
  val frequency: ReviewFrequency = when (period) { GoalPeriod.WEEK -> ReviewFrequency.DAILY; GoalPeriod.MONTH -> ReviewFrequency.WEEKLY; GoalPeriod.YEAR -> ReviewFrequency.MONTHLY },
  val time: LocalTime = LocalTime.of(20, 0), val weekday: Int = 7, val monthDay: Int = 31) {
  fun validate() { require(weekday in 1..7 && monthDay in 1..31) { "Selecciona un día válido." } }
  fun window(date: LocalDate) = when (frequency) {
    ReviewFrequency.DAILY -> date
    ReviewFrequency.WEEKLY -> GoalPeriod.WEEK.start(date)
    ReviewFrequency.MONTHLY -> date.withDayOfMonth(1)
  }
  fun latest(now: Instant): LocalDate? {
    val date = now.atZone(GoalReminders.zone).toLocalDate()
    return (0L..32L).asSequence().map { date.minusDays(it) }.filter(::occurs)
      .firstOrNull { it.atTime(time).atZone(GoalReminders.zone).toInstant() <= now }
  }
  fun occurs(date: LocalDate) = when (frequency) {
    ReviewFrequency.DAILY -> true
    ReviewFrequency.WEEKLY -> date.dayOfWeek.value == weekday
    ReviewFrequency.MONTHLY -> date.dayOfMonth == minOf(monthDay, date.lengthOfMonth())
  }
}

object GoalReminders {
  val zone: ZoneId = ZoneId.of("Europe/Madrid")
  fun due(goals: List<Goal>, reviews: List<GoalReview>, schedules: List<GoalSchedule>, now: Instant): List<Goal> {
    val date = now.atZone(zone).toLocalDate()
    return goals.filter { goal ->
      val schedule = schedules.find { it.period == goal.period && it.enabled }
      val last = schedule?.latest(now)
      goal.active(date) && schedule != null && last != null && last >= goal.startsOn &&
        reviews.none { it.goalId == goal.id && it.recordedAt.atZone(zone).toLocalDate() >= schedule.window(last) }
    }
  }
  fun next(schedules: List<GoalSchedule>, now: Instant): Instant? = schedules.filter { it.enabled }.mapNotNull { schedule ->
    val date = now.atZone(zone).toLocalDate()
    (0L..32L).asSequence().map { date.plusDays(it) }.filter(schedule::occurs)
      .map { it.atTime(schedule.time).atZone(zone).toInstant() }.firstOrNull { it > now }
  }.minOrNull()
}
