package com.studycalendar.android

import androidx.room.*
import com.studycalendar.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

@Entity(tableName = "projects")
data class ProjectRow(@PrimaryKey val id: String, val name: String, val color: String, val status: String) {
  fun project() = Project(id, name, color, ProjectStatus.valueOf(status))
  fun snapshot() = JSONObject().put("id", id).put("name", name).put("color", color).put("status", status.lowercase()).toString()
  companion object {
    fun from(project: Project) = ProjectRow(project.id, project.name.trim(), project.color, project.status.name)
  }
}

@Entity(tableName = "routines")
data class RoutineRow(@PrimaryKey val id: String, val title: String, val color: String, val active: Boolean, val periods: String) {
  fun routine() = Routine(id, title, color, active, JSONArray(periods).let { array ->
    (0 until array.length()).map { index -> array.getJSONObject(index).let {
      ActivePeriod(LocalDate.parse(it.getString("start")), if (it.isNull("end")) null else LocalDate.parse(it.getString("end")))
    } }
  })
  fun snapshot() = JSONObject().put("id", id).put("title", title).put("color", color).put("active", active).put("periods", JSONArray(periods)).toString()
  companion object {
    fun from(routine: Routine) = RoutineRow(routine.id, routine.title.trim(), routine.color, routine.active,
      JSONArray(routine.periods.map { JSONObject().put("start", it.start.toString()).put("end", it.end?.toString() ?: JSONObject.NULL) }).toString())
  }
}

@Entity(tableName = "routine_completions", primaryKeys = ["routineId", "date"],
  foreignKeys = [ForeignKey(entity = RoutineRow::class, parentColumns = ["id"], childColumns = ["routineId"], onDelete = ForeignKey.CASCADE)],
  indices = [Index("routineId")])
data class CompletionRow(val routineId: String, val date: String, val completed: Boolean) {
  fun snapshot() = JSONObject().put("routine_id", routineId).put("date", date).put("completed", completed).toString()
}

data class RoutineRecord(@Embedded val row: RoutineRow,
  @Relation(parentColumn = "id", entityColumn = "routineId") val completions: List<CompletionRow>) {
  fun completedDates() = completions.filter { it.completed }.map { LocalDate.parse(it.date) }.toSet()
}
