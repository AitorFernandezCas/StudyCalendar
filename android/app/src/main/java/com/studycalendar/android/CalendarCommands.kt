package com.studycalendar.android

import com.studycalendar.core.Category
import com.studycalendar.core.Project
import com.studycalendar.core.Task
import java.time.LocalDate

interface CalendarCommands {
  suspend fun save(task: Task)
  suspend fun complete(id: String)
  suspend fun delete(id: String)
  suspend fun saveCategory(category: Category)
  suspend fun deleteCategory(id: String)
  suspend fun saveProject(project: Project)
  suspend fun deleteProject(id: String)
  suspend fun saveRoutine(id: String, title: String, color: String, active: Boolean, startsOn: LocalDate)
  suspend fun completeRoutine(id: String, date: LocalDate, completed: Boolean)
  suspend fun deleteRoutine(id: String)
}
