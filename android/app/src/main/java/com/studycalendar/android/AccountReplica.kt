package com.studycalendar.android

import com.studycalendar.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.UUID

fun JSONObject.copyJson() = JSONObject(toString())
private fun JSONObject.without(vararg names: String) = copyJson().apply { names.forEach(::remove) }
private fun sameJson(a: Any?, b: Any?) = when {
  a is JSONObject && b is JSONObject -> a.toString() == b.toString()
  a is JSONArray && b is JSONArray -> a.toString() == b.toString()
  else -> a == b
}

/** One account document contains both its remote base and its durable outbox.
 * Mutate a copy, persist it atomically, then publish it. Preview Room data never
 * enters this document or the authenticated queue.
 */
class AccountReplica(val document: JSONObject) {
  val owner: String get() = document.getString("owner")
  val remote: JSONObject get() = document.getJSONObject("snapshot")
  val operations: JSONArray get() = document.getJSONArray("operations")
  val pending: Int get() = operations.length()
  val conflicts: List<JSONObject> get() = operations.objects().filter { it.optJSONObject("result") != null }
  fun copy() = AccountReplica(document.copyJson())
  fun snapshot() = CloudSnapshot.parse(render(), owner)

  companion object {
    fun load(json: JSONObject, owner: String): AccountReplica {
      require(json.getString("owner") == owner) { "La copia pertenece a otra cuenta." }
      return (if (json.has("snapshot")) AccountReplica(json) else AccountReplica(JSONObject().put("owner", owner).put("snapshot", json).put("operations", JSONArray()).put("receipts", JSONObject()))).also {
        val data = it.remote.getJSONObject("data")
        if (!data.has("category")) data.put("category", JSONArray())
        it.snapshot()
      }
    }
  }

  fun replaceRemote(snapshot: JSONObject) {
    require(snapshot.getString("owner") == owner)
    CloudSnapshot.parse(snapshot, owner)
    document.put("snapshot", snapshot)
  }

  private fun rows(json: JSONObject, table: String) = json.getJSONObject("data").getJSONArray(table)
  private fun find(json: JSONObject, table: String, id: String) = rows(json, table).objects().find { it.optString(if (table == "user_settings") "user_id" else "id") == id }
  private fun replace(json: JSONObject, table: String, id: String, row: JSONObject?) {
    val list = rows(json, table).objects().filter { it.optString(if (table == "user_settings") "user_id" else "id") != id }
    json.getJSONObject("data").put(table, JSONArray(list + listOfNotNull(row)))
  }
  private fun normalized(json: JSONObject) = json.without("_sync", "_local_activation").apply {
    listOf("start_time", "end_time").forEach { key -> if (has(key) && !isNull(key)) put(key, LocalTime.parse(getString(key)).format(DateTimeFormatter.ISO_LOCAL_TIME)) }
  }

  private fun enqueue(table: String, id: String, action: String, desired: JSONObject = JSONObject(), context: JSONObject = JSONObject()) {
    require(remote.optInt("protocol") == 1) { "El servidor todavía no permite sincronizar cambios." }
    val before = find(render(), table, id) ?: if (table == "user_settings") JSONObject().put("user_id", owner).put("routine_reset_time", "00:00").put("calendar_start_time", "00:00").put("calendar_end_time", "24:00").put("week_start", "monday") else null
    val beforeState = before?.let(::normalized)
    val changes = JSONObject()
    desired.keys().forEach { key -> if (action == "create" || beforeState == null || !sameJson(beforeState.opt(key), desired.opt(key))) changes.put(key, desired.get(key)) }
    if (action == "update" && changes.length() == 0) return
    val base = if (beforeState == null) JSONObject.NULL else JSONObject().put("revision", before?.optJSONObject("_sync")?.optLong("revision") ?: 0).put("state", beforeState)
    val op = JSONObject().put("protocol", 1).put("operation_id", UUID.randomUUID().toString()).put("entity", table).put("id", id)
      .put("action", action).put("base", base).put("changes", changes).put("context", context)
    operations.put(JSONObject().put("operation", op).put("sent", false))
  }

  private fun temporal(now: Instant): JSONObject {
    val view = snapshot()
    val data = render().getJSONObject("data")
    val settings = data.getJSONArray("user_settings").objects().singleOrNull()
    return JSONObject().put("recorded_at", now.toString()).put("day", HabitClock.day(now, view.reset).date.toString())
      .put("reset_time", view.reset.format(DateTimeFormatter.ofPattern("HH:mm"))).put("settings_revision", settings?.optJSONObject("_sync")?.optLong("revision") ?: 0)
  }

