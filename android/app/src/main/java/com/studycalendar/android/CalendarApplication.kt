package com.studycalendar.android

import android.app.Application
import androidx.room.Room

class CalendarApplication : Application() {
  private var goalNotifications: GoalNotifications? = null
  override fun onCreate() { super.onCreate(); refreshGoalNotifications() }
  fun refreshGoalNotifications() {
    goalNotifications?.let { it.refresh(); return }
    goalNotifications = GoalNotifications(this).also { it.start() }
  }
  suspend fun refreshGoalsNow() { goalNotifications?.refreshNow() }
  val connection by lazy { CloudConnection(this) }
  val database by lazy {
    Room.databaseBuilder(this, CalendarDatabase::class.java, "local-preview.db").addMigrations(CalendarDatabase.MIGRATION_1_2, CalendarDatabase.MIGRATION_2_3, CalendarDatabase.MIGRATION_3_4, CalendarDatabase.MIGRATION_4_5).build()
  }
  val goals by lazy { LocalGoals(database) }
  val store by lazy {
    LocalCalendar(database)
  }
}
