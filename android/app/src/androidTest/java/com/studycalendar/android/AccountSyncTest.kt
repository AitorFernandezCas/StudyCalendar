package com.studycalendar.android

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.studycalendar.core.Category
import com.studycalendar.core.Task
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class AccountSyncTest {
  private val user = "77777777-7777-4777-8777-777777777777"
  private val taskId = "88888888-8888-4888-8888-888888888888"
  private fun context(label: String): Context {
    val base = InstrumentationRegistry.getInstrumentation().targetContext
    base.getSharedPreferences("sync-test-$label-supabase-session", 0).edit().clear().commit()
    listOf(".json", ".json.bak").forEach { suffix -> java.io.File(base.cacheDir, "account-sync-tests/$label/account-cache/$user$suffix").delete() }
    return object : ContextWrapper(base) {
    override fun getFilesDir() = java.io.File(baseContext.cacheDir, "account-sync-tests/$label").apply { mkdirs() }
    override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences("sync-test-$label-$name", mode)
    }
  }
  private inner class Server : CloudTransport {
    var row: JSONObject? = JSONObject().put("id", taskId).put("user_id", user).put("title", "Original").put("date", "2026-11-13")
      .put("start_time", "09:00:00").put("end_time", "10:00:00").put("color", "#4F46E5").put("completed", false).put("all_day", true)
      .put("task_type", "daily").put("category_id", JSONObject.NULL).put("project_id", JSONObject.NULL).put("prerequisite_ids", JSONArray())
    var revision = 1L
    var loseResponse = false
    var pause: CompletableDeferred<Unit>? = null
    val entered = CompletableDeferred<Unit>()
    val calls = mutableListOf<JSONObject>()
    val receipts = mutableMapOf<String, JSONObject>()
    private fun base() = JSONObject().put("revision", revision).put("state", row?.copyJson() ?: JSONObject.NULL)
    private fun visible() = row?.copyJson()?.put("_sync", base())
    fun snapshot() = JSONObject().put("protocol", 1).put("owner", user).put("downloadedAt", Instant.now().toString()).put("data", JSONObject().apply {
      put("tasks", JSONArray(listOfNotNull(visible())))
      listOf("projects", "category", "routine", "routine_period", "routine_completion", "user_settings").forEach { put(it, JSONArray()) }
    })
    override suspend fun request(path: String, method: String, token: String?, body: JSONObject?): String {
      if (path.startsWith("/auth/")) return JSONObject().put("user", JSONObject().put("id", user).put("email", "sync@example.invalid"))
        .put("access_token", "access").put("refresh_token", "refresh").put("expires_in", 3600).toString()
      assertEquals("POST", method)
      if (path.endsWith("sync_snapshot")) return snapshot().toString()
      assertTrue(path.endsWith("sync_apply"))
      val op = body!!.getJSONObject("p_operation")
      calls.add(op.copyJson())
      entered.complete(Unit)
      pause?.await()
      val id = op.getString("operation_id")
      val result = receipts[id] ?: run {
        val changes = op.getJSONObject("changes")
        val original = op.getJSONObject("base").getJSONObject("state")
        val remote = row
        val conflicts = changes.keys().asSequence().filter { key -> original.opt(key) != remote?.opt(key) && changes.opt(key) != remote?.opt(key) }.toList()
        val answer = if (remote == null) JSONObject().put("status", "conflict").put("reason", "deleted").put("remote", JSONObject.NULL)
          else if (conflicts.isNotEmpty()) JSONObject().put("status", "conflict").put("reason", "fields").put("fields", JSONArray(conflicts)).put("remote", base())
          else { changes.keys().forEach { remote.put(it, changes.get(it)) }; revision++; JSONObject().put("status", "applied").put("row", visible()) }
        receipts[id] = answer.copyJson()
        answer
      }
      if (loseResponse) { loseResponse = false; throw java.io.IOException("lost response") }
      return result.toString()
    }
  }

  @Test fun categoryOperationsPreserveAssignmentsAndRenderOfflineDeletion() {
    val server = Server()
    val category = Category(id = "99999999-9999-4999-8999-999999999999", name = "Estudio", color = "#123456")
    val replica = AccountReplica.load(server.snapshot(), user)
    replica.saveCategory(category)
    replica.save(replica.snapshot().tasks.single().task().copy(categoryId = category.id))
    assertEquals(category.id, replica.snapshot().tasks.single().categoryId)
    assertEquals(category.id, replica.operations.getJSONObject(1).getJSONObject("operation").getJSONObject("changes").getString("category_id"))
    replica.complete(taskId)
    assertEquals(category.id, replica.snapshot().tasks.single().categoryId)
    replica.deleteCategory(category.id)
    assertTrue(replica.snapshot().categories.isEmpty())
    assertNull(replica.snapshot().tasks.single().categoryId)
    assertTrue(replica.snapshot().tasks.single().completed)
    val reopened = AccountReplica.load(replica.document.copyJson(), user)
    assertNull(reopened.snapshot().tasks.single().categoryId)
    assertEquals(4, reopened.pending)
  }

  @Test fun categoryChangesSynchronizeAfterOfflineAndResolveConflict() = runBlocking {
    var offline = false
    var category: JSONObject? = null
    var categoryRevision = 0L
    val task = Server().row!!
    var taskRevision = 1L
    fun wrapped(row: JSONObject, revision: Long): JSONObject = row.copyJson().put("_sync", JSONObject().put("revision", revision).put("state", row.copyJson()))
    val transport = CloudTransport { path, _, _, body ->
      if (path.startsWith("/auth/")) JSONObject().put("user", JSONObject().put("id", user).put("email", "sync@example.invalid"))
        .put("access_token", "access").put("refresh_token", "refresh").put("expires_in", 3600).toString()
      else {
        if (offline) throw java.io.IOException("offline")
        if (path.endsWith("sync_snapshot")) JSONObject().put("protocol", 1).put("owner", user).put("downloadedAt", Instant.now().toString()).put("data", JSONObject().apply {
          put("tasks", JSONArray().put(wrapped(task, taskRevision)))
          put("category", JSONArray().apply { category?.let { put(wrapped(it, categoryRevision)) } })
          listOf("projects", "routine", "routine_period", "routine_completion", "user_settings").forEach { put(it, JSONArray()) }
        }).toString()
        else {
          val op = body!!.getJSONObject("p_operation")
          val changes = op.getJSONObject("changes")
          val isCategory = op.getString("entity") == "category"
          val current = if (isCategory) category else task
          val base = op.optJSONObject("base")?.optJSONObject("state")
          val revision = if (isCategory) categoryRevision else taskRevision
          val conflict = if (current == null || base == null) emptyList() else changes.keys().asSequence().filter { key -> base.opt(key) != current.opt(key) && changes.opt(key) != current.opt(key) }.toList()
          if (conflict.isNotEmpty()) JSONObject().put("status", "conflict").put("reason", "fields").put("fields", JSONArray(conflict)).put("remote", JSONObject().put("revision", revision).put("state", current!!.copyJson())).toString()
          else {
            val row = current ?: JSONObject().put("id", op.getString("id")).put("user_id", user)
            changes.keys().forEach { key -> row.put(key, changes.get(key)) }
            if (isCategory) { category = row; categoryRevision++ } else taskRevision++
            JSONObject().put("status", "applied").put("row", wrapped(row, if (isCategory) categoryRevision else taskRevision)).toString()
          }
        }
      }
    }
    val connection = CloudConnection(context("category-sync"), CloudReader(transport))
    try {
      connection.login("sync@example.invalid", "password")
      val entry = Category(name = "Estudio", color = "#123456")
      connection.saveCategory(entry)
      connection.save(connection.state.value.snapshot!!.tasks.single().task().copy(categoryId = entry.id))
      offline = true
      connection.refresh()
      assertEquals(2, connection.state.value.pending)
      assertEquals(entry.id, connection.state.value.snapshot!!.tasks.single().categoryId)
      offline = false
      connection.refresh()
      assertEquals(0, connection.state.value.pending)
      assertEquals(entry.id, task.getString("category_id"))
      connection.saveCategory(entry.copy(color = "#ABCDEF"))
      category!!.put("color", "#654321"); categoryRevision++
      connection.refresh()
      val conflict = connection.state.value.conflicts.single()
      assertEquals("fields", conflict.getJSONObject("result").getString("reason"))
      connection.resolve(conflict.getJSONObject("operation").getString("operation_id"), setOf("color"))
      connection.refresh()
      assertEquals(0, connection.state.value.pending)
      assertEquals("#ABCDEF", category!!.getString("color"))
    } finally { connection.logout() }
  }

  @Test fun lostResponseAndProcessReopeningKeepOneImmutableOperation() = runBlocking<Unit> {
    val context = context("lost-response")
    val server = Server()
    val connection = CloudConnection(context, CloudReader(server))
    try {
      connection.login("sync@example.invalid", "test-password")
      connection.save(connection.state.value.snapshot!!.tasks.single().task().copy(title = "Teléfono"))
      assertEquals(1, connection.state.value.pending)
      val restored = CloudConnection(context, CloudReader(server)); restored.restore()
      assertEquals("Teléfono", restored.state.value.snapshot!!.tasks.single().title)
      server.loseResponse = true
      restored.refresh()
      assertEquals(1, restored.state.value.pending)
      assertNotNull(restored.state.value.error)
      val reopened = CloudConnection(context, CloudReader(server)); reopened.restore(); reopened.refresh()
      assertEquals(0, reopened.state.value.pending)
      assertEquals(1, server.receipts.size)
      assertEquals(server.calls[0].toString(), server.calls[1].toString())
      assertEquals("Teléfono", reopened.state.value.snapshot!!.tasks.single().title)
    } finally { connection.logout() }
  }

  @Test fun differentFieldsMergeAndAnEditDuringHttpIsNotLost() = runBlocking<Unit> {
    val server = Server()
    val actual = CloudConnection(context("merge-inflight"), CloudReader(server))
    try {
      actual.login("sync@example.invalid", "test-password")
      actual.save(actual.state.value.snapshot!!.tasks.single().task().copy(title = "Teléfono"))
      server.row!!.put("completed", true); server.revision++
      val gate = CompletableDeferred<Unit>(); server.pause = gate
      val sync = async { actual.refresh() }
      server.entered.await()
      try { withTimeout(3000) { actual.save(actual.state.value.snapshot!!.tasks.single().task().copy(date = LocalDate.of(2026, 11, 14))) } } finally { gate.complete(Unit) }
      sync.await()
      val task = actual.state.value.snapshot!!.tasks.single().task()
      assertEquals("Teléfono", task.title)
      assertTrue(task.completed)
      assertEquals(LocalDate.of(2026, 11, 14), task.date)
      assertEquals(0, actual.state.value.pending)
    } finally { actual.logout() }
  }

  @Test fun sameFieldConflictSurvivesReopeningAndExplicitResolution() = runBlocking<Unit> {
    val context = context("conflict")
    val server = Server()
    val connection = CloudConnection(context, CloudReader(server))
    try {
      connection.login("sync@example.invalid", "test-password")
      connection.save(connection.state.value.snapshot!!.tasks.single().task().copy(title = "Teléfono"))
      server.row!!.put("title", "Web"); server.revision++
      connection.refresh()
      assertEquals(1, connection.state.value.conflicts.size)
      val restored = CloudConnection(context, CloudReader(server)); restored.restore()
      assertEquals(1, restored.state.value.conflicts.size)
      val originalId = restored.state.value.conflicts.single().getJSONObject("operation").getString("operation_id")
      restored.resolve(originalId, setOf("title")); restored.refresh()
      assertEquals(0, restored.state.value.pending)
      assertEquals("Teléfono", server.row!!.getString("title"))
      assertNotEquals(originalId, server.calls.last().getString("operation_id"))
    } finally { connection.logout() }
  }

  @Test fun remoteDeletionNeverRecreatesAndDiscardClearsOnlyTheSelectedChange() = runBlocking<Unit> {
    val server = Server()
    val connection = CloudConnection(context("deleted"), CloudReader(server))
    try {
      connection.login("sync@example.invalid", "test-password")
      connection.save(connection.state.value.snapshot!!.tasks.single().task().copy(title = "Pendiente"))
      server.row = null
      connection.refresh()
      assertEquals("deleted", connection.state.value.conflicts.single().getJSONObject("result").getString("reason"))
      val id = connection.state.value.conflicts.single().getJSONObject("operation").getString("operation_id")
      var rejected = false
      try { connection.resolve(id, setOf("title")) } catch (_: IllegalArgumentException) { rejected = true }
      assertTrue(rejected)
      connection.resolve(id, null)
      assertEquals(0, connection.state.value.pending)
      assertTrue(connection.state.value.snapshot!!.tasks.isEmpty())
      assertNull(server.row)
    } finally { connection.logout() }
  }

  @Test fun offlineHabitPauseKeepsTheAvailabilityBaseFromCreation() {
    val replica = AccountReplica.load(Server().snapshot(), user)
    val day = LocalDate.of(2026, 11, 13)
    val now = Instant.parse("2026-11-13T21:00:00Z")
    val id = "99999999-9999-4999-8999-999999999990"
    replica.saveRoutine(id, "Leer", "#16A085", true, day, now)
    replica.saveRoutine(id, "Leer", "#16A085", false, day, now.plusSeconds(1))
    val pause = replica.operations.getJSONObject(1).getJSONObject("operation")
    assertEquals(1, pause.getJSONObject("base").getJSONObject("state").getJSONArray("periods").length())
    assertEquals(day.toString(), pause.getJSONObject("base").getJSONObject("state").getJSONArray("periods").getJSONObject(0).getString("start"))
    assertFalse(replica.snapshot().routines.single().row.active)
  }

  @Test fun offlineCreatedHabitRetainsItsOriginalDayAfterActivationAcknowledgement() {
    val server = Server()
    val replica = AccountReplica.load(server.snapshot(), user)
    val now = Instant.parse("2026-11-13T21:00:00Z")
    val day = LocalDate.of(2026, 11, 13)
    val id = "99999999-9999-4999-8999-999999999999"
    replica.saveRoutine(id, "Leer", "#16A085", true, day, now)
    replica.completeRoutine(id, day, true, now.plusSeconds(1))
    val creation = replica.prepareNext()!!
    val root = JSONObject().put("id", id).put("user_id", user).put("title", "Leer").put("color", "#16A085").put("active", true).put("periods", JSONArray())
    replica.acknowledge(JSONObject().put("status", "applied").put("row", root.copyJson().put("_sync", JSONObject().put("revision", 17).put("state", root))))
    val reopened = AccountReplica.load(JSONObject(replica.document.toString()), user)
    val completion = reopened.prepareNext()!!
    assertEquals(day.toString(), completion.getJSONObject("context").getString("day"))
    assertEquals(17, completion.getJSONObject("context").getInt("routine_revision"))
    assertEquals(creation.getString("operation_id"), completion.getJSONObject("context").getString("routine_operation_id"))
  }
}
