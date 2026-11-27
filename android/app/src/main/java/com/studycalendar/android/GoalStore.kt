package com.studycalendar.android

import androidx.room.*
import com.studycalendar.core.*
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import java.math.BigDecimal
import java.time.*

@Entity(tableName = "goals")
data class GoalRow(@PrimaryKey val id: String, val payload: String) {
  fun goal() = parse(JSONObject(payload))
  companion object {
    fun parse(j: JSONObject) = Goal(j.getString("id"), j.getString("title"), GoalPeriod.valueOf(j.getString("period")), LocalDate.parse(j.getString("starts_on")), j.opt("target").takeUnless { it == null || it == JSONObject.NULL }?.toString()?.let(::BigDecimal), j.getString("unit"), j.getBoolean("achieved"))
  }
}
@Entity(tableName = "goal_reviews")
data class GoalReviewRow(@PrimaryKey val id: String, val goalId: String, val payload: String) {
  fun review() = parse(JSONObject(payload))
  companion object {
    fun parse(j: JSONObject) = GoalReview(j.getString("id"), j.getString("goal_id"), Instant.parse(j.getString("recorded_at")), j.opt("amount").takeUnless { it == null || it == JSONObject.NULL }?.toString()?.let(::BigDecimal), j.getString("note"))
  }
}
@Entity(tableName = "goal_schedules")
data class GoalScheduleRow(@PrimaryKey val id: String, val payload: String) {
  fun schedule() = parse(JSONObject(payload))
  companion object {
    fun parse(j: JSONObject) = GoalSchedule(j.getString("id"), GoalPeriod.valueOf(j.getString("period")), j.getBoolean("enabled"), ReviewFrequency.valueOf(j.getString("frequency")), LocalTime.parse(j.getString("time")), j.getInt("weekday"), j.getInt("month_day"))
  }
}
fun Goal.json() = JSONObject().put("id", id).put("title", title.trim()).put("period", period.name).put("starts_on", startsOn.toString()).put("target", target ?: JSONObject.NULL).put("unit", unit.trim()).put("achieved", achieved)
fun GoalReview.json() = JSONObject().put("id", id).put("goal_id", goalId).put("recorded_at", recordedAt.toString()).put("amount", amount ?: JSONObject.NULL).put("note", note.trim())
fun GoalSchedule.json() = JSONObject().put("id", id).put("period", period.name).put("enabled", enabled).put("frequency", frequency.name).put("time", time.format(java.time.format.DateTimeFormatter.ISO_LOCAL_TIME)).put("weekday", weekday).put("month_day", monthDay)

@Dao
interface GoalDao {
  @Query("SELECT * FROM goals") fun observeGoals(): Flow<List<GoalRow>>
  @Query("SELECT * FROM goal_reviews") fun observeReviews(): Flow<List<GoalReviewRow>>
  @Query("SELECT * FROM goal_schedules") fun observeSchedules(): Flow<List<GoalScheduleRow>>
  @Query("SELECT * FROM goals WHERE id = :id") suspend fun goal(id: String): GoalRow?
  @Upsert suspend fun put(row: GoalRow)
  @Insert suspend fun review(row: GoalReviewRow)
  @Upsert suspend fun schedule(row: GoalScheduleRow)
  @Query("DELETE FROM goals WHERE id = :id") suspend fun delete(id: String)
  @Query("DELETE FROM goal_reviews WHERE goalId = :id") suspend fun deleteReviews(id: String)
}

interface GoalCommands {
  suspend fun saveGoal(value: Goal, original: Goal?)
  suspend fun deleteGoal(value: Goal)
  suspend fun reviewGoal(value: GoalReview)
  suspend fun saveGoalSchedule(value: GoalSchedule)
}

class LocalGoals(private val database: CalendarDatabase) : GoalCommands {
  val goals = database.goals().observeGoals()
  val reviews = database.goals().observeReviews()
  val schedules = database.goals().observeSchedules()
  override suspend fun saveGoal(value: Goal, original: Goal?) = database.withTransaction {
    value.validate()
    require(database.goals().goal(value.id)?.goal() == original) { "El objetivo ha cambiado. Ábrelo de nuevo." }
    database.goals().put(GoalRow(value.id, value.json().toString()))
  }
  override suspend fun deleteGoal(value: Goal) = database.withTransaction {
    require(database.goals().goal(value.id)?.goal() == value) { "El objetivo ha cambiado. Ábrelo de nuevo." }
    database.goals().deleteReviews(value.id)
    database.goals().delete(value.id)
  }
  override suspend fun reviewGoal(value: GoalReview) = database.withTransaction {
    value.validate()
    require(database.goals().goal(value.goalId) != null) { "El objetivo ya no está disponible." }
    database.goals().review(GoalReviewRow(value.id, value.goalId, value.json().toString()))
  }
  override suspend fun saveGoalSchedule(value: GoalSchedule) {
    value.validate()
    database.goals().schedule(GoalScheduleRow(value.id, value.json().toString()))
  }
}