  fun save(task: Task) {
    val view = snapshot()
    val before = find(render(), "tasks", task.id)
    task.projectId?.let { id ->
      val project = requireNotNull(view.projects.find { it.id == id }) { "El proyecto ya no está disponible." }.project()
      require(project.status == ProjectStatus.ACTIVE || before?.nullable("project_id") == id) { "Selecciona un proyecto activo." }
    }
    task.categoryId?.let { id -> require(view.categories.any { it.id == id }) { "La categoría ya no está disponible." } }
    Planning.save(task, view.tasks.map(TaskRow::task))
    val after = normalized(JSONObject(TaskRow.from(task).snapshot())).without("id")
      .put("all_day", task.date != null && task.allDay)
      .put("color", before?.optString("color") ?: "#4F46E5")
    enqueue("tasks", task.id, if (before == null) "create" else "update", after)
  }
  fun complete(id: String) {
    val task = requireNotNull(snapshot().tasks.find { it.id == id }) { "La tarea ya no está disponible." }.task()
    save(task.copy(completed = !task.completed))
  }
  fun delete(id: String) {
    Planning.delete(id, snapshot().tasks.map(TaskRow::task))
    enqueue("tasks", id, "delete")
  }
  fun saveSettings(settings: CalendarSettings) {
    enqueue("user_settings", owner, "update", JSONObject().put("calendar_start_time", CalendarLayout.time(settings.start)).put("calendar_end_time", CalendarLayout.time(settings.end)).put("week_start", if (settings.sunday) "sunday" else "monday"))
  }
  fun saveCategory(category: Category) {
    category.validate(snapshot().categories.map(CategoryRow::category))
    enqueue("category", category.id, if (find(render(), "category", category.id) == null) "create" else "update", JSONObject(CategoryRow.from(category).snapshot()).without("id"))
  }
  fun deleteCategory(id: String) {
    require(find(render(), "category", id) != null) { "La categoría ya no está disponible." }
    enqueue("category", id, "delete")
  }
  fun saveProject(project: Project) {
    project.validate(snapshot().projects.map(ProjectRow::project))
    enqueue("projects", project.id, if (find(render(), "projects", project.id) == null) "create" else "update", JSONObject(ProjectRow.from(project).snapshot()).without("id"))
  }
  fun deleteProject(id: String) {
    require(find(render(), "projects", id) != null) { "El proyecto ya no está disponible." }
    enqueue("projects", id, "delete")
  }
  fun saveRoutine(id: String, title: String, color: String, active: Boolean, startsOn: LocalDate, now: Instant) {
    Routine(id, title.trim(), color, active).validate()
    val before = find(render(), "routine", id)
    enqueue("routine", id, if (before == null) "create" else "update", JSONObject().put("title", title.trim()).put("color", color).put("active", active), temporal(now).put("starts_on", startsOn.toString()))
  }
  fun completeRoutine(id: String, date: LocalDate, completed: Boolean, now: Instant) {
    val view = snapshot()
    require(date == HabitClock.day(now, view.reset).date) { "El día ha cambiado. Actualiza las rutinas de hoy." }
    val record = requireNotNull(view.routines.find { it.row.id == id }) { "La rutina ya no está disponible." }
    require(record.row.routine().due(date)) { "Esta rutina no está activa para hoy." }
    if ((date in record.completedDates()) == completed) return
    val routine = requireNotNull(find(render(), "routine", id))
    val context = temporal(now).put("routine_revision", routine.optJSONObject("_sync")?.optLong("revision") ?: 0)
    routine.optString("_local_activation").takeIf { it.isNotBlank() }?.let { context.put("routine_operation_id", it) }
    val op = JSONObject().put("protocol", 1).put("operation_id", UUID.randomUUID().toString()).put("entity", "routine_completion").put("id", id).put("action", "complete")
      .put("base", JSONObject().put("revision", 0).put("state", JSONObject().put("completed", date in record.completedDates())))
      .put("changes", JSONObject().put("completed", completed)).put("context", context)
    operations.put(JSONObject().put("operation", op).put("sent", false))
  }
  fun deleteRoutine(id: String) {
    require(find(render(), "routine", id) != null) { "La rutina ya no está disponible." }
    enqueue("routine", id, "delete")
  }

  fun render(): JSONObject = remote.copyJson().also { json -> operations.objects().forEach { overlay(json, it.getJSONObject("operation")) } }

