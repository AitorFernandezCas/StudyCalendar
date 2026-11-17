package com.studycalendar.android

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.studycalendar.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ProjectGraphCommandsTest {
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val project = Project(id = "graph-project", name = "Plan de prueba")
  private fun task(id: String) = Task(id = id, title = id, date = LocalDate.of(2026, 11, 16), projectTask = true, projectId = project.id)

  @Test fun localDependencyEditIsAtomicPreservesFieldsAndRejectsStaleLinks() = runBlocking {
    val database = Room.inMemoryDatabaseBuilder(context, CalendarDatabase::class.java).build()
    try {
      val store = LocalCalendar(database)
      store.saveProject(project)
      val a = task("a")
      val b = task("b")
      store.save(a); store.save(b)
      store.save(b.copy(title = "Título nuevo", date = b.date!!.plusDays(1)))
      store.setPrerequisites(b.id, project.id, emptySet(), setOf(a.id))
      val current = database.calendar().tasks().first { it.id == b.id }.task()
      assertEquals("Título nuevo", current.title)
      assertEquals(b.date!!.plusDays(1), current.date)
      assertEquals(setOf(a.id), current.prerequisiteIds)
      try { store.setPrerequisites(b.id, project.id, emptySet(), emptySet()); fail("Stale links") } catch (_: IllegalArgumentException) { }
      try { store.setPrerequisites(a.id, project.id, emptySet(), setOf(b.id)); fail("Cycle") } catch (_: IllegalArgumentException) { }
      try { store.setCompletion(b.id, false, true); fail("Blocked") } catch (_: IllegalArgumentException) { }
      try { store.delete(a.id); fail("Linked deletion") } catch (_: IllegalArgumentException) { }
      assertEquals(current, database.calendar().tasks().first { it.id == b.id }.task())
      store.setPrerequisites(b.id, project.id, setOf(a.id), emptySet())
      store.delete(a.id)
      assertEquals(1, database.calendar().tasks().size)
    } finally { database.close() }
  }

  @Test fun accountLinksSurviveOfflineRestartMergeFieldsAndResolveConflicts() = runBlocking {
    val user = "77777777-7777-4777-8777-777777777777"
    val baseContext = context
    val ctx = object : ContextWrapper(baseContext) {
      override fun getFilesDir() = java.io.File(baseContext.cacheDir, "graph-sync-fixture").apply { mkdirs() }
      override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences("graph-sync-fixture-$name", mode)
    }
    ctx.getSharedPreferences("supabase-session", 0).edit().clear().commit()
    listOf(".json", ".json.bak").forEach { java.io.File(ctx.filesDir, "account-cache/$user$it").delete() }
    fun row(task: Task) = JSONObject(TaskRow.from(task).snapshot()).put("user_id", user).put("color", "#7056ED")
    val remote = listOf(task("a"), task("b"), task("c")).associate { it.id to row(it) }
    var revision = 1L
    var offline = false
    fun wrap(row: JSONObject) = row.copyJson().put("_sync", JSONObject().put("revision", revision).put("state", row.copyJson()))
    val sent = mutableListOf<JSONObject>()
    val transport = CloudTransport { path, _, _, body ->
      if (path.startsWith("/auth/")) JSONObject().put("user", JSONObject().put("id", user).put("email", "graph@example.invalid"))
        .put("access_token", "test").put("refresh_token", "test-refresh").put("expires_in", 3600).toString()
      else {
        if (offline) throw java.io.IOException("offline fixture")
        if (path.endsWith("sync_snapshot")) JSONObject().put("protocol", 1).put("owner", user).put("downloadedAt", Instant.now().toString()).put("data", JSONObject().apply {
          put("tasks", JSONArray(remote.values.map(::wrap)))
          put("projects", JSONArray().put(wrap(JSONObject(ProjectRow.from(project).snapshot()).put("user_id", user))))
          listOf("category", "routine", "routine_period", "routine_completion", "user_settings").forEach { put(it, JSONArray()) }
        }).toString()
        else {
          val op = body!!.getJSONObject("p_operation")
          sent.add(op.copyJson())
          val changes = op.getJSONObject("changes")
          val current = remote.getValue(op.getString("id"))
          val base = op.getJSONObject("base").getJSONObject("state")
          val conflicts = changes.keys().asSequence().filter { key -> base.opt(key).toString() != current.opt(key).toString() && changes.opt(key).toString() != current.opt(key).toString() }.toList()
          if (conflicts.isNotEmpty()) JSONObject().put("status", "conflict").put("reason", "fields").put("fields", JSONArray(conflicts))
            .put("remote", JSONObject().put("revision", revision).put("state", current.copyJson())).toString()
          else {
            changes.keys().forEach { current.put(it, changes.get(it)) }; revision++
            JSONObject().put("status", "applied").put("row", wrap(current)).toString()
          }
        }
      }
    }
    val reader = CloudReader(transport)
    val connection = CloudConnection(ctx, reader)
    connection.login("graph@example.invalid", "fixture")
    offline = true
    connection.setPrerequisites("b", project.id, emptySet(), setOf("a"))
    connection.refresh()
    assertEquals(1, connection.state.value.pending)
    val restored = CloudConnection(ctx, reader)
    restored.restore()
    assertEquals(setOf("a"), restored.state.value.snapshot!!.tasks.first { it.id == "b" }.task().prerequisiteIds)
    val pending = restored.state.value.pending
    try { restored.setPrerequisites("b", project.id, emptySet(), emptySet()); fail("Stale edit") } catch (_: IllegalArgumentException) { }
    assertEquals(pending, restored.state.value.pending)
    remote.getValue("b").put("title", "Cambio remoto independiente")
    offline = false
    restored.refresh()
    assertEquals(0, restored.state.value.pending)
    assertEquals("Cambio remoto independiente", restored.state.value.snapshot!!.tasks.first { it.id == "b" }.title)
    assertEquals(setOf("prerequisite_ids"), sent.first().getJSONObject("changes").keys().asSequence().toSet())
    restored.setPrerequisites("b", project.id, setOf("a"), emptySet())
    remote.getValue("b").put("prerequisite_ids", JSONArray().put("c")); revision++
    restored.refresh()
    val conflict = restored.state.value.conflicts.single()
    assertEquals("prerequisite_ids", conflict.getJSONObject("result").getJSONArray("fields").getString(0))
    restored.resolve(conflict.getJSONObject("operation").getString("operation_id"), setOf("prerequisite_ids"))
    restored.refresh()
    assertEquals(0, restored.state.value.pending)
    assertEquals(0, remote.getValue("b").getJSONArray("prerequisite_ids").length())
    restored.logout()
  }

  @Test fun corruptPresentationStorageIsIgnoredAndNeverBecomesAccountData() {
    val name = "project-graph-graph-storage-test-project"
    val raw = context.getSharedPreferences(name, 0)
    val prefs = ProjectGraphPreferences(context, "graph-storage-test", "project")
    try {
      raw.edit().putString("positions", "broken").commit()
      assertTrue(prefs.read().isEmpty())
      raw.edit().putString("positions", "{\"bad\":{\"x\":-20,\"y\":30},\"good\":{\"x\":20,\"y\":40}}").commit()
      assertEquals(mapOf("good" to PlanPoint(20f, 40f)), prefs.read())
      assertTrue(ProjectGraphPreferences(context, "graph-storage-other", "project").read().isEmpty())
    } finally { raw.edit().clear().commit() }
  }
}
