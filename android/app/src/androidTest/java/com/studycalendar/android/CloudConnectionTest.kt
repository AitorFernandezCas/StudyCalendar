package com.studycalendar.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class CloudConnectionTest {
  private val user = "11111111-1111-4111-8111-111111111111"
  private val other = "22222222-2222-4222-8222-222222222222"
  private val taskId = "33333333-3333-4333-8333-333333333333"
  private fun session() = CloudSession(user, "test@example.invalid", "access", "refresh", Long.MAX_VALUE)
  private fun row(owner: String = user) = JSONObject().put("id", taskId).put("user_id", owner).put("title", "Tarea remota").put("date", "2026-11-13")
    .put("start_time", "09:00:00").put("end_time", "10:00:00").put("all_day", true).put("completed", false)
    .put("project_id", JSONObject.NULL).put("task_type", "daily").put("prerequisite_ids", org.json.JSONArray())

  @Test fun downloadPaginatesEvenWhenServerReturnsShortPagesAndHonorsResetTime() = runBlocking<Unit> {
    val paths = mutableListOf<String>()
    val reader = CloudReader(CloudTransport { path, method, token, _ ->
      if (path.startsWith("/rest/v1/rpc/")) throw CloudFailure(404, "not deployed")
      assertEquals("GET", method); assertEquals("access", token); paths.add(path)
      when {
        path.startsWith("/rest/v1/tasks?") && !path.contains("&id=gt.") -> "[${row()}]"
        path.startsWith("/rest/v1/user_settings?") && !path.contains("&user_id=gt.") -> "[{\"user_id\":\"$user\",\"routine_reset_time\":\"04:30\"}]"
        else -> "[]"
      }
    })
    val json = reader.snapshot(session())
    val snapshot = CloudSnapshot.parse(json, user)
    assertEquals("Tarea remota", snapshot.tasks.single().title)
    assertEquals(LocalTime.of(4, 30), snapshot.reset)
    assertTrue(paths.any { it.contains("&id=gt.$taskId") })
    assertTrue(paths.all { it.contains("user_id=eq.$user") })
    assertTrue(snapshot.routines.isEmpty())
    assertThrows(IllegalArgumentException::class.java) { CloudSnapshot.parse(json, other) }
  }

  @Test fun wrongOwnerInResponseIsRejected() = runBlocking<Unit> {
    val reader = CloudReader(CloudTransport { path, _, _, _ -> if (path.startsWith("/rest/v1/rpc/")) throw CloudFailure(404, "not deployed"); "[${row(other)}]" })
    var rejected = false
    try { reader.snapshot(session()) } catch (_: IllegalArgumentException) { rejected = true }
    assertTrue(rejected)
  }

  @Test fun failedDownloadPreservesCachedSnapshotAndLocalSignoutKeepsItIsolated() = runBlocking<Unit> {
    // Isolate test preferences and files from the installed app's session/cache.
    val context = object : android.content.ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
      override fun getFilesDir() = java.io.File(baseContext.cacheDir, "cloud-connection-tests").apply { mkdirs() }
      override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences("test-$name", mode)
    }
    context.getSharedPreferences("supabase-session", 0).edit().clear().commit()
    val tokenJson = JSONObject().put("user", JSONObject().put("id", user).put("email", "test@example.invalid"))
      .put("access_token", "access").put("refresh_token", "refresh").put("expires_in", 3600)
    var fail = false
    val reader = CloudReader(CloudTransport { path, method, _, _ ->
      if (path.startsWith("/rest/v1/rpc/")) throw CloudFailure(404, "not deployed")
      if (path.startsWith("/auth/")) { assertEquals("POST", method); tokenJson.toString() }
      else {
        assertEquals("GET", method)
        if (fail) throw java.io.IOException("simulated offline")
        if (path.startsWith("/rest/v1/tasks?") && !path.contains("&id=gt.")) "[${row()}]" else "[]"
      }
    })
    val connection = CloudConnection(context, reader)
    try {
      connection.login("test@example.invalid", "test-password")
      assertEquals(1, connection.state.value.snapshot!!.tasks.size)
      fail = true
      connection.refresh()
      assertNotNull(connection.state.value.error)
      assertEquals(1, connection.state.value.snapshot!!.tasks.size)
      val reopened = CloudConnection(context, reader)
      reopened.restore()
      assertEquals(user, reopened.state.value.session!!.userId)
      assertEquals(1, reopened.state.value.snapshot!!.tasks.size)
      reopened.logout()
      assertNull(reopened.state.value.snapshot)
      assertNull(reopened.state.value.session)
      val signedOut = CloudConnection(context, reader)
      signedOut.restore()
      assertNull(signedOut.state.value.session)
    } finally {
      connection.logout()
      java.io.File(context.filesDir, "account-cache/$user.json").delete()
    }
  }
}