  private fun overlay(json: JSONObject, op: JSONObject) {
    val table = op.getString("entity")
    val id = op.getString("id")
    val action = op.getString("action")
    val changes = op.getJSONObject("changes")
    val context = op.getJSONObject("context")
    if (table == "routine_completion") {
      val date = context.getString("day")
      val list = rows(json, table).objects().filterNot { it.getString("routine_id") == id && it.getString("occurrence_date") == date }
      rows(json, table) // Require the complete history table to be present.
      json.getJSONObject("data").put(table, JSONArray(list + JSONObject().put("id", op.getString("operation_id")).put("user_id", owner).put("routine_id", id).put("occurrence_date", date).put("completed", changes.getBoolean("completed"))))
      return
    }
    if (action == "delete") {
      replace(json, table, id, null)
      if (table == "category") rows(json, "tasks").objects().filter { it.nullable("category_id") == id }.forEach { it.put("category_id", JSONObject.NULL) }
      if (table == "projects") json.getJSONObject("data").put("tasks", JSONArray(rows(json, "tasks").objects().filter { it.nullable("project_id") != id }))
      if (table == "routine") listOf("routine_period", "routine_completion").forEach { child -> json.getJSONObject("data").put(child, JSONArray(rows(json, child).objects().filter { it.getString("routine_id") != id })) }
      return
    }
    val before = find(json, table, id) ?: op.optJSONObject("base")?.optJSONObject("state")?.copyJson()?.put("_sync", op.getJSONObject("base"))
    val row = before?.copyJson() ?: JSONObject().put("id", id).put("user_id", owner)
    changes.keys().forEach { row.put(it, changes.get(it)) }
    if (table == "tasks" && before == null) {
      if (!row.has("prerequisite_ids")) row.put("prerequisite_ids", JSONArray())
      if (!row.has("completed")) row.put("completed", false)
    }
    if (table == "projects" && !row.has("status")) row.put("status", "active")
    if (table == "routine" && (before == null || (changes.has("active") && before.getBoolean("active") != changes.getBoolean("active")))) {
      val active = row.getBoolean("active")
      val day = LocalDate.parse(context.getString(if (before == null) "starts_on" else "day"))
      val previous = rows(json, "routine_period").objects().filter { it.getString("routine_id") == id }
      val periods = if (active) previous + JSONObject().put("id", op.getString("operation_id")).put("routine_id", id).put("user_id", owner).put("starts_on", day.toString()).put("ends_on", JSONObject.NULL)
        else previous.mapNotNull { period ->
          if (!period.isNull("ends_on")) period else if (LocalDate.parse(period.getString("starts_on")) >= day) null else period.copyJson().put("ends_on", day.minusDays(1).toString())
        }
      json.getJSONObject("data").put("routine_period", JSONArray(rows(json, "routine_period").objects().filter { it.getString("routine_id") != id } + periods))
      row.put("periods", JSONArray(periods.sortedBy { it.getString("starts_on") }.map { period -> JSONObject().put("start", period.getString("starts_on")).put("end", period.opt("ends_on") ?: JSONObject.NULL) }))
      if (active) row.put("_local_activation", op.getString("operation_id"))
    }
    replace(json, table, id, row)
  }

  fun prepareNext(): JSONObject? {
    val entry = operations.objects().firstOrNull() ?: return null
    if (entry.has("result")) return null
    val op = entry.getJSONObject("operation")
    if (!entry.getBoolean("sent")) {
      op.getJSONObject("context").optString("routine_operation_id").takeIf { it.isNotBlank() }?.let { previous ->
        val receipt = document.getJSONObject("receipts").optJSONObject(previous)
        val revision = receipt?.optJSONObject("row")?.optJSONObject("_sync")?.optLong("revision")
        require(revision != null && revision > 0) { "Primero sincroniza la activación de la rutina." }
        op.getJSONObject("context").put("routine_revision", revision)
      }
      entry.put("sent", true)
    }
    return op.copyJson()
  }

  fun acknowledge(result: JSONObject) {
    val first = operations.getJSONObject(0)
    val op = first.getJSONObject("operation")
    if (result.getString("status") != "applied") { first.put("result", result); return }
    val updated = remote.copyJson()
    overlay(updated, op)
    val row = result.optJSONObject("row")
    val table = op.getString("entity")
    if (row != null && table != "routine_completion" && op.getString("action") != "delete") {
      val canonical = if (table == "routine") row.getJSONObject("_sync").getJSONObject("state").copyJson().put("_sync", row.getJSONObject("_sync")) else row
      replace(updated, table, op.getString("id"), canonical)
    }
    document.getJSONObject("receipts").put(op.getString("operation_id"), result)
    document.put("snapshot", updated)
    document.put("operations", JSONArray(operations.objects().drop(1)))
  }

  fun resolve(operationId: String, keepLocalFields: Set<String>?) {
    val index = operations.objects().indexOfFirst { it.getJSONObject("operation").getString("operation_id") == operationId }
    require(index >= 0) { "El conflicto ya no está disponible." }
    val entry = operations.getJSONObject(index)
    val op = entry.getJSONObject("operation")
    val result = entry.getJSONObject("result")
    val remaining = operations.objects().toMutableList()
    if (keepLocalFields == null || (op.getString("action") == "delete" && "delete" !in keepLocalFields)) remaining.removeAt(index)
    else {
      val remoteState = result.optJSONObject("remote")
      require(remoteState?.optJSONObject("state") != null) { "El elemento eliminado no puede recrearse. Descarta este cambio y revisa el resto." }
      require(result.optString("reason") == "fields") { "Revisa los datos antes de volver a enviar el cambio." }
      val changes = op.getJSONObject("changes").copyJson()
      result.optJSONArray("fields")?.let { fields -> fields.objectsStrings().filter { it !in keepLocalFields }.forEach(changes::remove) }
      if (op.getString("action") != "delete" && changes.length() == 0) remaining.removeAt(index)
      else {
        val replacement = op.copyJson().put("operation_id", UUID.randomUUID().toString()).put("base", remoteState).put("changes", changes)
        remaining[index] = JSONObject().put("operation", replacement).put("sent", false)
      }
    }
    document.put("operations", JSONArray(remaining))
  }
}

fun JSONArray.objectsStrings(): List<String> = (0 until length()).map { getString(it) }
