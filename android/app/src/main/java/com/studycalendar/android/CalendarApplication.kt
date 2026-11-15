package com.studycalendar.android

import android.app.Application
import androidx.room.Room

class CalendarApplication : Application() {
  val connection by lazy { CloudConnection(this) }
  val store by lazy {
    LocalCalendar(Room.databaseBuilder(this, CalendarDatabase::class.java, "local-preview.db")
      .addMigrations(CalendarDatabase.MIGRATION_1_2, CalendarDatabase.MIGRATION_2_3).build())
  }
}
