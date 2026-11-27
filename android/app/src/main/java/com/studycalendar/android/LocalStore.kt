package com.studycalendar.android

import androidx.room.*
import com.studycalendar.core.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

@Entity(tableName = "tasks")
data class TaskRow(
  @PrimaryKey val id: String,
  val title: String,
  val date: String?,
  val start: String?,
  val end: String?,
  val allDay: Boolean,
  val completed: Boolean,
  val projectId: String?,
  val projectTask: Boolean,
  val prerequisites: String,
  val categoryId: String? = null,
  val featureId: String? = null,
) {
  fun task() = Task(id, title, date?.let(LocalDate::parse), start?.let(LocalTime::parse), end?.let(LocalTime::parse), allDay, completed, projectId, projectTask,
    JSONArray(prerequisites).let { array -> (0 until array.length()).map { array.getString(it) }.toSet() }, categoryId, featureId)

  fun snapshot(): String = JSONObject().apply {
    put("id", id); put("title", title); put("date", date ?: JSONObject.NULL)
    put("start_time", start ?: JSONObject.NULL); put("end_time", end ?: JSONObject.NULL)
    put("all_day", allDay); put("completed", completed)
    put("category_id", categoryId ?: JSONObject.NULL)
    put("project_id", projectId ?: JSONObject.NULL); put("task_type", if (projectTask) "project" else "daily")
    put("feature_id", featureId ?: JSONObject.NULL)
    put("prerequisite_ids", JSONArray(prerequisites))
  }.toString()

  companion object {
    fun from(task: Task) = TaskRow(task.id, task.title.trim(), task.date?.toString(), task.start?.toString(), task.end?.toString(), task.allDay, task.completed,
      task.projectId, task.projectTask, JSONArray(task.prerequisiteIds.toList()).toString(), task.categoryId, task.featureId)
  }
}

@Entity(tableName = "categories")
data class CategoryRow(@PrimaryKey val id: String, val name: String, val color: String) {
  fun category() = Category(id, name, color)
  fun snapshot() = JSONObject().put("id", id).put("name", name).put("color", color).toString()
  companion object { fun from(category: Category) = CategoryRow(category.id, category.name.trim(), category.color) }
}

// Local journal only. Sending these records is disabled until the authenticated,
// versioned server contract exists; they must never be replayed as raw CRUD.
@Entity(tableName = "local_changes", indices = [Index("entityId"), Index(value = ["operationId"], unique = true)])
data class LocalChange(
  @PrimaryKey(autoGenerate = true) val sequence: Long = 0,
  val operationId: String = UUID.randomUUID().toString(),
  val entityId: String,
  val kind: String,
  val before: String?,
  val after: String?,
  val recordedAt: String = Instant.now().toString(),
)

@Dao
interface CalendarDao {
  @Query("SELECT * FROM tasks ORDER BY date, allDay DESC, start, title") fun observeTasks(): Flow<List<TaskRow>>
  @Query("SELECT COUNT(*) FROM local_changes") fun observePending(): Flow<Int>
  @Query("SELECT * FROM tasks") suspend fun tasks(): List<TaskRow>
  @Query("SELECT * FROM local_changes ORDER BY sequence") suspend fun changes(): List<LocalChange>
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(task: TaskRow)
  @Insert suspend fun append(change: LocalChange)
  @Query("DELETE FROM tasks WHERE id = :id") suspend fun delete(id: String)
  @Query("SELECT * FROM categories ORDER BY name COLLATE NOCASE") fun observeCategories(): Flow<List<CategoryRow>>
  @Query("SELECT * FROM categories") suspend fun categories(): List<CategoryRow>
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCategory(category: CategoryRow)
  @Query("DELETE FROM categories WHERE id = :id") suspend fun deleteCategory(id: String)
  @Query("UPDATE tasks SET categoryId = NULL WHERE categoryId = :id") suspend fun clearCategory(id: String)
  @Query("SELECT * FROM projects ORDER BY name COLLATE NOCASE") fun observeProjects(): Flow<List<ProjectRow>>
  @Query("SELECT * FROM projects") suspend fun projects(): List<ProjectRow>
  @Upsert suspend fun putProject(project: ProjectRow)
  @Query("DELETE FROM projects WHERE id = :id") suspend fun deleteProject(id: String)
  @Query("SELECT * FROM project_features") fun observeFeatures(): Flow<List<ProjectFeatureRow>>
  @Query("SELECT * FROM project_features") suspend fun features(): List<ProjectFeatureRow>
  @Upsert suspend fun putFeature(feature: ProjectFeatureRow)
  @Query("DELETE FROM project_features WHERE id = :id") suspend fun deleteFeature(id: String)
  @Query("UPDATE tasks SET featureId = NULL WHERE featureId = :id") suspend fun clearFeature(id: String)
  @Query("SELECT * FROM project_feature_links") fun observeFeatureLinks(): Flow<List<ProjectFeatureLinkRow>>
  @Query("SELECT * FROM project_feature_links") suspend fun featureLinks(): List<ProjectFeatureLinkRow>
  @Insert suspend fun putFeatureLink(link: ProjectFeatureLinkRow)
  @Query("DELETE FROM project_feature_links WHERE id = :id") suspend fun deleteFeatureLink(id: String)
  @Transaction @Query("SELECT * FROM routines ORDER BY title COLLATE NOCASE") fun observeRoutines(): Flow<List<RoutineRecord>>
  @Transaction @Query("SELECT * FROM routines") suspend fun routines(): List<RoutineRecord>
  @Insert suspend fun insertRoutine(routine: RoutineRow)
  @Update suspend fun updateRoutine(routine: RoutineRow)
  @Query("DELETE FROM routines WHERE id = :id") suspend fun deleteRoutine(id: String)
  @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putCompletion(completion: CompletionRow)
}

