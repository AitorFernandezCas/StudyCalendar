package com.studycalendar.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.studycalendar.core.*
import java.time.LocalDate

@Composable
fun ProjectViewSwitch(graph: Boolean, choose: (Boolean) -> Unit) {
  SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
    listOf(true to "Gráfico", false to "Lista").forEachIndexed { index, (value, label) ->
      SegmentedButton(selected = graph == value, onClick = { choose(value) }, shape = SegmentedButtonDefaults.itemShape(index, 2)) { Text(label) }
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectGraphView(
  tasks: List<Task>, project: Project, today: LocalDate, session: ProjectGraphSession,
  preferences: ProjectGraphPreferences, busy: Boolean, availableHeight: Dp,
  edit: (Task) -> Unit, complete: (Task) -> Unit, delete: (Task) -> Unit,
  links: (Task, Set<String>, (Boolean) -> Unit) -> Unit,
  projectTasks: List<Task> = tasks, groupName: String? = null,
  featureNames: Map<String, String> = emptyMap(), openExternal: (Task) -> Unit = {},
) {
  val moves = remember { mutableStateOf<((String, Float, Float) -> Unit)?>(null) }
  var localError by remember { mutableStateOf<String?>(null) }
  var saving by remember { mutableStateOf(false) }
  val locked = busy || saving
  val ids = remember(tasks) { tasks.map { it.id }.toSet() }
  val visibleIds = remember(tasks, session.root) { session.root?.let { ProjectGraph.branch(tasks, it) } ?: ids }
  val visible = remember(tasks, visibleIds) { tasks.filter { it.id in visibleIds } }
  val groupLabel: (Task) -> String = { featureNames[it.featureId] ?: "Sin funcionalidad" }
  fun reference(task: Task): String = task.title + when {
    task.id !in ids -> " · " + groupLabel(task)
    task.id !in visibleIds -> " (fuera de rama)"
    else -> ""
  }
  fun inspect(task: Task) {
    if (task.id in ids) session.selected = task.id
    else { session.selected = null; openExternal(task) }
  }
  fun connect(source: String, target: Task) {
    if (locked) return
    if (source == target.id || source in target.prerequisiteIds) {
      localError = if (source == target.id) "Una tarea no puede depender de sí misma." else "Esta conexión ya existe."
      return
    }
    saving = true
    links(target, target.prerequisiteIds + source) { success ->
      saving = false
      if (success) { session.connecting = null; localError = null }
    }
  }
  fun select(id: String) {
    val task = tasks.find { it.id == id } ?: return
    session.connecting?.let { connect(it, task) } ?: run { session.selected = id }
  }
  LaunchedEffect(ids) {
    if (session.root != null && session.root !in ids) { session.full(); session.fitPending = true }
    if (session.selected !in ids) session.selected = null
    if (session.connecting !in ids) session.connecting = null
    if (session.drag?.id?.let { it !in ids } == true) session.cancelGestures()
  }
  BackHandler(session.drag != null || session.connecting != null || session.root != null) {
    when {
      session.drag != null || session.connecting != null -> { session.cancelGestures(); localError = null }
      else -> session.full()
    }
  }

  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    session.root?.let { root ->
      Text("Rama de «${tasks.find { it.id == root }?.title.orEmpty()}»", style = MaterialTheme.typography.titleSmall)
      Text("${visible.size} de ${tasks.size} tareas", style = MaterialTheme.typography.bodySmall)
      TextButton(onClick = session::full) { Text(if (groupName == null) "Ver proyecto completo" else "Ver plan completo") }
    }
    val cards = visible.map { task ->
      val blocked = ProjectGraph.blockers(task, projectTasks)
      val state = if (task.completed) "Completada" else if (blocked.isNotEmpty()) "Bloqueada" else "Disponible"
      GraphCard(PlanItem(task.id, task.title, task.prerequisiteIds), state, task.completed,
        (if (task.completed) "✓ " else if (blocked.isNotEmpty()) "⊘ " else "○ ") + state)
    }
    val accent = Color(android.graphics.Color.parseColor(project.color))
    GraphViewport(cards, ProjectGraph.edges(visible).map { GraphArrow(it) }, session, preferences, accent, locked,
      availableHeight, "project-graph", "plan-node", "Ver acciones", ::select, contentBeforeCanvas = {
        if (session.connecting != null) {
          Text("Elige la tarea que depende de «${tasks.find { it.id == session.connecting }?.title.orEmpty()}».",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
          TextButton(onClick = { session.cancelGestures(); localError = null }) { Text("Cancelar conexión") }
        } else Text("Toca una tarea para ver sus acciones. Mantén pulsado para moverla; pellizca para ampliar.", style = MaterialTheme.typography.bodySmall)
        localError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (tasks.isEmpty()) Text(if (groupName == null) "Este proyecto todavía no tiene tareas. Usa Nueva tarea para empezar." else "Sin tareas")
      }, moves = moves)
  }

  tasks.find { it.id == session.selected }?.let { selected ->
    ModalBottomSheet(onDismissRequest = { session.selected = null }) {
      Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(selected.title, style = MaterialTheme.typography.titleLarge)
        Text(Overview.relativeDate(selected.date, today) + when {
          selected.date == null -> ""
          selected.allDay -> " · Todo el día"
          else -> " · ${selected.start}–${selected.end}"
        })
        groupName?.let { Text("${project.name} · $it", style = MaterialTheme.typography.labelLarge) }
        val blockers = ProjectGraph.blockers(selected, projectTasks)
        val completedDependents = projectTasks.filter { it.completed && selected.id in it.prerequisiteIds }
        Text(if (selected.completed) "Completada" else if (blockers.isNotEmpty()) "Bloqueada" else "Disponible")
        if (blockers.isNotEmpty()) Text("Antes: " + blockers.joinToString { id -> projectTasks.find { it.id == id }?.let(::reference) ?: "Tarea no disponible" })
        if (selected.completed && completedDependents.isNotEmpty()) Text("Reabre primero: " + completedDependents.joinToString(transform = ::reference))
        Button(enabled = !locked && (if (selected.completed) completedDependents.isEmpty() else blockers.isEmpty()), onClick = { session.selected = null; complete(selected) }) { Text(if (selected.completed) "Reabrir" else "Completar") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(onClick = { session.selected = null; edit(selected) }) { Text(if (busy) "Ver detalles" else "Editar") }
          TextButton(onClick = { session.focus(selected.id) }) { Text("Ver rama") }
          TextButton(enabled = !locked, onClick = { session.selected = null; session.connecting = selected.id }) { Text("Conectar con…") }
        }
        Text("Tareas previas", style = MaterialTheme.typography.titleMedium)
        selected.prerequisiteIds.sorted().forEach { id ->
          val prior = projectTasks.find { it.id == id }
          Row {
            if (prior != null && id !in ids) {
              TextButton(onClick = { inspect(prior) }, modifier = Modifier.weight(1f)) { Text(reference(prior)) }
            } else Text(prior?.let(::reference) ?: "Tarea no disponible", Modifier.weight(1f))
            TextButton(enabled = !locked, onClick = {
              saving = true
              links(selected, selected.prerequisiteIds - id) { saving = false }
            }, modifier = Modifier.semantics { contentDescription = "Quitar conexión de ${prior?.title ?: id}" }) { Text("Quitar") }
          }
        }
        var choosing by remember(selected.id) { mutableStateOf(false) }
        OutlinedButton(enabled = !locked, onClick = { choosing = !choosing }) { Text(if (choosing) "Cerrar selector" else "Añadir tarea previa") }
        if (choosing) projectTasks.filter { it.id != selected.id && it.id !in selected.prerequisiteIds }.sortedWith { a, b -> Overview.compareNames(a.title, b.title) }.forEach { candidate ->
          TextButton(enabled = !locked, onClick = { connect(candidate.id, selected) }) { Text(reference(candidate)) }
        }
        val dependents = projectTasks.filter { selected.id in it.prerequisiteIds }
        if (dependents.isNotEmpty()) {
          Text("Tareas dependientes", style = MaterialTheme.typography.titleMedium)
          dependents.forEach { dependent ->
            TextButton(onClick = { inspect(dependent) }) {
              Text(reference(dependent))
            }
          }
        }
        Text("Mover tarjeta", style = MaterialTheme.typography.titleSmall)
        FlowRow {
          listOf(Triple("Izquierda", -20f, 0f), Triple("Derecha", 20f, 0f), Triple("Arriba", 0f, -20f), Triple("Abajo", 0f, 20f)).forEach { (label, dx, dy) ->
            TextButton(onClick = { moves.value?.invoke(selected.id, dx, dy) }) { Text(label) }
          }
        }
        val linked = selected.prerequisiteIds.isNotEmpty() || projectTasks.any { selected.id in it.prerequisiteIds }
        if (linked) Text("Quita primero las conexiones para eliminar esta tarea.", style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = !locked && !linked, onClick = { session.selected = null; delete(selected) }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
      }
    }
  }
}
