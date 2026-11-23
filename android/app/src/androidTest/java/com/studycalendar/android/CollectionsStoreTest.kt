package com.studycalendar.android

import androidx.room.Room
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.studycalendar.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class CollectionsStoreTest {
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val today = LocalDate.of(2026, 11, 13)
  private class MutableClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = Clock.fixed(now, zone)
    override fun instant(): Instant = now
  }

  @Test fun guardedUndoPreservesEditsAndRejectsStaleStateAndDependencies() = runBlocking {
    val database = Room.inMemoryDatabaseBuilder(context, CalendarDatabase::class.java).build()
    try {
      val store = LocalCalendar(database)
      val project = Project(name = "Proyecto de prueba")
      store.saveProject(project)
      val task = Task(title = "Original", date = today, projectTask = true, projectId = project.id)
      store.save(task)
      store.setCompletion(task.id, false, true)
      store.save(task.copy(title = "Nombre nuevo", completed = true))
      store.setCompletion(task.id, true, false)
      assertEquals("Nombre nuevo", database.calendar().tasks().single().title)
      assertFalse(database.calendar().tasks().single().completed)
      try { store.setCompletion(task.id, true, false); fail("Stale undo must fail") } catch (_: IllegalArgumentException) { }
      store.setCompletion(task.id, false, true)
      val dependent = Task(title = "Depende", date = today, projectTask = true, projectId = project.id, prerequisiteIds = setOf(task.id), completed = true)
      store.save(dependent)
      try { store.setCompletion(task.id, true, false); fail("Completed dependents must prevent undo") } catch (_: IllegalArgumentException) { }
      assertTrue(database.calendar().tasks().first { it.id == task.id }.completed)
    } finally { database.close() }
  }

  @Test fun habitUndoChecksExpectedStateAndOriginalDay() = runBlocking {
    val database = Room.inMemoryDatabaseBuilder(context, CalendarDatabase::class.java).build()
    val clock = MutableClock(Instant.parse("2026-11-13T12:00:00Z"))
    try {
      val store = LocalCalendar(database, clock)
      store.saveRoutine("habit", "Leer", "#7056ED", true, today)
      store.setRoutineCompletion("habit", today, false, true)
      store.setRoutineCompletion("habit", today, true, false)
      try { store.setRoutineCompletion("habit", today, true, false); fail("Stale undo") } catch (_: IllegalArgumentException) { }
      store.setRoutineCompletion("habit", today, false, true)
      clock.now = clock.now.plusSeconds(86400)
      try { store.setRoutineCompletion("habit", today, true, false); fail("Yesterday undo") } catch (_: IllegalArgumentException) { }
      assertTrue(today in database.calendar().routines().single().completedDates())
    } finally { database.close() }
  }

  @Test fun migrationPreservesExistingTasksAndJournal() = runBlocking {
    val name = "collections-migration-test.db"
    prepareLegacyDatabase(name, 1) {
      execSQL("INSERT INTO tasks VALUES ('old-task', 'Conservar', '2026-11-13', '09:00', '10:00', 1, 0, NULL, 0, '[]')")
      execSQL("INSERT INTO local_changes VALUES (1, 'old-operation', 'old-task', 'create', NULL, '{}', '2026-11-13T10:00:00Z')")
    }
    val database = upgradedDatabase(name)
    try {
      assertEquals("Conservar", database.calendar().tasks().single().title)
      assertNull(database.calendar().tasks().single().categoryId)
      assertNull(database.calendar().tasks().single().featureId)
      assertTrue(database.calendar().features().isEmpty())
      assertTrue(database.calendar().featureLinks().isEmpty())
      assertTrue(database.calendar().categories().isEmpty())
      assertEquals("old-operation", database.calendar().changes().single().operationId)
      LocalCalendar(database).saveProject(Project(name = "Nuevo"))
      assertEquals(2, database.calendar().changes().size)
    } finally { database.close(); context.deleteDatabase(name) }
  }

  @Test fun versionThreeUpgradeRetainsProjectTasksAndTheirPrerequisites() = runBlocking {
    val name = "feature-migration-test.db"
    prepareLegacyDatabase(name, 3) {
      execSQL("INSERT INTO projects (id, name, color, status) VALUES ('plan', 'Curso', '#4F46E5', 'active')")
      execSQL("INSERT INTO tasks (id, title, allDay, completed, projectId, projectTask, prerequisites) VALUES ('prior', 'Leer', 0, 1, 'plan', 1, '[]')")
      execSQL("INSERT INTO tasks (id, title, allDay, completed, projectId, projectTask, prerequisites) VALUES ('next', 'Escribir', 0, 0, 'plan', 1, '[\"prior\"]')")
    }
    val database = upgradedDatabase(name)
    try {
      val tasks = database.calendar().tasks().associateBy { it.id }
      assertEquals(setOf("prior"), tasks.getValue("next").task().prerequisiteIds)
      assertTrue(tasks.getValue("prior").completed)
      assertTrue(tasks.values.all { it.projectId == "plan" && it.featureId == null })
    } finally { database.close(); context.deleteDatabase(name) }
  }

  private fun upgradedDatabase(name: String) = Room.databaseBuilder(context, CalendarDatabase::class.java, name)
    .addMigrations(CalendarDatabase.MIGRATION_1_2, CalendarDatabase.MIGRATION_2_3, CalendarDatabase.MIGRATION_3_4).build()

  private fun prepareLegacyDatabase(name: String, version: Int, seed: SQLiteDatabase.() -> Unit) {
    context.deleteDatabase(name)
    // Reconstruct the actual exported v1 schema without the test helper's
    // serializer runtime, then let production Room perform/validate the upgrade.
    val schema = InstrumentationRegistry.getInstrumentation().context.assets
      .open("com.studycalendar.android.CalendarDatabase/$version.json")
      .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
    val file = context.getDatabasePath(name)
    file.parentFile!!.mkdirs()
    SQLiteDatabase.openOrCreateDatabase(file, null).use { legacy ->
      val entities = schema.getJSONArray("entities")
      for (index in 0 until entities.length()) {
        val entity = entities.getJSONObject(index)
        val table = entity.getString("tableName")
        legacy.execSQL(entity.getString("createSql").replace("${'$'}{TABLE_NAME}", table))
        val indices = entity.optJSONArray("indices")
        if (indices != null) for (position in 0 until indices.length()) {
          legacy.execSQL(indices.getJSONObject(position).getString("createSql").replace("${'$'}{TABLE_NAME}", table))
        }
      }
      val setup = schema.getJSONArray("setupQueries")
      for (index in 0 until setup.length()) legacy.execSQL(setup.getString(index))
      legacy.version = version
      legacy.seed()
    }
  }

  @Test fun categoriesPersistAndDeletingOnePreservesTasks() = runBlocking {
    val name = "category-persistence-test.db"
    context.deleteDatabase(name)
    var db = Room.databaseBuilder(context, CalendarDatabase::class.java, name).build()
    try {
      val store = LocalCalendar(db)
      val category = Category(name = "Estudio", color = "#123456")
      val task = Task(title = "Leer", date = today, categoryId = category.id)
      store.saveCategory(category)
      store.save(task)
      var duplicateRejected = false
      try { store.saveCategory(Category(name = " ESTUDIO ")) } catch (_: IllegalArgumentException) { duplicateRejected = true }
      assertTrue(duplicateRejected)
      db.close()
      db = Room.databaseBuilder(context, CalendarDatabase::class.java, name).build()
      assertEquals(category, db.calendar().categories().single().category())
      assertEquals(category.id, db.calendar().tasks().single().task().categoryId)
      val reopened = LocalCalendar(db)
      reopened.complete(task.id)
      assertEquals(category.id, db.calendar().tasks().single().categoryId)
      reopened.deleteCategory(category.id)
      assertTrue(db.calendar().categories().isEmpty())
      assertNull(db.calendar().tasks().single().categoryId)
      assertTrue(db.calendar().tasks().single().completed)
    } finally { db.close(); context.deleteDatabase(name) }
  }

  @Test fun projectTransitionsPreserveTasksAndRejectDuplicateNamesAndNewInactiveAssignments() = runBlocking {
    val db = Room.inMemoryDatabaseBuilder(context, CalendarDatabase::class.java).build()
    try {
      val store = LocalCalendar(db)
      val project = Project(name = "Curso")
      store.saveProject(project)
      val task = Task(title = "Sin fecha", date = null, start = null, end = null, projectTask = true, projectId = project.id)
      store.save(task)
      for (status in listOf(ProjectStatus.INACTIVE, ProjectStatus.COMPLETED, ProjectStatus.ACTIVE)) {
        store.saveProject(project.copy(status = status))
        assertEquals(task, db.calendar().tasks().single().task())
      }
      var rejected = false
      try { store.saveProject(Project(name = " CURSO ")) } catch (_: IllegalArgumentException) { rejected = true }
      assertTrue(rejected)
      store.saveProject(project.copy(status = ProjectStatus.INACTIVE))
      store.save(task.copy(title = "Editar existente"))
      rejected = false
      try { store.save(task.copy(id = "new")) } catch (_: IllegalArgumentException) { rejected = true }
      assertTrue(rejected)
      assertEquals(1, db.calendar().tasks().size)
    } finally { db.close() }
  }

  @Test fun projectsRoutinesAndCompletionsSurviveReopening() = runBlocking {
    val name = "collections-persistence-test.db"
    context.deleteDatabase(name)
    var db = Room.databaseBuilder(context, CalendarDatabase::class.java, name).build()
    try {
      val clock = Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC)
      val store = LocalCalendar(db, clock)
      val project = Project(name = "Persistencia")
      store.saveProject(project)
      store.saveRoutine("habit", "Leer", "#16A085", true, today)
      store.completeRoutine("habit", today, true)
      db.close()
      db = Room.databaseBuilder(context, CalendarDatabase::class.java, name).build()
      assertEquals(project, db.calendar().projects().single().project())
      assertEquals(setOf(today), db.calendar().routines().single().completedDates())
      assertEquals(3, db.calendar().changes().size)
    } finally { db.close(); context.deleteDatabase(name) }
  }

  @Test fun deletingProjectRemovesAllItsTasksAndDependenciesAtomically() = runBlocking {
    val db = Room.inMemoryDatabaseBuilder(context, CalendarDatabase::class.java).build()
    try {
      val store = LocalCalendar(db)
      val project = Project(name = "Curso")
      val other = Task(title = "Personal", date = today)
      store.saveProject(project)
      val a = Task(title = "A", date = today, projectTask = true, projectId = project.id)
      val b = a.copy(id = "dependent", title = "B", prerequisiteIds = setOf(a.id))
      store.save(a); store.save(b); store.save(other)
      val count = db.calendar().changes().size
      db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_project_deletion BEFORE INSERT ON local_changes WHEN NEW.kind = 'project.delete' BEGIN SELECT RAISE(ABORT, 'journal failure'); END")
      var rejected = false
      try { store.deleteProject(project.id) } catch (_: android.database.sqlite.SQLiteException) { rejected = true }
      assertTrue(rejected)
      assertEquals(3, db.calendar().tasks().size)
      assertEquals(1, db.calendar().projects().size)
      assertEquals(count, db.calendar().changes().size)
      db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_project_deletion")
      store.deleteProject(project.id)
      assertEquals(other, db.calendar().tasks().single().task())
      assertTrue(db.calendar().projects().isEmpty())
    } finally { db.close() }
  }

  @Test fun routineCompletionIsIdempotentPreservesDayAndSurvivesPauseAndEditing() = runBlocking {
    val db = Room.inMemoryDatabaseBuilder(context, CalendarDatabase::class.java).build()
    val clock = MutableClock(Instant.parse("2026-11-13T12:00:00Z"))
    try {
      val store = LocalCalendar(db, clock)
      store.saveRoutine("habit", "Leer", "#16A085", true, today)
      store.completeRoutine("habit", today, true)
      store.completeRoutine("habit", today, true)
      assertEquals(2, db.calendar().changes().size)
      store.saveRoutine("habit", "Leer más", "#2563EB", false, today)
      var record = db.calendar().routines().single()
      assertEquals(setOf(today), record.completedDates())
      assertEquals(Streak(0, 1), streaks(today, false, record.row.routine().periods, record.completedDates()))
      store.saveRoutine("habit", "Leer más", "#2563EB", true, today)
      store.completeRoutine("habit", today, false)
      assertTrue(db.calendar().routines().single().completedDates().isEmpty())
      store.completeRoutine("habit", today, true)
      clock.now = Instant.parse("2026-11-13T23:00:00Z") // Madrid midnight
      var rejected = false
      try { store.completeRoutine("habit", today, false) } catch (_: IllegalArgumentException) { rejected = true }
      assertTrue(rejected)
      record = db.calendar().routines().single()
      assertEquals(setOf(today), record.completedDates())
      assertTrue(db.calendar().changes().filter { it.kind == "routine.completion" }.all { it.after!!.contains(today.toString()) })
      store.completeRoutine("habit", today.plusDays(1), true)
      record = db.calendar().routines().single()
      assertEquals(Streak(2, 2), streaks(today.plusDays(1), true, record.row.routine().periods, record.completedDates()))
      store.deleteRoutine("habit")
      assertTrue(db.calendar().routines().isEmpty())
      db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM routine_completions").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
    } finally { db.close() }
  }

  @Test fun futureAndInactiveHabitsCannotCompleteAndFailedJournalRollsBackCompletion() = runBlocking {
    val db = Room.inMemoryDatabaseBuilder(context, CalendarDatabase::class.java).build()
    try {
      val store = LocalCalendar(db, Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC))
      store.saveRoutine("future", "Futuro", "#16A085", true, today.plusDays(1))
      store.saveRoutine("paused", "Pausado", "#16A085", false, today)
      for (id in listOf("future", "paused")) {
        var rejected = false
        try { store.completeRoutine(id, today, true) } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
      }
      store.saveRoutine("active", "Hoy", "#16A085", true, today)
      db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_completion BEFORE INSERT ON local_changes WHEN NEW.kind = 'routine.completion' BEGIN SELECT RAISE(ABORT, 'journal failure'); END")
      var rejected = false
      try { store.completeRoutine("active", today, true) } catch (_: android.database.sqlite.SQLiteException) { rejected = true }
      assertTrue(rejected)
      assertTrue(db.calendar().routines().all { it.completions.isEmpty() })
      assertEquals(3, db.calendar().changes().size)
    } finally { db.close() }
  }
}
