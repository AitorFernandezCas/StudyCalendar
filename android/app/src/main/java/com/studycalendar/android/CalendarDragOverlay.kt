package com.studycalendar.android

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studycalendar.core.Task

internal enum class DragPhase { DRAGGING, SAVING, LANDING }

internal class CalendarDragSession(val task: Task, val source: Rect, val grab: Offset, val color: Color) {
  var pointer by mutableStateOf(source.topLeft + grab)
  var phase by mutableStateOf(DragPhase.DRAGGING)
  var preview by mutableStateOf<Task?>(null)
  var destination by mutableStateOf<Rect?>(null)
  var released by mutableStateOf(source)
  var landing by mutableStateOf(source)
  var fade by mutableStateOf(false)
  val progress = Animatable(0f)
  val floating: Rect get() = if (phase == DragPhase.DRAGGING) Rect(pointer - grab, source.size) else {
    val fraction = progress.value
    Rect(released.left + (landing.left - released.left) * fraction,
      released.top + (landing.top - released.top) * fraction,
      released.right + (landing.right - released.right) * fraction,
      released.bottom + (landing.bottom - released.bottom) * fraction)
  }
  fun release() { released = floating; landing = released; phase = DragPhase.SAVING }
}

internal class CalendarDragController {
  var session by mutableStateOf<CalendarDragSession?>(null)
    private set
  fun begin(task: Task, source: Rect, grab: Offset, color: Color): CalendarDragSession? {
    if (session != null) return null
    return CalendarDragSession(task, source, grab, color).also { session = it }
  }
  fun clear(expected: CalendarDragSession? = session) { if (session === expected) session = null }
  suspend fun land(session: CalendarDragSession, bounds: Rect?) {
    if (this.session !== session) return
    session.landing = bounds ?: session.released
    session.fade = bounds == null
    session.phase = DragPhase.LANDING
    session.progress.animateTo(1f, tween(150, easing = FastOutSlowInEasing))
    clear(session)
  }
}

/** A sibling of the scrolling calendar, so the lifted card never clips to a day. */
@Composable
internal fun CalendarDragOverlay(controller: CalendarDragController, modifier: Modifier = Modifier) {
  var hostWidth by remember { mutableStateOf(0f) }
  var origin by remember { mutableStateOf(Offset.Zero) }
  val session = controller.session
  Box(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow(); hostWidth = it.size.width.toFloat() }) {
    if (session != null) {
      val density = LocalDensity.current
      Canvas(Modifier.fillMaxSize().clearAndSetSemantics { }) {
        session.destination?.let { destination ->
          drawRoundRect(session.color.copy(alpha = .18f), topLeft = destination.topLeft - origin,
            size = destination.size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()))
        }
      }
      Surface(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(with(density) { session.source.width.toDp() }, with(density) { session.source.height.toDp() })
        .graphicsLayer {
          val rect = session.floating
          // Layer transforms track input without relaying out the calendar each frame.
          translationX = rect.left - origin.x + (rect.width - session.source.width) / 2
          translationY = rect.top - origin.y + (rect.height - session.source.height) / 2
          scaleX = rect.width / session.source.width
          scaleY = rect.height / session.source.height
          alpha = if (session.fade) 1f - session.progress.value else .97f
          shadowElevation = 8.dp.toPx()
        }.testTag("calendar-drag-card").clearAndSetSemantics { },
        shape = RoundedCornerShape(6.dp), color = androidx.compose.ui.graphics.lerp(MaterialTheme.colorScheme.surface, session.color, if (session.task.completed) .10f else .20f),
        contentColor = MaterialTheme.colorScheme.onSurface, border = BorderStroke(1.dp, session.color)) {
        Text((if (session.task.completed) "✓ " else "") + session.task.title, Modifier.padding(5.dp), maxLines = 3,
          overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
      }
      if (session.phase == DragPhase.DRAGGING) Surface(Modifier.widthIn(max = 250.dp).graphicsLayer {
        translationX = (session.pointer.x - origin.x).coerceIn(0f, (hostWidth - size.width).coerceAtLeast(0f))
        translationY = session.floating.bottom - origin.y + 8.dp.toPx()
      }.clearAndSetSemantics { }, shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.inverseSurface) {
        Text(session.preview?.let { "${it.date} · ${if (it.allDay) "Todo el día" else "${it.start}–${it.end}"}" }
          ?: "Suelta fuera para cancelar", Modifier.padding(8.dp), color = MaterialTheme.colorScheme.inverseOnSurface,
          style = MaterialTheme.typography.labelSmall)
      }
    }
  }
}
