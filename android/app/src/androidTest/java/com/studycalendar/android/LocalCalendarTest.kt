package com.studycalendar.android

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.studycalendar.core.Task
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class LocalCalendarTest {
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

  @Test fun taskAndJournalSurviveDatabaseReopening() = runBlocking {
    val name = "persistence-test.db"
    context.deleteDatabase(name)
    var database = Room.databaseBuilder(context, CalendarDatabase::class.java, name).build()
    try {
      val task = Task(title = "Persistir", date = LocalDate.of(2026, 11, 13))
      LocalCalendar(database).save(task)
      database.close()
      database = Room.databaseBuilder(context, CalendarDatabase::class.java, name).build()
      assertEquals(task, database.calendar().tasks().single().task())
      assertEquals(task.id, database.calendar().changes().single().entityId)
      LocalCalendar(database).delete(task.id)
      assertTrue(database.calendar().tasks().isEmpty())
      assertEquals(listOf("create", "delete"), database.calendar().changes().map { it.kind })
    } finally { database.close(); context.deleteDatabase(name) }
  }

  @Test fun failedJournalInsertRollsBackTaskWrite() = runBlocking {
    val database = Room.inMemoryDatabaseBuilder(context, CalendarDatabase::class.java).build()
    try {
      database.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_changes BEFORE INSERT ON local_changes BEGIN SELECT RAISE(ABORT, 'simulated journal failure'); END")
      var failed = false
      try { LocalCalendar(database).save(Task(title = "Atómica", date = LocalDate.of(2026, 11, 13))) }
      catch (_: android.database.sqlite.SQLiteException) { failed = true }
      assertTrue(failed)
      assertTrue(database.calendar().tasks().isEmpty())
      assertTrue(database.calendar().changes().isEmpty())
    } finally { database.close() }
  }
}
