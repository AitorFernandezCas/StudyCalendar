package com.studycalendar.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.studycalendar.core.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class CalendarSettingsTest {
  private val user = "77777777-7777-4777-8777-777777777777"
  private fun document() = JSONObject().put("owner", user).put("protocol", 1).put("downloadedAt", "2026-11-16T00:00:00Z").put("data", JSONObject().apply {
    listOf("tasks", "projects", "category", "routine", "routine_period", "routine_completion", "user_settings").forEach { put(it, JSONArray()) }
  })
  @Test fun firstOfflineSettingsHaveDefaultBaseAndSurviveReopen() {
    val replica = AccountReplica.load(document(), user)
    replica.saveSettings(CalendarSettings(480, 1200, true))
    val reloaded = AccountReplica.load(replica.document.copyJson(), user)
    assertEquals(CalendarSettings(480, 1200, true), reloaded.snapshot().calendar)
    assertEquals(java.time.LocalTime.MIDNIGHT, reloaded.snapshot().reset)
    val op = reloaded.prepareNext()!!
    assertEquals("user_settings", op.getString("entity"))
    assertEquals(user, op.getString("id"))
    assertEquals("monday", op.getJSONObject("base").getJSONObject("state").getString("week_start"))
    assertFalse(op.getJSONObject("changes").has("routine_reset_time"))
  }
  @Test fun settingsPreserveHabitResetAndResolveFieldConflict() {
    val json = document()
    val row = JSONObject().put("user_id", user).put("routine_reset_time", "04:00").put("calendar_start_time", "00:00").put("calendar_end_time", "24:00").put("week_start", "monday")
    row.put("_sync", JSONObject().put("revision", 1).put("state", row.copyJson()))
    json.getJSONObject("data").put("user_settings", JSONArray().put(row))
    val replica = AccountReplica.load(json, user)
    replica.saveSettings(CalendarSettings(480, 1200, true))
    assertEquals(java.time.LocalTime.of(4, 0), replica.snapshot().reset)
    val op = replica.prepareNext()!!
    val remote = row.copyJson().put("calendar_start_time", "09:00").apply { remove("_sync") }
    replica.acknowledge(JSONObject().put("status", "conflict").put("reason", "fields").put("fields", JSONArray().put("calendar_start_time")).put("remote", JSONObject().put("revision", 2).put("state", remote)))
    replica.resolve(op.getString("operation_id"), setOf("calendar_start_time"))
    assertEquals(2, replica.prepareNext()!!.getJSONObject("base").getInt("revision"))
    assertEquals(java.time.LocalTime.of(4, 0), replica.snapshot().reset)
  }
  @Test fun undoCannotOverwriteAnEditOrRecreateDeletedTask() = kotlinx.coroutines.runBlocking {
    val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
    val db = androidx.room.Room.inMemoryDatabaseBuilder(context, CalendarDatabase::class.java).build()
    try {
      val store = LocalCalendar(db)
      val task = Task(title = "Mover", date = LocalDate.of(2026, 11, 16))
      store.save(task)
      val moved = CalendarLayout.move(task, task.date!!.plusDays(1), 600)!!
      store.reschedule(task, moved)
      assertEquals(moved, db.calendar().tasks().single().task())
      store.save(moved.copy(title = "Edición posterior"))
      try { store.reschedule(moved, task); fail("Stale undo") } catch (_: IllegalArgumentException) { }
      assertEquals("Edición posterior", db.calendar().tasks().single().title)
      store.delete(task.id)
      try { store.reschedule(moved, task); fail("Deleted task") } catch (_: IllegalArgumentException) { }
      assertTrue(db.calendar().tasks().isEmpty())
    } finally { db.close() }
  }

}
