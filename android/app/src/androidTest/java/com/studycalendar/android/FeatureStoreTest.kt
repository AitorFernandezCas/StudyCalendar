package com.studycalendar.android

import androidx.room.Room
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.studycalendar.core.*
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class FeatureStoreTest {
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val owner = "33333333-3333-4333-8333-333333333333"
  private val project = Project(id = "p", name = "Curso")
  private val a = ProjectFeature("a", project.id, "Analizar")
  private val b = ProjectFeature("b", project.id, "Escribir", "Texto")
  private val link = ProjectFeatureLink("ab", project.id, a.id, b.id)
  private val root = Task(id = "one", title = "Leer", date = LocalDate.of(2026, 11, 20), projectTask = true,
    projectId = project.id, featureId = a.id, completed = true)
  private val child = Task(id = "two", title = "Redactar", date = null, start = null, end = null, allDay = false,
    projectTask = true, projectId = project.id, featureId = b.id, prerequisiteIds = setOf(root.id))

  @Test fun localProjectUpdateAndFeatureDeletionPreserveTasksAndTheirRelations() = runBlocking {
    val database = Room.inMemoryDatabaseBuilder(context, CalendarDatabase::class.java).build()
    try {
      val store = LocalCalendar(database)
      val dao = database.calendar()
      store.saveProject(project)
      store.saveFeature(a)
      store.saveFeature(b)
      store.createFeatureLink(link)
      store.save(root)
      store.save(child)
      store.saveProject(project.copy(name = "Curso actualizado"))
      assertEquals(2, dao.features().size)
      assertEquals(link, dao.featureLinks().single().link())
      store.saveFeature(b.copy(name = "Revisar"), b)
      store.editTask(child, child.copy(featureId = a.id))
      store.editTask(child, child.copy(title = "Redactar una nota"))
      assertEquals(a.id, dao.tasks().find { it.id == child.id }!!.featureId)
      store.deleteFeature(a)
      assertEquals("Revisar", dao.features().single().name)
      assertTrue(dao.featureLinks().isEmpty())
      assertEquals(setOf(root.copy(featureId = null), child.copy(featureId = null, title = "Redactar una nota")),
        dao.tasks().map(TaskRow::task).toSet())
      store.deleteProject(project.id)
      assertTrue(dao.features().isEmpty())
      assertTrue(dao.tasks().isEmpty())
    } finally { database.close() }
  }

  private fun serverSnapshot(features: Boolean, links: Boolean): JSONObject {
    val data = JSONObject()
    listOf("category", "routine", "routine_period", "routine_completion", "user_settings").forEach { data.put(it, JSONArray()) }
    data.put("projects", JSONArray().put(JSONObject().put("id", project.id).put("user_id", owner)
      .put("name", project.name).put("color", project.color).put("status", "active")))
    val task = JSONObject(TaskRow.from(root.copy(featureId = null)).snapshot()).apply {
      put("user_id", owner); put("color", project.color); remove("feature_id")
    }
    data.put("tasks", JSONArray().put(task))
    if (features) data.put("project_features", JSONArray())
    if (links) data.put("project_feature_links", JSONArray())
    return JSONObject().put("protocol", 1).put("owner", owner).put("data", data).put("downloadedAt", "2026-11-20T12:00:00Z")
  }

  @Test fun replicaDistinguishesOldServerSnapshotsAndSendsSparseTaskMembershipChanges() {
    val legacy = AccountReplica.load(serverSnapshot(false, false), owner)
    legacy.save(root.copy(featureId = null, title = "Título"))
    assertNull(legacy.snapshot().features)
    assertFalse(legacy.prepareNext()!!.getJSONObject("changes").has("feature_id"))
    val featuresOnly = AccountReplica.load(serverSnapshot(true, false), owner)
    assertEquals(emptyList<ProjectFeatureRow>(), featuresOnly.snapshot().features)
    assertNull(featuresOnly.snapshot().featureLinks)

    val replica = AccountReplica.load(serverSnapshot(true, true), owner)
    replica.saveFeature(a, null)
    replica.saveFeature(b, null)
    replica.createFeatureLink(link)
    val original = replica.snapshot().tasks.single().task()
    replica.editTask(original, original.copy(featureId = a.id))
    replica.editTask(original, original.copy(title = "Otro título"))
    assertEquals(a.id, replica.snapshot().tasks.single().featureId)
    assertFalse(replica.operations.getJSONObject(4).getJSONObject("operation").getJSONObject("changes").has("feature_id"))
    val assigned = replica.snapshot().tasks.single().task()
    replica.editTask(assigned, assigned.copy(featureId = null))
    val changes = replica.operations.getJSONObject(5).getJSONObject("operation").getJSONObject("changes")
    assertTrue(changes.has("feature_id") && changes.isNull("feature_id"))

    val reopened = AccountReplica.load(replica.document.copyJson(), owner)
    assertEquals(link, reopened.snapshot().featureLinks!!.single().link())
    assertEquals("Otro título", reopened.snapshot().tasks.single().title)
    assertNull(reopened.snapshot().tasks.single().featureId)
    reopened.deleteFeature(a)
    assertTrue(reopened.snapshot().featureLinks!!.isEmpty())
    assertEquals(b, reopened.snapshot().features!!.single().feature())
    assertEquals(1, reopened.snapshot().tasks.size)
  }

  @Test fun offlineLinkDeleteUsesCreateReceiptBeforeFirstSendAndPreservesRetryPayload() {
    val replica = AccountReplica.load(serverSnapshot(true, true), owner)
    replica.saveFeature(a, null)
    replica.saveFeature(b, null)
    replica.createFeatureLink(link)
    replica.deleteFeatureLink(link)
    repeat(3) { index ->
      val op = replica.prepareNext()!!
      val state = op.getJSONObject("changes").copyJson().put("user_id", owner).put("id", op.getString("id"))
      val row = state.copyJson().put("_sync", JSONObject().put("state", state).put("revision", 10 + index))
      replica.acknowledge(JSONObject().put("status", "applied").put("row", row))
    }
    val deletion = replica.prepareNext()!!
    assertEquals("delete", deletion.getString("action"))
    assertEquals(12L, deletion.getJSONObject("base").getLong("revision"))
    val reopened = AccountReplica.load(replica.document.copyJson(), owner)
    assertEquals(deletion.toString(), reopened.prepareNext()!!.toString())
    reopened.acknowledge(JSONObject().put("status", "conflict").put("reason", "fields"))
    assertEquals(1, reopened.conflicts.size)
    assertNull(reopened.prepareNext())
  }

  @Test fun serviceSendsQueuedFeaturesAssignmentAndLinksInOrderAfterRestart() = runBlocking {
    val namespace = "feature-cloud-test-" + java.util.UUID.randomUUID()
    val isolated = object : ContextWrapper(context) {
      override fun getFilesDir() = java.io.File(baseContext.cacheDir, namespace).apply { mkdirs() }
      override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences("$namespace-$name", mode)
    }
    val remote = serverSnapshot(true, true)
    val data = remote.getJSONObject("data")
    var online = true
    val calls = mutableListOf<Pair<String, String>>()
    val reader = CloudReader(CloudTransport { path, _, _, body ->
      if (!online) throw java.io.IOException("offline")
      when {
        path.startsWith("/auth/") -> JSONObject().put("user", JSONObject().put("id", owner).put("email", "features@example.invalid"))
          .put("access_token", "access").put("refresh_token", "refresh").put("expires_in", 3600).toString()
        path.endsWith("sync_snapshot") -> remote.toString()
        else -> {
          assertTrue(path.endsWith("sync_apply"))
          val op = body!!.getJSONObject("p_operation")
          val id = op.getString("id")
          val table = op.getString("entity")
          calls += table to op.getString("action")
          val rows = data.getJSONArray(table).objects()
          val row = rows.find { it.getString("id") == id }?.copyJson()
            ?: JSONObject().put("id", id).put("user_id", owner)
          row.remove("_sync")
          val changes = op.getJSONObject("changes")
          changes.keys().forEach { row.put(it, changes.get(it)) }
          val wrapped = row.copyJson().put("_sync", JSONObject().put("revision", calls.size).put("state", row))
          data.put(table, JSONArray(rows.filterNot { it.getString("id") == id } + wrapped))
          JSONObject().put("status", "applied").put("row", wrapped).toString()
        }
      }
    })
    val connection = CloudConnection(isolated, reader)
    connection.login("features@example.invalid", "password")
    assertNull(connection.state.value.error)
    assertEquals(1, connection.state.value.snapshot?.protocol)
    online = false
    connection.saveFeature(a)
    connection.saveFeature(b)
    connection.editTask(root.copy(featureId = null), root)
    connection.createFeatureLink(link)
    connection.refresh()
    assertTrue(calls.isEmpty())
    val restarted = CloudConnection(isolated, reader)
    restarted.restore()
    assertEquals(4, restarted.state.value.pending)
    assertEquals(a.id, restarted.state.value.snapshot!!.tasks.single().featureId)
    online = true
    restarted.refresh()
    assertEquals(listOf("project_features" to "create", "project_features" to "create", "tasks" to "update",
      "project_feature_links" to "create"), calls)
    assertEquals(0, restarted.state.value.pending)
    assertEquals(a.id, restarted.state.value.snapshot!!.tasks.single().featureId)
    assertEquals(link, restarted.state.value.snapshot!!.featureLinks!!.single().link())
    restarted.logout()
  }
}