@Database(entities = [TaskRow::class, LocalChange::class, ProjectRow::class, RoutineRow::class, CompletionRow::class,
  CategoryRow::class, ProjectFeatureRow::class, ProjectFeatureLinkRow::class, GoalRow::class, GoalReviewRow::class, GoalScheduleRow::class], version = 5, exportSchema = true)
abstract class CalendarDatabase : RoomDatabase() {
  abstract fun calendar(): CalendarDao
  abstract fun goals(): GoalDao

  companion object {
    val MIGRATION_4_5 = object : Migration(4, 5) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE goals (id TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE goal_reviews (id TEXT NOT NULL PRIMARY KEY, goalId TEXT NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE goal_schedules (id TEXT NOT NULL PRIMARY KEY, payload TEXT NOT NULL)")
      }
    }
    val MIGRATION_3_4 = object : Migration(3, 4) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN featureId TEXT")
        db.execSQL("CREATE TABLE project_features (id TEXT NOT NULL PRIMARY KEY, projectId TEXT NOT NULL, name TEXT NOT NULL, description TEXT, FOREIGN KEY(projectId) REFERENCES projects(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX index_project_features_projectId ON project_features(projectId)")
        db.execSQL("CREATE TABLE project_feature_links (id TEXT NOT NULL PRIMARY KEY, projectId TEXT NOT NULL, sourceId TEXT NOT NULL, targetId TEXT NOT NULL, FOREIGN KEY(sourceId) REFERENCES project_features(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(targetId) REFERENCES project_features(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE UNIQUE INDEX index_project_feature_links_sourceId_targetId ON project_feature_links(sourceId, targetId)")
        db.execSQL("CREATE INDEX index_project_feature_links_targetId ON project_feature_links(targetId)")
      }
    }
    val MIGRATION_2_3 = object : Migration(2, 3) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE tasks ADD COLUMN categoryId TEXT")
        db.execSQL("CREATE TABLE IF NOT EXISTS categories (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, color TEXT NOT NULL)")
      }
    }
    val MIGRATION_1_2 = object : Migration(1, 2) {
      override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS projects (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, color TEXT NOT NULL, status TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS routines (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, color TEXT NOT NULL, active INTEGER NOT NULL, periods TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS routine_completions (routineId TEXT NOT NULL, date TEXT NOT NULL, completed INTEGER NOT NULL, PRIMARY KEY(routineId, date), FOREIGN KEY(routineId) REFERENCES routines(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_routine_completions_routineId ON routine_completions(routineId)")
      }
    }
  }
}

class LocalCalendar(private val database: CalendarDatabase, private val clock: Clock = Clock.systemUTC()) : CalendarCommands {
  val tasks = database.calendar().observeTasks()
  val pending = database.calendar().observePending()
  val projects = database.calendar().observeProjects()
  val features = database.calendar().observeFeatures()
  val featureLinks = database.calendar().observeFeatureLinks()
  val categories = database.calendar().observeCategories()
  val routines = database.calendar().observeRoutines()

  override suspend fun reschedule(before: Task, after: Task) = database.withTransaction {
    val current = requireNotNull(database.calendar().tasks().find { it.id == before.id }) { "La tarea ya no está disponible." }.task()
    require(current == before) { "La tarea ha cambiado. Revisa su horario antes de moverla." }
    save(current.copy(date = after.date, start = after.start, end = after.end, allDay = after.allDay))
  }
  override suspend fun setPrerequisites(id: String, expectedProjectId: String, expected: Set<String>, desired: Set<String>) = database.withTransaction {
    val current = requireNotNull(database.calendar().tasks().find { it.id == id }) { "La tarea ya no está disponible." }.task()
    save(Planning.withPrerequisites(current, expectedProjectId, expected, desired))
  }

  override suspend fun save(task: Task) = database.withTransaction {
    val rows = database.calendar().tasks()
    task.projectId?.let { id ->
      val project = requireNotNull(database.calendar().projects().find { it.id == id }) { "El proyecto ya no está disponible." }.project()
      val previous = rows.find { it.id == task.id }
      require(project.status == ProjectStatus.ACTIVE || previous?.projectId == id) { "Selecciona un proyecto activo." }
    }
    task.categoryId?.let { id -> require(database.calendar().categories().any { it.id == id }) { "La categoría ya no está disponible." } }
    if (task.featureId != null) Features.validateMembership(task, database.calendar().features().map(ProjectFeatureRow::feature))
    Planning.save(task, rows.map(TaskRow::task))
    val row = TaskRow.from(task)
    val previous = rows.find { it.id == task.id }
    if (row != previous) {
      database.calendar().put(row)
      database.calendar().append(LocalChange(entityId = task.id, kind = if (previous == null) "create" else "update", before = previous?.snapshot(), after = row.snapshot()))
    }
  }

  override suspend fun editTask(original: Task, draft: Task) = database.withTransaction {
    val current = requireNotNull(database.calendar().tasks().find { it.id == original.id }) { "La tarea ya no está disponible." }.task()
    save(Planning.withEdit(current, original, draft))
  }

  override suspend fun complete(id: String) = database.withTransaction {
    val task = requireNotNull(database.calendar().tasks().find { it.id == id }) { "La tarea ya no está disponible." }.task()
    save(task.copy(completed = !task.completed))
  }

  override suspend fun setCompletion(id: String, expected: Boolean, completed: Boolean) = database.withTransaction {
    val task = requireNotNull(database.calendar().tasks().find { it.id == id }) { "La tarea ya no está disponible." }.task()
    require(task.completed == expected) { "La tarea ha cambiado. Revisa su estado antes de continuar." }
    save(task.copy(completed = completed))
  }

  override suspend fun setRoutineCompletion(id: String, date: LocalDate, expected: Boolean, completed: Boolean) = database.withTransaction {
    val record = requireNotNull(database.calendar().routines().find { it.row.id == id }) { "La rutina ya no está disponible." }
    require((date in record.completedDates()) == expected) { "La rutina ha cambiado. Revisa su estado antes de continuar." }
    completeRoutine(id, date, completed)
  }

  override suspend fun delete(id: String) = database.withTransaction {
    val rows = database.calendar().tasks()
    Planning.delete(id, rows.map(TaskRow::task))
    val previous = rows.first { it.id == id }
    database.calendar().append(LocalChange(entityId = id, kind = "delete", before = previous.snapshot(), after = null))
    database.calendar().delete(id)
  }

  override suspend fun saveCategory(category: Category) = database.withTransaction {
    val dao = database.calendar()
    val categories = dao.categories()
    category.validate(categories.map(CategoryRow::category))
    val row = CategoryRow.from(category)
    val previous = categories.find { it.id == row.id }
    if (row != previous) {
      dao.putCategory(row)
      dao.append(LocalChange(entityId = row.id, kind = "category.${if (previous == null) "create" else "update"}", before = previous?.snapshot(), after = row.snapshot()))
    }
  }

  override suspend fun deleteCategory(id: String) = database.withTransaction {
    val dao = database.calendar()
    val previous = requireNotNull(dao.categories().find { it.id == id }) { "La categoría ya no está disponible." }
    dao.clearCategory(id)
    dao.deleteCategory(id)
    dao.append(LocalChange(entityId = id, kind = "category.delete", before = previous.snapshot(), after = null))
  }

  override suspend fun saveProject(project: Project) = database.withTransaction {
    val dao = database.calendar()
    val projects = dao.projects()
    project.validate(projects.map(ProjectRow::project))
    val previous = projects.find { it.id == project.id }
    val row = ProjectRow.from(project)
    if (row != previous) {
      dao.putProject(row)
      dao.append(LocalChange(entityId = row.id, kind = "project.${if (previous == null) "create" else "update"}", before = previous?.snapshot(), after = row.snapshot()))
    }
  }

  override suspend fun deleteProject(id: String) = database.withTransaction {
    val dao = database.calendar()
    val project = requireNotNull(dao.projects().find { it.id == id }) { "El proyecto ya no está disponible." }
    // One transaction for the project, all its tasks/dependency links and journal.
    dao.tasks().filter { it.projectId == id }.forEach {
      dao.append(LocalChange(entityId = it.id, kind = "delete", before = it.snapshot(), after = null))
      dao.delete(it.id)
    }
    dao.append(LocalChange(entityId = id, kind = "project.delete", before = project.snapshot(), after = null))
    dao.deleteProject(id)
  }

  override suspend fun saveFeature(feature: ProjectFeature, original: ProjectFeature?) = database.withTransaction {
    val dao = database.calendar()
    val value = feature.normalized()
    val features = dao.features()
    val previous = features.find { it.id == value.id }
    require(dao.projects().any { it.id == value.projectId }) { "El proyecto ya no está disponible." }
    val row = ProjectFeatureRow.from(featureEdit(value, original, previous?.feature()))
    Features.validate(row.feature(), features.map(ProjectFeatureRow::feature))
    if (row != previous) {
      dao.putFeature(row)
      dao.append(LocalChange(entityId = row.id, kind = "feature.${if (previous == null) "create" else "update"}",
        before = previous?.json()?.toString(), after = row.json().toString()))
    }
  }

  override suspend fun deleteFeature(feature: ProjectFeature) = database.withTransaction {
    val dao = database.calendar()
    val row = requireNotNull(dao.features().find { it.id == feature.id }) { "La funcionalidad ya no está disponible." }
    require(row.feature() == feature) { "La funcionalidad ha cambiado. Revisa sus datos antes de eliminarla." }
    dao.clearFeature(feature.id)
    dao.deleteFeature(feature.id)
    dao.append(LocalChange(entityId = feature.id, kind = "feature.delete", before = row.json().toString(), after = null))
  }

  override suspend fun createFeatureLink(link: ProjectFeatureLink) = database.withTransaction {
    val dao = database.calendar()
    Features.validateLink(link, dao.features().map(ProjectFeatureRow::feature), dao.featureLinks().map(ProjectFeatureLinkRow::link))
    val row = ProjectFeatureLinkRow.from(link)
    dao.putFeatureLink(row)
    dao.append(LocalChange(entityId = link.id, kind = "feature-link.create", before = null, after = row.json().toString()))
  }

  override suspend fun deleteFeatureLink(link: ProjectFeatureLink) = database.withTransaction {
    val dao = database.calendar()
    val row = requireNotNull(dao.featureLinks().find { it.id == link.id }) { "La conexión ya no está disponible." }
    require(row.link() == link) { "La conexión ya no está disponible." }
    dao.deleteFeatureLink(link.id)
    dao.append(LocalChange(entityId = link.id, kind = "feature-link.delete", before = row.json().toString(), after = null))
  }

  override suspend fun saveRoutine(id: String, title: String, color: String, active: Boolean, startsOn: LocalDate) = database.withTransaction {
    val dao = database.calendar()
    val previous = dao.routines().find { it.row.id == id }?.row
    val today = HabitClock.day(clock.instant()).date
    val routine = if (previous == null) Routine(id, title.trim(), color, active, if (active) listOf(ActivePeriod(startsOn)) else emptyList())
      else previous.routine().activation(active, today).copy(title = title.trim(), color = color)
    routine.validate()
    val row = RoutineRow.from(routine)
    if (row != previous) {
      if (previous == null) dao.insertRoutine(row) else dao.updateRoutine(row)
      dao.append(LocalChange(entityId = id, kind = "routine.${if (previous == null) "create" else "update"}", before = previous?.snapshot(), after = row.snapshot()))
    }
  }

  override suspend fun completeRoutine(id: String, date: LocalDate, completed: Boolean) = database.withTransaction {
    val instant = clock.instant()
    require(date == HabitClock.day(instant).date) { "El día ha cambiado. Vuelve a intentarlo con las rutinas de hoy." }
    val dao = database.calendar()
    val record = requireNotNull(dao.routines().find { it.row.id == id }) { "La rutina ya no está disponible." }
    require(record.row.routine().due(date)) { "Esta rutina no está activa para hoy." }
    val previous = record.completions.find { it.date == date.toString() }
    val row = CompletionRow(id, date.toString(), completed)
    if ((previous?.completed ?: false) != completed) {
      dao.putCompletion(row)
      dao.append(LocalChange(entityId = id, kind = "routine.completion", before = previous?.snapshot(), after = row.snapshot(), recordedAt = instant.toString()))
    }
  }

  override suspend fun deleteRoutine(id: String) = database.withTransaction {
    val dao = database.calendar()
    val record = requireNotNull(dao.routines().find { it.row.id == id }) { "La rutina ya no está disponible." }
    val snapshot = JSONObject(record.row.snapshot()).put("completions", JSONArray(record.completions.map { JSONObject(it.snapshot()) })).toString()
    dao.append(LocalChange(entityId = id, kind = "routine.delete", before = snapshot, after = null))
    dao.deleteRoutine(id)
  }
}
