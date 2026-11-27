package com.studycalendar.android

import android.database.sqlite.SQLiteDatabase
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.studycalendar.core.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.time.*
import java.util.UUID

class GoalsTest {
  @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
  private val goal = Goal("goal", "Leer", GoalPeriod.MONTH, LocalDate.parse("2026-11-01"), BigDecimal("12"), "libros")

  @Test fun localGoalsAndReviewsSurviveReopeningAndUpgradePreservesTasks() = runBlocking {
    val name = "goals-migration-test.db"
    context.deleteDatabase(name)
    val schema = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("com.studycalendar.android.CalendarDatabase/4.json").bufferedReader().use { it.readText() }).getJSONObject("database")
    val path = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
    SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
      schema.getJSONArray("entities").objects().forEach { entity ->
        db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName")))
        entity.optJSONArray("indices")?.objects()?.forEach { db.execSQL(it.getString("createSql").replace("\${TABLE_NAME}", entity.getString("tableName"))) }
      }
      db.execSQL("INSERT INTO tasks (id,title,allDay,completed,projectTask,prerequisites) VALUES ('old','Conservar',0,0,1,'[]')")
      db.version = 4
    }
    fun open() = Room.databaseBuilder(context, CalendarDatabase::class.java, name).addMigrations(CalendarDatabase.MIGRATION_4_5).build()
    var db = open()
    try {
      assertEquals("Conservar", db.calendar().tasks().single().title)
      LocalGoals(db).saveGoal(goal, null)
      LocalGoals(db).reviewGoal(GoalReview("review", goal.id, Instant.parse("2026-11-23T12:00:00Z"), BigDecimal("4"), "Avance"))
      db.close(); db = open()
      assertEquals(goal, db.goals().goal(goal.id)!!.goal())
      assertEquals(BigDecimal("4"), db.goals().observeReviews().first().single().review().amount)
      LocalGoals(db).deleteGoal(goal)
      assertTrue(db.goals().observeReviews().first().isEmpty())
      assertEquals(1, db.calendar().tasks().size)
    } finally { db.close(); context.deleteDatabase(name) }
  }

  @Test fun accountQueueRestoresIndependentReviewsAndSurvivesAcknowledgement() {
    val owner = "33333333-3333-4333-8333-333333333333"
    val data = JSONObject()
    listOf("tasks", "projects", "category", "routine", "routine_period", "routine_completion", "user_settings", "goals", "goal_reviews", "goal_schedules").forEach { data.put(it, JSONArray()) }
    val json = JSONObject().put("protocol", 1).put("owner", owner).put("data", data).put("downloadedAt", "2026-11-23T12:00:00Z")
    val replica = AccountReplica.load(json, owner)
    replica.saveGoal(goal, null)
    replica.reviewGoal(GoalReview("a", goal.id, Instant.parse("2026-11-23T12:00:00Z"), BigDecimal("3")))
    replica.reviewGoal(GoalReview("b", goal.id, Instant.parse("2026-11-23T13:00:00Z"), BigDecimal("4")))
    val restored = AccountReplica.load(replica.document.copyJson(), owner)
    repeat(3) { index ->
      val operation = restored.prepareNext()!!
      assertEquals(operation.toString(), restored.prepareNext()!!.toString())
      val state = operation.getJSONObject("changes").copyJson().put("id", operation.getString("id")).put("user_id", owner)
      restored.acknowledge(JSONObject().put("status", "applied").put("row", state.copyJson().put("_sync", JSONObject().put("revision", index + 1).put("state", state))))
    }
    assertEquals(0, restored.pending)
    assertEquals(setOf("a", "b"), restored.snapshot().goalReviews.map { it.id }.toSet())
    val remote = restored.remote.copyJson()
    remote.getJSONObject("data").getJSONArray("goal_reviews").put(GoalReview("other-phone", goal.id, Instant.parse("2026-11-23T14:00:00Z"), BigDecimal("5")).json().put("user_id", owner))
    restored.reviewGoal(GoalReview("offline", goal.id, Instant.parse("2026-11-23T15:00:00Z"), BigDecimal("6")))
    restored.replaceRemote(remote)
    assertEquals(setOf("a", "b", "other-phone", "offline"), restored.snapshot().goalReviews.map { it.id }.toSet())
    restored.deleteGoal(goal)
    assertTrue(restored.snapshot().goalReviews.isEmpty())
    val oldData = json.copyJson().apply { getJSONObject("data").remove("goals") }
    assertNull(AccountReplica.load(oldData, owner).snapshot().goals)
  }

  @Test fun reviewRecordsAnAbsoluteTotalAndNotes() {
    var saved: GoalReview? = null
    val old = GoalReview("old", goal.id, Instant.parse("2026-11-22T12:00:00Z"), BigDecimal("3"))
    rule.setContent { StudyCalendarTheme { GoalReviewDialog(goal, listOf(old), false, {}, { saved = it }, {}) } }
    rule.onNodeWithText("Total alcanzado (libros)").performTextInput("4")
    rule.onNodeWithText("Notas de la revisión").performTextInput("Un libro más")
    rule.onNodeWithText("Guardar revisión").performClick()
    rule.runOnIdle { assertEquals(BigDecimal("4"), saved!!.amount); assertEquals("Un libro más", saved!!.note) }
  }

  @Test fun formCreatesCalendarGoalWithNumericTarget() {
    var saved: Goal? = null
    rule.setContent { StudyCalendarTheme { GoalForm(null, false, {}, { saved = it }) } }
    rule.onNodeWithText("Título").performTextInput("Leer")
    rule.onNodeWithText("Mensual").performClick()
    rule.onNodeWithText("Fecha del período (AAAA-MM-DD)").performTextReplacement("2026-11-23")
    rule.onNodeWithText("Meta numérica (opcional)").performScrollTo().performTextInput("12")
    rule.onNodeWithText("Guardar").performClick()
    rule.runOnIdle { assertEquals(LocalDate.parse("2026-11-01"), saved!!.startsOn); assertEquals(BigDecimal("12"), saved!!.target) }
  }

  @Test fun groupedNotificationUpdatesAfterReviewAndSuppressesDismissedRepeats() = runBlocking {
    org.junit.Assume.assumeTrue(context.packageName.endsWith(".graphcheck"))
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")).bufferedReader().use { it.readText() }
    val app = context.applicationContext as CalendarApplication
    val today = LocalDate.now(GoalReminders.zone)
    val first = goal.copy(id = UUID.randomUUID().toString(), period = GoalPeriod.WEEK, startsOn = GoalPeriod.WEEK.start(today))
    val second = first.copy(id = UUID.randomUUID().toString())
    val old = app.goals.schedules.first().map { it.schedule() }.find { it.period == GoalPeriod.WEEK }
    val schedule = GoalSchedule(old?.id ?: UUID.randomUUID().toString(), GoalPeriod.WEEK, true, time = LocalTime.MIDNIGHT)
    val manager = context.getSystemService(android.app.NotificationManager::class.java)
    try {
      app.goals.saveGoal(first, null); app.goals.saveGoal(second, null)
      app.goals.saveGoalSchedule(schedule)
      app.refreshGoalsNow()
      kotlinx.coroutines.delay(350)
      assertEquals(1, manager.activeNotifications.count { it.id == 902 })
      app.goals.reviewGoal(GoalReview(UUID.randomUUID().toString(), first.id, Instant.now()))
      app.refreshGoalsNow()
      kotlinx.coroutines.delay(350)
      assertEquals("1 objetivo pendiente de revisión", manager.activeNotifications.single { it.id == 902 }.notification.extras.getString("android.text"))
      manager.cancel(902)
      app.refreshGoalsNow()
      kotlinx.coroutines.delay(350)
      assertFalse(manager.activeNotifications.any { it.id == 902 })
      app.goals.reviewGoal(GoalReview(UUID.randomUUID().toString(), second.id, Instant.now()))
      app.refreshGoalsNow()
      kotlinx.coroutines.delay(350)
      assertFalse(manager.activeNotifications.any { it.id == 902 })
    } finally {
      app.goals.deleteGoal(first); app.goals.deleteGoal(second)
      app.goals.saveGoalSchedule(old ?: schedule.copy(enabled = false))
      manager.cancel(902)
    }
  }
}
