package com.studycalendar.core

import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

data class Task(
  val id: String = UUID.randomUUID().toString(),
  val title: String,
  val date: LocalDate?,
  val start: LocalTime? = LocalTime.of(9, 0),
  val end: LocalTime? = LocalTime.of(10, 0),
  val allDay: Boolean = true,
  val completed: Boolean = false,
  val projectId: String? = null,
  val projectTask: Boolean = false,
  val prerequisiteIds: Set<String> = emptySet(),
  val categoryId: String? = null,
  val featureId: String? = null,
) {
  fun validate() {
    require(title.isNotBlank()) { "Escribe un título." }
    require(projectTask || (date != null && start != null && end != null)) { "Las tareas personales necesitan fecha y horario." }
    require((start == null) == (end == null)) { "Indica las dos horas." }
    require(start == null || end!!.isAfter(start)) { "La hora de fin debe ser posterior al inicio." }
    require(date != null || (projectTask && start == null && end == null)) { "Una tarea sin fecha no puede tener horas." }
    require(projectTask || (projectId == null && prerequisiteIds.isEmpty())) { "Las dependencias pertenecen a tareas de proyecto." }
    require(featureId == null || (projectTask && projectId != null)) { "La funcionalidad debe pertenecer al proyecto de la tarea." }
    require(id !in prerequisiteIds) { "Una tarea no puede depender de sí misma." }
  }
}

object Planning {
  fun withEdit(current: Task, original: Task, draft: Task): Task {
    val changedProject = draft.projectId != original.projectId || draft.projectTask != original.projectTask
    val changedFeature = draft.featureId != original.featureId
    if (changedProject || changedFeature) {
      require(current.projectId == original.projectId && current.projectTask == original.projectTask &&
        (current.featureId == original.featureId || current.featureId == draft.featureId)) {
        "La tarea ha cambiado de proyecto o funcionalidad. Vuelve a abrirla."
      }
    }
    val changedSchedule = draft.date != original.date || draft.start != original.start ||
      draft.end != original.end || draft.allDay != original.allDay
    if (draft.prerequisiteIds != original.prerequisiteIds) {
      require(current.projectId == original.projectId && current.prerequisiteIds == original.prerequisiteIds) {
        "Las tareas previas han cambiado. Revisa las conexiones antes de continuar."
      }
    }
    return current.copy(
      title = if (draft.title != original.title) draft.title else current.title,
      date = if (changedSchedule) draft.date else current.date,
      start = if (changedSchedule) draft.start else current.start,
      end = if (changedSchedule) draft.end else current.end,
      allDay = if (changedSchedule) draft.allDay else current.allDay,
      categoryId = if (draft.categoryId != original.categoryId) draft.categoryId else current.categoryId,
      projectId = if (changedProject) draft.projectId else current.projectId,
      projectTask = if (changedProject) draft.projectTask else current.projectTask,
      featureId = if (changedProject || changedFeature) draft.featureId else current.featureId,
      prerequisiteIds = if (draft.prerequisiteIds != original.prerequisiteIds) draft.prerequisiteIds else current.prerequisiteIds,
    )
  }

  fun withPrerequisites(current: Task, projectId: String, expected: Set<String>, desired: Set<String>): Task {
    require(current.projectTask && current.projectId == projectId) { "La tarea ha cambiado de proyecto. Vuelve a abrir el plan." }
    require(current.prerequisiteIds == expected) { "Las tareas previas han cambiado. Revisa las conexiones antes de continuar." }
    return current.copy(prerequisiteIds = desired.toSet())
  }

  fun save(task: Task, tasks: Collection<Task>) {
    task.validate()
    val previous = tasks.find { it.id == task.id }
    if (previous != null && (previous.projectId != task.projectId || previous.projectTask != task.projectTask)) {
      require(previous.prerequisiteIds.isEmpty() && tasks.none { task.id in it.prerequisiteIds }) { "Elimina primero los enlaces de dependencia." }
    }
    val graph = tasks.associateBy { it.id }.toMutableMap().also { it[task.id] = task }
    task.prerequisiteIds.forEach { id ->
      val required = requireNotNull(graph[id]) { "No se encuentra una tarea previa." }
      require(task.projectTask && task.projectId != null && required.projectTask && required.projectId == task.projectId) { "Las tareas previas deben pertenecer al mismo proyecto." }
      require(!task.completed || required.completed) { "Completa primero las tareas previas." }
    }
    val visited = mutableSetOf<String>()
    fun visit(id: String, path: Set<String>) {
      require(id !in path) { "Las dependencias no pueden formar un ciclo." }
      if (!visited.add(id)) return
      graph[id]?.prerequisiteIds?.forEach { visit(it, path + id) }
    }
    visit(task.id, emptySet())
    if (!task.completed) require(tasks.none { it.completed && task.id in it.prerequisiteIds }) { "Reabre primero las tareas dependientes." }
  }

  fun delete(id: String, tasks: Collection<Task>) {
    require(tasks.any { it.id == id }) { "La tarea ya no está disponible." }
    require(tasks.none { (it.id == id && it.prerequisiteIds.isNotEmpty()) || id in it.prerequisiteIds }) { "Elimina primero los enlaces de dependencia." }
  }
}
