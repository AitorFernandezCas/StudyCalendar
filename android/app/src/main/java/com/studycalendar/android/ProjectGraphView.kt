package com.studycalendar.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.studycalendar.core.*
import java.time.LocalDate
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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
) {
  val density = LocalDensity.current.density
  val haptic = LocalHapticFeedback.current
  val heights = remember { mutableStateMapOf<String, Float>() }
  var viewport by remember { mutableStateOf<LayoutCoordinates?>(null) }
  var size by remember { mutableStateOf(PlanPoint(0f, 0f)) }
  var localError by remember { mutableStateOf<String?>(null) }
  var saving by remember { mutableStateOf(false) }
  val locked = busy || saving
  val currentLocked by rememberUpdatedState(locked)
  val currentTasks by rememberUpdatedState(tasks)
  val ids = remember(tasks) { tasks.map { it.id }.toSet() }
  val visibleIds = remember(tasks, session.root) { session.root?.let { ProjectGraph.branch(tasks, it) } ?: ids }
  val visible = remember(tasks, visibleIds) { tasks.filter { it.id in visibleIds } }
  val automatic = remember(visible, heights.toMap()) { ProjectGraph.layout(visible, heights) }
  val nodes = remember(automatic, session.activePositions) { automatic.map { node -> node.copy(position = session.activePositions[node.task.id] ?: node.position) } }
  val byId = remember(nodes) { nodes.associateBy { it.task.id } }
  val edges = remember(visible) { ProjectGraph.edges(visible) }
  val routes = remember(nodes, edges) { edges.map { ProjectGraph.route(it, nodes) } }
  fun persist() { preferences.write(session.positions.filterKeys { it in ids }) }
  fun move(id: String, dx: Float, dy: Float) {
    val node = byId[id] ?: return
    session.place(id, ProjectGraph.move(node.position, dx, dy, 1f))
    if (session.root == null) persist()
  }
  fun connect(source: String, target: Task) {
    if (currentLocked) return
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
    val task = currentTasks.find { it.id == id } ?: return
    session.connecting?.let { connect(it, task) } ?: run { session.selected = id }
  }
  val currentSelect by rememberUpdatedState<(String) -> Unit>(::select)

  LaunchedEffect(ids) {
    if (session.root != null && session.root !in ids) { session.full(); session.fitPending = true }
    if (session.selected !in ids) session.selected = null
    if (session.connecting !in ids) session.connecting = null
    if (session.drag?.id?.let { it !in ids } == true) session.cancelGestures()
    session.positions = session.positions.filterKeys { it in ids }
    preferences.write(session.positions)
  }
  LaunchedEffect(locked) { if (locked) session.cancelDrag() }
  DisposableEffect(session) { onDispose { session.cancelGestures() } }
  LaunchedEffect(nodes, size, session.fitPending) {
    if (session.fitPending && size.x > 0 && size.y > 0 && nodes.all { it.task.id in heights }) {
      session.camera = ProjectGraph.fit(nodes, size.x, size.y)
      session.fitPending = false
    }
  }
  // Keep the grab point under the finger when panning near an edge, even if it stops moving.
  LaunchedEffect(session.drag?.id) {
    var previous = withFrameNanos { it }
    while (session.drag != null) {
      val now = withFrameNanos { it }
      val dt = ((now - previous) / 1_000_000_000f).coerceAtMost(.05f)
      previous = now
      val active = session.drag ?: break
      fun speed(value: Float, limit: Float) = when {
        value < 36f -> -180f * ((36f - value) / 36f).coerceIn(0f, 1f)
        value > limit - 36f -> 180f * ((value - limit + 36f) / 36f).coerceIn(0f, 1f)
        else -> 0f
      }
      val camera = session.camera
      session.camera = camera.copy(left = camera.left + speed(active.pointer.x, size.x) * dt / camera.zoom, top = camera.top + speed(active.pointer.y, size.y) * dt / camera.zoom)
      session.dragTo(active.pointer)
    }
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
      TextButton(onClick = session::full) { Text("Ver proyecto completo") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
      OutlinedButton(onClick = { session.camera = ProjectGraph.zoom(session.camera, .8f, PlanPoint(size.x / 2, size.y / 2)) }, modifier = Modifier.semantics { contentDescription = "Alejar plan" }) { Text("−") }
      Text("${(session.camera.zoom * 100).roundToInt()}%", modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
      OutlinedButton(onClick = { session.camera = ProjectGraph.zoom(session.camera, 1.25f, PlanPoint(size.x / 2, size.y / 2)) }, modifier = Modifier.semantics { contentDescription = "Acercar plan" }) { Text("+") }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      TextButton(onClick = { session.cancelGestures(); session.fitPending = true }) { Text("Restablecer vista") }
      TextButton(onClick = { session.arrange(); if (session.root == null) persist() }) { Text("Organizar automáticamente") }
    }
    if (session.connecting != null) {
      Text("Elige la tarea que depende de «${tasks.find { it.id == session.connecting }?.title.orEmpty()}».", style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
      TextButton(onClick = { session.cancelGestures(); localError = null }) { Text("Cancelar conexión") }
    } else Text("Toca una tarea para ver sus acciones. Mantén pulsado para moverla; pellizca para ampliar.", style = MaterialTheme.typography.bodySmall)
    localError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
    if (tasks.isEmpty()) Text("Este proyecto todavía no tiene tareas. Usa Nueva tarea para empezar.")
    val arrowColor = MaterialTheme.colorScheme.outline
    val rootColor = MaterialTheme.colorScheme.primary
    val accent = Color(android.graphics.Color.parseColor(project.color))
    Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), color = MaterialTheme.colorScheme.surfaceVariant) {
      Box(Modifier.fillMaxWidth().height(availableHeight.coerceAtLeast(220.dp)).clipToBounds().testTag("project-graph")
        .onGloballyPositioned { viewport = it; size = PlanPoint(it.size.width / density, it.size.height / density) }
        .pointerInput(session, session.gestureEpoch) {
          detectTransformGestures { centroid, pan, zoom, _ ->
            if (session.drag == null) {
              val camera = session.camera
              session.camera = ProjectGraph.zoom(camera, zoom, PlanPoint(centroid.x / density, centroid.y / density)).let {
                it.copy(left = it.left - pan.x / density / it.zoom, top = it.top - pan.y / density / it.zoom)
              }
              session.fitPending = false
            }
          }
        }) {
        Canvas(Modifier.fillMaxSize()) {
          fun point(value: PlanPoint): Offset = ProjectGraph.screen(value, session.camera).let { Offset(it.x * density, it.y * density) }
          routes.forEach { curves ->
            val start = point(curves.first().start)
            val end = point(curves.last().end)
            val path = Path().apply {
              moveTo(start.x, start.y)
              curves.forEach { curve ->
                val first = point(curve.first); val second = point(curve.second); val finish = point(curve.end)
                cubicTo(first.x, first.y, second.x, second.y, finish.x, finish.y)
              }
            }
            drawPath(path, arrowColor, style = Stroke(2.dp.toPx()))
            val control = point(curves.last().second)
            val angle = atan2(end.y - control.y, end.x - control.x)
            val tip = 8.dp.toPx()
            val head = Path().apply {
              moveTo(end.x, end.y)
              lineTo(end.x - tip * cos(angle - .5f), end.y - tip * sin(angle - .5f))
              lineTo(end.x - tip * cos(angle + .5f), end.y - tip * sin(angle + .5f)); close()
            }
            drawPath(head, arrowColor)
          }
        }
        nodes.forEach { node -> key(node.task.id) {
          val task = node.task
          val latestNode by rememberUpdatedState(node)
          var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
          fun pointer(at: Offset): PlanPoint? {
            val view = viewport?.takeIf { it.isAttached } ?: return null
            val card = coordinates?.takeIf { it.isAttached } ?: return null
            val point = view.localPositionOf(card, at)
            return PlanPoint(point.x / density, point.y / density)
          }
          val blocked = ProjectGraph.blockers(task, tasks)
          val state = if (task.completed) "Completada" else if (blocked.isNotEmpty()) "Bloqueada" else "Disponible"
          val position = ProjectGraph.screen(node.position, session.camera)
          OutlinedCard(
            modifier = Modifier.offset { IntOffset((position.x * density).roundToInt(), (position.y * density).roundToInt()) }
              .graphicsLayer { scaleX = session.camera.zoom; scaleY = session.camera.zoom; transformOrigin = TransformOrigin(0f, 0f) }
              .requiredWidth(ProjectGraph.NODE_WIDTH.dp)
              .onGloballyPositioned { coordinates = it; val height = it.size.height / density; if (heights[task.id] != height) heights[task.id] = height }
              .testTag("plan-node-${task.id}")
              .semantics {
                contentDescription = task.title
                stateDescription = state + if (session.root == task.id) ", raíz de la rama" else ""
                role = Role.Button
                onClick("Ver acciones") { currentSelect(task.id); true }
                customActions = listOf(
                  CustomAccessibilityAction("Ver rama") { session.focus(task.id); true },
                  CustomAccessibilityAction("Mover a la izquierda") { move(task.id, -20f, 0f); true },
                  CustomAccessibilityAction("Mover a la derecha") { move(task.id, 20f, 0f); true },
                  CustomAccessibilityAction("Mover arriba") { move(task.id, 0f, -20f); true },
                  CustomAccessibilityAction("Mover abajo") { move(task.id, 0f, 20f); true },
                )
              }
              .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) false
                else when (event.key) {
                  Key.Enter, Key.Spacebar -> { currentSelect(task.id); true }
                  Key.DirectionLeft -> { move(task.id, -20f, 0f); true }
                  Key.DirectionRight -> { move(task.id, 20f, 0f); true }
                  Key.DirectionUp -> { move(task.id, 0f, -20f); true }
                  Key.DirectionDown -> { move(task.id, 0f, 20f); true }
                  else -> false
                }
              }.focusable()
              .pointerInput(task.id, session.gestureEpoch) { detectTapGestures(onTap = { currentSelect(task.id) }) }
              .pointerInput(task.id, session.gestureEpoch) {
                detectDragGesturesAfterLongPress(
                  onDragStart = { at -> if (session.connecting == null) pointer(at)?.let { session.beginDrag(task.id, latestNode.position, it); haptic.performHapticFeedback(HapticFeedbackType.LongPress) } },
                  onDrag = { change, _ -> if (session.drag?.id == task.id) { change.consume(); pointer(change.position)?.let(session::dragTo) } },
                  onDragEnd = { if (session.drag?.id == task.id) { session.finishDrag(); if (session.root == null) persist() } },
                  onDragCancel = { session.cancelDrag() },
                )
              },
            shape = RoundedCornerShape(10.dp),
            border = BorderStroke(if (session.root == task.id) 3.dp else 1.dp, if (session.root == task.id) rootColor else accent),
            colors = CardDefaults.outlinedCardColors(containerColor = if (task.completed) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
          ) {
            Column(Modifier.padding(12.dp).heightIn(min = 24.dp)) {
              Text(task.title, style = MaterialTheme.typography.titleSmall)
              Text((if (task.completed) "✓ " else if (blocked.isNotEmpty()) "⊘ " else "○ ") + state, style = MaterialTheme.typography.labelSmall)
            }
          }
        } }
      }
    }
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
        val blockers = ProjectGraph.blockers(selected, tasks)
        val completedDependents = tasks.filter { it.completed && selected.id in it.prerequisiteIds }
        Text(if (selected.completed) "Completada" else if (blockers.isNotEmpty()) "Bloqueada" else "Disponible")
        if (blockers.isNotEmpty()) Text("Antes: " + blockers.joinToString { id -> tasks.find { it.id == id }?.title ?: "Tarea no disponible" })
        if (selected.completed && completedDependents.isNotEmpty()) Text("Reabre primero: " + completedDependents.joinToString { it.title })
        Button(enabled = !locked && (if (selected.completed) completedDependents.isEmpty() else blockers.isEmpty()), onClick = { session.selected = null; complete(selected) }) { Text(if (selected.completed) "Reabrir" else "Completar") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(onClick = { session.selected = null; edit(selected) }) { Text(if (busy) "Ver detalles" else "Editar") }
          TextButton(onClick = { session.focus(selected.id) }) { Text("Ver rama") }
          TextButton(enabled = !locked, onClick = { session.selected = null; session.connecting = selected.id }) { Text("Conectar con…") }
        }
        Text("Tareas previas", style = MaterialTheme.typography.titleMedium)
        selected.prerequisiteIds.sorted().forEach { id ->
          val prior = tasks.find { it.id == id }
          Row {
            Text((prior?.title ?: "Tarea no disponible") + if (id !in visibleIds) " (fuera de rama)" else "", Modifier.weight(1f))
            TextButton(enabled = !locked, onClick = {
              saving = true
              links(selected, selected.prerequisiteIds - id) { saving = false }
            }, modifier = Modifier.semantics { contentDescription = "Quitar conexión de ${prior?.title ?: id}" }) { Text("Quitar") }
          }
        }
        var choosing by remember(selected.id) { mutableStateOf(false) }
        OutlinedButton(enabled = !locked, onClick = { choosing = !choosing }) { Text(if (choosing) "Cerrar selector" else "Añadir tarea previa") }
        if (choosing) tasks.filter { it.id != selected.id && it.id !in selected.prerequisiteIds }.sortedWith { a, b -> Overview.compareNames(a.title, b.title) }.forEach { candidate ->
          TextButton(enabled = !locked, onClick = { connect(candidate.id, selected) }) { Text(candidate.title) }
        }
        val dependents = tasks.filter { selected.id in it.prerequisiteIds }
        if (dependents.isNotEmpty()) {
          Text("Tareas dependientes", style = MaterialTheme.typography.titleMedium)
          dependents.forEach { dependent ->
            TextButton(onClick = { session.selected = dependent.id }) {
              Text(dependent.title + if (dependent.id !in visibleIds) " (fuera de rama)" else "")
            }
          }
        }
        Text("Mover tarjeta", style = MaterialTheme.typography.titleSmall)
        FlowRow {
          listOf(Triple("Izquierda", -20f, 0f), Triple("Derecha", 20f, 0f), Triple("Arriba", 0f, -20f), Triple("Abajo", 0f, 20f)).forEach { (label, dx, dy) ->
            TextButton(onClick = { move(selected.id, dx, dy) }) { Text(label) }
          }
        }
        val linked = selected.prerequisiteIds.isNotEmpty() || tasks.any { selected.id in it.prerequisiteIds }
        if (linked) Text("Quita primero las conexiones para eliminar esta tarea.", style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = !locked && !linked, onClick = { session.selected = null; delete(selected) }) { Text("Eliminar", color = MaterialTheme.colorScheme.error) }
      }
    }
  }
}
