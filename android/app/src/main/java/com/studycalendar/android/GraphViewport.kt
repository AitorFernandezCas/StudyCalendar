package com.studycalendar.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
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
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class GraphCard(val item: PlanItem, val status: String, val completed: Boolean, val label: String = status)
internal data class GraphArrow(val edge: PlanEdge, val dashed: Boolean = false)

@Composable
internal fun GraphViewport(
  cards: List<GraphCard>, arrows: List<GraphArrow>, session: ProjectGraphSession,
  preferences: ProjectGraphPreferences, accent: Color, busy: Boolean, availableHeight: Dp,
  tag: String, nodeTag: String, describeAction: String, select: (String) -> Unit,
  contentBeforeCanvas: @Composable () -> Unit = {},
  moves: MutableState<((String, Float, Float) -> Unit)?>,
  details: ((String) -> Unit)? = null,
) {
  val density = LocalDensity.current.density
  val haptic = LocalHapticFeedback.current
  val heights = remember { mutableStateMapOf<String, Float>() }
  var viewport by remember { mutableStateOf<LayoutCoordinates?>(null) }
  var size by remember { mutableStateOf(PlanPoint(0f, 0f)) }
  val byId = remember(cards) { cards.associateBy { it.item.id } }
  val layout = remember(cards, heights.toMap()) { ProjectGraph.layoutItems(cards.map { it.item }, heights) }
  val nodes = remember(layout, session.activePositions) { layout.map { it.copy(position = session.activePositions[it.id] ?: it.position) } }
  val routes = remember(nodes, arrows) { arrows.map { it to ProjectGraph.routeBoxes(it.edge, nodes) }.filter { it.second.isNotEmpty() } }
  val currentSelect by rememberUpdatedState(select)
  fun persist() = preferences.write(session.positions)
  val currentMove by rememberUpdatedState<(String, Float, Float) -> Unit>({ id, dx, dy ->
    nodes.find { it.id == id }?.let { node ->
      session.place(id, ProjectGraph.move(node.position, dx, dy, 1f))
      if (session.root == null) persist()
    }
  })
  DisposableEffect(session) {
    moves.value = { id, dx, dy -> currentMove(id, dx, dy) }
    onDispose { moves.value = null; session.cancelGestures() }
  }
  LaunchedEffect(busy) { if (busy) session.cancelDrag() }
  LaunchedEffect(nodes, size, session.fitPending) {
    if (session.fitPending && size.x > 0 && size.y > 0 && nodes.all { it.id in heights }) {
      session.camera = ProjectGraph.fitBoxes(nodes, size.x, size.y)
      session.fitPending = false
    }
  }
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
      session.camera = camera.copy(left = camera.left + speed(active.pointer.x, size.x) * dt / camera.zoom,
        top = camera.top + speed(active.pointer.y, size.y) * dt / camera.zoom)
      session.dragTo(active.pointer)
    }
  }
  Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
    OutlinedButton(onClick = { session.camera = ProjectGraph.zoom(session.camera, .8f, PlanPoint(size.x / 2, size.y / 2)) },
      modifier = Modifier.semantics { contentDescription = "Alejar plan" }) { Text("−") }
    Text("${(session.camera.zoom * 100).roundToInt()}%", modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelLarge)
    OutlinedButton(onClick = { session.camera = ProjectGraph.zoom(session.camera, 1.25f, PlanPoint(size.x / 2, size.y / 2)) },
      modifier = Modifier.semantics { contentDescription = "Acercar plan" }) { Text("+") }
  }
  FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    TextButton(onClick = { session.cancelGestures(); session.fitPending = true }) { Text("Restablecer vista") }
    TextButton(onClick = { session.arrange(); if (session.root == null) persist() }) { Text("Organizar automáticamente") }
  }
  contentBeforeCanvas()
  val arrowColor = MaterialTheme.colorScheme.outline
  val rootColor = MaterialTheme.colorScheme.primary
  Surface(shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    color = MaterialTheme.colorScheme.surfaceVariant) {
    Box(Modifier.fillMaxWidth().height(availableHeight.coerceAtLeast(220.dp)).clipToBounds().testTag(tag)
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
        routes.forEach { (arrow, curves) ->
          val start = point(curves.first().start)
          val end = point(curves.last().end)
          val path = Path().apply {
            moveTo(start.x, start.y)
            curves.forEach { curve ->
              val first = point(curve.first); val second = point(curve.second); val finish = point(curve.end)
              cubicTo(first.x, first.y, second.x, second.y, finish.x, finish.y)
            }
          }
          val dash = if (arrow.dashed) PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx())) else null
          drawPath(path, arrowColor, style = Stroke(2.dp.toPx(), pathEffect = dash))
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
      nodes.forEach { node -> key(node.id) {
        val card = byId.getValue(node.id)
        val latestNode by rememberUpdatedState(node)
        var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
        fun pointer(at: Offset): PlanPoint? {
          val view = viewport?.takeIf { it.isAttached } ?: return null
          val tile = coordinates?.takeIf { it.isAttached } ?: return null
          val point = view.localPositionOf(tile, at)
          return PlanPoint(point.x / density, point.y / density)
        }
        val position = ProjectGraph.screen(node.position, session.camera)
        OutlinedCard(
          modifier = Modifier.offset { IntOffset((position.x * density).roundToInt(), (position.y * density).roundToInt()) }
            .graphicsLayer { scaleX = session.camera.zoom; scaleY = session.camera.zoom; transformOrigin = TransformOrigin(0f, 0f) }
            .requiredWidth(ProjectGraph.NODE_WIDTH.dp)
            .onGloballyPositioned { coordinates = it; val height = it.size.height / density; if (heights[node.id] != height) heights[node.id] = height }
            .testTag("$nodeTag-${node.id}")
            .semantics {
              contentDescription = card.item.title
              stateDescription = card.status + if (session.root == node.id) ", raíz de la rama" else ""
              role = Role.Button
              onClick(describeAction) { currentSelect(node.id); true }
              customActions = listOf(
                CustomAccessibilityAction("Ver rama") { session.focus(node.id); true },
                CustomAccessibilityAction("Mover a la izquierda") { currentMove(node.id, -20f, 0f); true },
                CustomAccessibilityAction("Mover a la derecha") { currentMove(node.id, 20f, 0f); true },
                CustomAccessibilityAction("Mover arriba") { currentMove(node.id, 0f, -20f); true },
                CustomAccessibilityAction("Mover abajo") { currentMove(node.id, 0f, 20f); true },
              ) + listOfNotNull(details?.let { action ->
                CustomAccessibilityAction("Opciones de ${card.item.title}") { action(node.id); true }
              })
            }
            .onKeyEvent { event ->
              if (event.type != KeyEventType.KeyDown) false
              else when (event.key) {
                Key.Enter, Key.Spacebar -> { currentSelect(node.id); true }
                Key.DirectionLeft -> { currentMove(node.id, -20f, 0f); true }
                Key.DirectionRight -> { currentMove(node.id, 20f, 0f); true }
                Key.DirectionUp -> { currentMove(node.id, 0f, -20f); true }
                Key.DirectionDown -> { currentMove(node.id, 0f, 20f); true }
                else -> false
              }
            }.focusable()
            .pointerInput(node.id, session.gestureEpoch) { detectTapGestures(onTap = { currentSelect(node.id) }) }
            .pointerInput(node.id, session.gestureEpoch) {
              detectDragGesturesAfterLongPress(
                onDragStart = { at -> if (session.connecting == null) pointer(at)?.let {
                  session.beginDrag(node.id, latestNode.position, it); haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                } },
                onDrag = { change, _ -> if (session.drag?.id == node.id) { change.consume(); pointer(change.position)?.let(session::dragTo) } },
                onDragEnd = { if (session.drag?.id == node.id) { session.finishDrag(); if (session.root == null) persist() } },
                onDragCancel = { session.cancelDrag() },
              )
            },
          shape = RoundedCornerShape(10.dp),
          border = BorderStroke(if (session.root == node.id) 3.dp else 1.dp, if (session.root == node.id) rootColor else accent),
          colors = CardDefaults.outlinedCardColors(containerColor = if (card.completed) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
        ) {
          Column(Modifier.padding(12.dp).heightIn(min = 24.dp)) {
            Text(card.item.title, style = MaterialTheme.typography.titleSmall)
            Text(card.label, style = MaterialTheme.typography.labelSmall)
            if (details != null) TextButton(onClick = { details(node.id) },
              modifier = Modifier.semantics { contentDescription = "Opciones de ${card.item.title}" }) { Text("Opciones") }
          }
        }
      } }
    }
  }
}
