package com.studycalendar.android

import android.app.DatePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import com.studycalendar.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val spanish = Locale.forLanguageTag("es")
private val calendarZone = ZoneId.of("Europe/Madrid")
private fun LocalDate.label() = format(DateTimeFormatter.ofPattern("EEE d MMM", spanish))
private fun LocalDate.agendaLabel() = format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", spanish))
  .replaceFirstChar { it.titlecase(spanish) }

private fun calendarPeriod(view: String, date: LocalDate, first: LocalDate): String = when (view) {
  "Mes" -> date.format(DateTimeFormatter.ofPattern("MMMM yyyy", spanish)).replaceFirstChar { it.titlecase(spanish) }
  "Semana" -> {
    val last = first.plusDays(6)
    val short = DateTimeFormatter.ofPattern("d MMM", spanish)
    when {
      first.year != last.year -> {
        val withYear = DateTimeFormatter.ofPattern("d MMM yyyy", spanish)
        "${first.format(withYear)} – ${last.format(withYear)}"
      }
      first.month == last.month -> "${first.dayOfMonth}–${last.format(short)}"
      else -> "${first.format(short)} – ${last.format(short)}"
    }
  }
  else -> date.label().replaceFirstChar { it.titlecase(spanish) }
}

private fun LocalDate.shiftCalendarPeriod(view: String, amount: Long) = when (view) {
  "Mes" -> plusMonths(amount)
  "Semana" -> plusWeeks(amount)
  else -> plusDays(amount)
}

private data class CalendarTarget(val date: LocalDate, val bounds: Rect, val visibleBounds: Rect, val first: Int? = null, val last: Int? = null, val allDay: Boolean = false) {
  fun proposal(task: Task, point: Offset, grabMinutes: Float): Task? = CalendarLayout.move(task, date,
    first?.let { (it + (point.y - bounds.top) / bounds.height * (last!! - it) - grabMinutes).toInt() }, allDay)
}

@Composable
internal fun CalendarScreen(tasks: List<Task>, projects: List<Project>, categories: List<Category>, today: LocalDate,
  date: LocalDate, select: (LocalDate) -> Unit, owner: String, settings: CalendarSettings, busy: Boolean,
  saveSettings: (CalendarSettings) -> Unit, scrollOuter: suspend (Float) -> Unit, create: (LocalDate, Int?) -> Unit, edit: (Task) -> Unit,
  complete: (Task) -> Unit, delete: (Task) -> Unit, move: (Task, Task, (Boolean) -> Unit) -> Unit, drag: CalendarDragController, availableHeight: Dp? = null) {
  val context = LocalContext.current
  val prefs = remember(owner) { context.getSharedPreferences("calendar-view-$owner", 0) }
  var view by rememberSaveable(owner) { mutableStateOf(prefs.getString("view", "Semana") ?: "Semana") }
  var agenda by rememberSaveable(owner) { mutableStateOf(prefs.getBoolean("agenda", false)) }
  var category by rememberSaveable(owner) { mutableStateOf(prefs.getString("category", "*") ?: "*") }
  var settingsForm by remember { mutableStateOf(false) }
  var openedDay by remember { mutableStateOf<LocalDate?>(null) }
  var undo by remember(owner) { mutableStateOf<Pair<Task, Task>?>(null) }
  val targets = remember(owner, view, date, settings, agenda) { mutableMapOf<String, CalendarTarget>() }
  val scope = rememberCoroutineScope()
  val currentTasks by rememberUpdatedState(tasks)
  val geometries = remember(owner, view, date, settings, agenda) { mutableMapOf<String, Pair<Task, Rect>>() }
  val session = drag.session
  fun finish(after: Task?) {
    val active = drag.session ?: return
    if (active.phase != DragPhase.DRAGGING) return
    active.release()
    scope.launch {
      var saved = false
      if (after != null && after != active.task) {
        val result = CompletableDeferred<Boolean>()
        move(active.task, after) { result.complete(it) }
        saved = result.await()
        if (saved) undo = active.task to after
      }
      if (drag.session !== active) return@launch
      val expected = if (saved) after!! else active.task
      // Let the local state and final overlap layout arrive before landing.
      repeat(12) {
        if (geometries[expected.id]?.first != expected) withFrameNanos { }
      }
      val bounds = geometries[expected.id]?.takeIf { it.first == expected }?.second
      val exists = currentTasks.any { it == expected }
      drag.land(active, if (exists) bounds ?: (if (saved) active.destination else active.source) else null)
    }
  }
  BackHandler(session?.phase == DragPhase.DRAGGING) { finish(null) }
  DisposableEffect(owner, view, date, settings, agenda, category) {
    onDispose { drag.clear() }
  }
  val horizontal = rememberScrollState()
  val vertical = rememberScrollState()
  var viewport by remember { mutableStateOf(Rect.Zero) }
  var rootViewport by remember { mutableStateOf(Rect.Zero) }
  val hour = 72.dp
  val density = LocalDensity.current
  LaunchedEffect(owner, category, categories) {
    if (category !in listOf("*", "-") && categories.none { it.id == category }) category = "*"
  }
  LaunchedEffect(view, agenda, category) {
    prefs.edit().putString("view", view).putBoolean("agenda", agenda).putString("category", category).apply()
  }
  LaunchedEffect(owner, view, agenda, settings) {
    if (!agenda && view != "Mes") {
      withFrameNanos { }
      withFrameNanos { }
      val now = CalendarLayout.minute(LocalTime.now(calendarZone))
      vertical.scrollTo(with(density) { (hour.toPx() * (now - settings.start - 60).coerceAtLeast(0) / 60).toInt() })
    }
  }
  val edge = with(LocalDensity.current) { 40.dp.toPx() }
  val speed = with(LocalDensity.current) { 480.dp.toPx() }
  fun updateDestination(active: CalendarDragSession) {
    val candidate = targets.values.firstOrNull { it.visibleBounds.contains(active.pointer) }
    val grabFraction = (active.grab.y / active.source.height).coerceIn(0f, 1f)
    val startMinute = active.task.start?.let(CalendarLayout::minute) ?: 0
    val endMinute = active.task.end?.let(CalendarLayout::minute) ?: startMinute + 60
    val visibleStart = maxOf(settings.start, startMinute)
    val visibleEnd = minOf(settings.end, endMinute)
    val grabMinutes = if (active.task.allDay) grabFraction * 60 else if (view == "Mes") grabFraction * (endMinute - startMinute)
      else visibleStart - startMinute + grabFraction * (visibleEnd - visibleStart)
    val proposal = candidate?.proposal(active.task, active.pointer, grabMinutes)
    active.preview = proposal
    active.destination = if (candidate == null || proposal == null) null else if (candidate.first == null) candidate.visibleBounds else {
      val start = CalendarLayout.minute(proposal.start!!)
      val end = CalendarLayout.minute(proposal.end!!)
      val unit = candidate.bounds.height / (candidate.last!! - candidate.first)
      Rect(candidate.bounds.left, candidate.bounds.top + (start - candidate.first) * unit,
        candidate.bounds.right, candidate.bounds.top + (end - candidate.first) * unit).intersect(candidate.visibleBounds)
    }
  }
  LaunchedEffect(session) {
    val active = session ?: return@LaunchedEffect
    var lastFrame = withFrameNanos { it }
    fun velocity(position: Float, first: Float, last: Float): Float {
      if (last <= first || position < first - edge || position > last + edge) return 0f
      return when {
        position < first + edge -> -speed * ((first + edge - position) / edge).coerceIn(0f, 1f)
        position > last - edge -> speed * ((position - last + edge) / edge).coerceIn(0f, 1f)
        else -> 0f
      }
    }
    while (drag.session === active && active.phase == DragPhase.DRAGGING) {
      val frame = withFrameNanos { it }
      if (active.phase != DragPhase.DRAGGING) break
      val elapsed = ((frame - lastFrame) / 1_000_000_000f).coerceAtMost(.05f)
      lastFrame = frame
      val pointer = active.pointer
      if (view == "Mes") scrollOuter(velocity(pointer.y, rootViewport.top, rootViewport.bottom) * elapsed)
      else {
        horizontal.scrollBy(velocity(pointer.x, viewport.left + edge, viewport.right) * elapsed)
        vertical.scrollBy(velocity(pointer.y, viewport.top, viewport.bottom) * elapsed)
      }
      updateDestination(active)
    }
  }
  val filtered = tasks.filter { it.date != null && (category == "*" || if (category == "-") it.categoryId == null else it.categoryId == category) }
  val tasksByDate = filtered.groupBy(Task::date)
  val first = if (view == "Semana") CalendarLayout.week(date, settings.sunday) else date
  val dates = if (view == "Mes") CalendarLayout.month(date, settings.sunday) else (0L until if (view == "Semana") 7L else 1L).map(first::plusDays)
  fun target(key: String, day: LocalDate, start: Int? = null, end: Int? = null, allDay: Boolean = false) = Modifier.onGloballyPositioned {
    targets[key] = CalendarTarget(day, Rect(it.positionInWindow(), Size(it.size.width.toFloat(), it.size.height.toFloat())), it.boundsInWindow(), start, end, allDay)
  }
  @Composable fun chip(task: Task, modifier: Modifier = Modifier, compact: Boolean = false) {
    val coordinates = remember(task.id) { arrayOfNulls<LayoutCoordinates>(1) }
    val color = projects.find { it.id == task.projectId }?.color ?: categories.find { it.id == task.categoryId }?.color
    val accent by rememberUpdatedState(color?.let { Color(android.graphics.Color.parseColor(it)) } ?: MaterialTheme.colorScheme.primary)
    val currentTask by rememberUpdatedState(task)
    CalendarTaskBlock(task, accent, compact, modifier.onGloballyPositioned {
      coordinates[0] = it
      geometries[task.id] = task to Rect(it.positionInWindow(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
    }.graphicsLayer {
      val active = drag.session
      alpha = if (active?.task?.id != task.id) 1f else if (active.phase == DragPhase.DRAGGING) .2f else 0f
    }.testTag("calendar-task-${task.id}")
      .semantics {
        contentDescription = "${task.title}, ${task.date}, ${if (task.allDay) "todo el día" else "${task.start} a ${task.end}"}. Mantén pulsado para mover."
        stateDescription = if (task.completed) "Completada" else "Pendiente"
      }
      .pointerInput(task.id, busy, targets) {
        if (!busy) detectDragGesturesAfterLongPress(onDragStart = { offset ->
          val geometry = geometries[currentTask.id]
          if (geometry != null) drag.begin(currentTask, geometry.second, offset, accent, compact)?.let(::updateDestination)
        }, onDragCancel = { finish(null) }, onDragEnd = {
          val active = drag.session
          if (active != null && active.phase == DragPhase.DRAGGING) {
            updateDestination(active)
            finish(active.preview)
          }
        }, onDrag = { change, _ ->
          change.consume()
          val active = drag.session
          val layout = coordinates[0]
          if (active != null && active.phase == DragPhase.DRAGGING && layout?.isAttached == true) {
            active.pointer = layout.localToWindow(change.position)
            updateDestination(active)
          }
        })
      }.clickable(role = Role.Button, onClickLabel = "Editar tarea") { edit(task) })
  }
  @Composable fun dayAgenda(day: LocalDate) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CalendarDateNumber(day.dayOfMonth, day == today, day == date)
        Text(day.agendaLabel(), Modifier.weight(1f).semantics { heading() },
          color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
        IconButton(enabled = !busy, onClick = { create(day, null) },
          colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.primary)) {
          Icon(appIcon("Añadir"), "Nueva tarea para el ${day.agendaLabel().lowercase(spanish)}")
        }
      }
      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
      val daily = tasksByDate[day].orEmpty().sortedWith(compareByDescending<Task> { it.allDay }.thenBy { it.start }.thenBy { it.title })
      if (daily.isEmpty()) Text("No tienes tareas para este día.", color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodyMedium)
      daily.forEach { task -> TaskCard(task, projects.find { it.id == task.projectId }, categories.find { it.id == task.categoryId }, busy,
        complete = { complete(task) }, edit = { edit(task) }, delete = { delete(task) }, today = today, allTasks = tasks) }
    }
  }
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val calendarWidth = maxWidth
    val colors = MaterialTheme.colorScheme
    val gridLine = colors.outlineVariant
    val gridHeight = ((availableHeight ?: LocalConfiguration.current.screenHeightDp.dp) - 156.dp).coerceAtLeast(240.dp)
    Column(Modifier.onGloballyPositioned { rootViewport = it.boundsInWindow() }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
      CalendarToolbar(
        title = calendarPeriod(view, date, first), view = view, selectView = { view = it },
        agenda = agenda, toggleAgenda = { agenda = !agenda }, category = category, categories = categories,
        selectCategory = { category = it }, pickDate = {
          DatePickerDialog(context, { _, year, month, day -> select(LocalDate.of(year, month + 1, day)) },
            date.year, date.monthValue - 1, date.dayOfMonth).show()
        }, previous = { select(date.shiftCalendarPeriod(view, -1)) }, today = { select(today) },
        next = { select(date.shiftCalendarPeriod(view, 1)) }, openSettings = { settingsForm = true }
      )
      undo?.let { (before, after) ->
        Surface(Modifier.fillMaxWidth().padding(horizontal = 12.dp), shape = RoundedCornerShape(12.dp), color = colors.primaryContainer) {
          Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Horario guardado", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(enabled = !busy, onClick = { move(after, before) { success -> if (success) undo = null } }) { Text("Deshacer") }
            TextButton(onClick = { undo = null }) { Text("Cerrar") }
          }
        }
      }

      if (view == "Mes") {
        val chipHeight = with(density) { (20.sp.toDp() + 4.dp).coerceAtLeast(24.dp) }
        val dateHeight = with(density) { (20.sp.toDp() + 8.dp).coerceAtLeast(28.dp) }
        val overflowHeight = with(density) { (16.sp.toDp() + 2.dp).coerceAtLeast(18.dp) }
        val weekday = DateTimeFormatter.ofPattern("EE", spanish)
        Column(Modifier.fillMaxWidth().background(colors.surface)) {
          Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            dates.take(7).forEach {
              Text(it.format(weekday), Modifier.weight(1f), color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, maxLines = 1)
            }
          }
          dates.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth()) {
              week.forEachIndexed { index, day ->
                val daily = tasksByDate[day].orEmpty()
                val overflow = (daily.size - 2).coerceAtLeast(0)
                Column(Modifier.testTag("calendar-month-$day").weight(1f).height(dateHeight + chipHeight * 2 + overflowHeight + 10.dp)
                  .then(target("month-$day", day)).drawBehind {
                    drawLine(gridLine, Offset.Zero, Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
                    if (index != 0) drawLine(gridLine, Offset.Zero, Offset(0f, size.height), strokeWidth = 1.dp.toPx())
                  }.clickable(role = Role.Button, onClickLabel = "Abrir agenda") { openedDay = day }
                  .semantics {
                    contentDescription = day.agendaLabel()
                    if (day == today) stateDescription = "Hoy"
                  }.padding(horizontal = 2.dp, vertical = 3.dp),
                  horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(1.dp)) {
                  Box(Modifier.height(dateHeight), contentAlignment = Alignment.Center) {
                    CalendarDateNumber(day.dayOfMonth, day == today, day == date, muted = day.month != date.month)
                  }
                  daily.take(2).forEach { chip(it, Modifier.fillMaxWidth().height(chipHeight), compact = true) }
                  if (overflow > 0) {
                    Text("+$overflow", color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall,
                      maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                        .clickable(role = Role.Button, onClickLabel = "Abrir agenda") { openedDay = day }
                        .semantics { contentDescription = if (overflow == 1) "Una tarea más" else "$overflow tareas más" })
                  }
                }
              }
            }
          }
          HorizontalDivider(color = gridLine)
        }
      } else if (agenda) {
        dates.forEach { dayAgenda(it) }
      } else {
        val outside = filtered.count { it.date in dates && !CalendarLayout.visible(it, settings) }
        if (outside > 0) TextButton(onClick = { agenda = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
          val count = if (outside == 1) "1 tarea" else "$outside tareas"
          Text("$count fuera del horario · Ver agenda")
        }
        val gutter = with(density) { (36.sp.toDp() + 12.dp).coerceAtLeast(48.dp) }
        val columnWidth = if (view == "Día") (calendarWidth - gutter).coerceAtLeast(180.dp)
          else ((calendarWidth - gutter) / 7).coerceAtLeast(180.dp)
        val hours = settings.start until settings.end step 60
        val slots = settings.start until settings.end step 15
        val allDayCount = dates.maxOf { day -> tasksByDate[day].orEmpty().count(Task::allDay) }
        val allDayChipHeight = with(density) { (24.sp.toDp() + 4.dp).coerceAtLeast(28.dp) }
        val allDayHeight = (48.dp + allDayChipHeight * allDayCount).coerceAtMost(140.dp)
        var minuteNow by remember { mutableStateOf(CalendarLayout.minute(LocalTime.now(calendarZone))) }
        LaunchedEffect(today) {
          while (true) {
            minuteNow = CalendarLayout.minute(LocalTime.now(calendarZone))
            delay(30_000)
          }
        }
        Column(Modifier.fillMaxWidth().height(gridHeight).background(colors.surface)
          .onGloballyPositioned { viewport = it.boundsInWindow() }) {
          Row {
            Spacer(Modifier.width(gutter))
            Row(Modifier.horizontalScroll(horizontal)) {
              dates.forEach { day ->
                Column(Modifier.width(columnWidth).drawBehind {
                  drawLine(gridLine, Offset.Zero, Offset(0f, size.height), strokeWidth = 1.dp.toPx())
                }) {
                  Column(Modifier.fillMaxWidth()
                    .clickable(role = Role.Button, onClickLabel = "Abrir agenda") { openedDay = day }
                    .semantics {
                      contentDescription = day.agendaLabel()
                      if (day == today) stateDescription = "Hoy"
                    }.padding(vertical = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(day.format(DateTimeFormatter.ofPattern("EEE", spanish)), color = colors.onSurfaceVariant,
                      style = MaterialTheme.typography.labelSmall)
                    CalendarDateNumber(day.dayOfMonth, day == today, day == date)
                  }
                  Column(Modifier.testTag("calendar-all-$day").fillMaxWidth().height(allDayHeight)
                    .then(target("all-$day", day, allDay = true)).verticalScroll(rememberScrollState())) {
                    TextButton(enabled = !busy, onClick = { create(day, null) },
                      modifier = Modifier.semantics { contentDescription = "Nueva tarea de todo el día, ${day.label()}" }) {
                      Icon(appIcon("Añadir"), null, Modifier.size(16.dp))
                      Spacer(Modifier.width(4.dp))
                      Text("Todo el día")
                    }
                    tasksByDate[day].orEmpty().filter(Task::allDay).forEach {
                      chip(it, Modifier.fillMaxWidth().padding(horizontal = 3.dp, vertical = 2.dp))
                    }
                  }
                }
              }
            }
          }
          HorizontalDivider(color = gridLine)
          Row(Modifier.weight(1f).verticalScroll(vertical)) {
            Column(Modifier.width(gutter)) {
              hours.forEach { minute ->
                Text(CalendarLayout.time(minute), Modifier.fillMaxWidth().height(hour * (minOf(60, settings.end - minute) / 60f))
                  .padding(horizontal = 4.dp), color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall,
                  textAlign = TextAlign.End, maxLines = 1)
              }
            }
            Row(Modifier.horizontalScroll(horizontal)) {
              dates.forEach { day ->
                Box(Modifier.testTag("calendar-grid-$day").width(columnWidth).height(hour * ((settings.end - settings.start) / 60f))
                  .then(target("grid-$day", day, settings.start, settings.end)).drawBehind {
                    drawLine(gridLine, Offset.Zero, Offset(0f, size.height), strokeWidth = 1.dp.toPx())
                    for (minute in settings.start + 60 until settings.end step 60) {
                      val y = hour.toPx() * (minute - settings.start) / 60f
                      drawLine(gridLine, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                    }
                  }) {
                  slots.forEach { minute ->
                    Box(Modifier.offset(y = hour * ((minute - settings.start) / 60f)).fillMaxWidth()
                      .height(hour * (minOf(15, settings.end - minute) / 60f))
                      .semantics { contentDescription = "Nueva tarea, ${day.label()}, ${CalendarLayout.time(minute)}" }
                      .clickable(enabled = !busy && minute < 1439, role = Role.Button) { create(day, minute) })
                  }
                  CalendarLayout.lanes(tasksByDate[day].orEmpty().filter { CalendarLayout.visible(it, settings) }).forEach { lane ->
                    val start = maxOf(settings.start, CalendarLayout.minute(lane.task.start!!))
                    val end = minOf(settings.end, CalendarLayout.minute(lane.task.end!!))
                    val laneWidth = columnWidth / lane.columns
                    chip(lane.task, Modifier.offset(x = laneWidth * lane.column, y = hour * ((start - settings.start) / 60f))
                      .width(laneWidth).height((hour * ((end - start) / 60f)).coerceAtLeast(20.dp)).padding(horizontal = 2.dp))
                  }
                  if (day == today && minuteNow in settings.start until settings.end) {
                    Canvas(Modifier.offset(y = hour * ((minuteNow - settings.start) / 60f) - 4.dp).fillMaxWidth().height(8.dp)) {
                      val rose = Color(0xFFB53248)
                      drawLine(rose, Offset(4.dp.toPx(), center.y), Offset(size.width, center.y), strokeWidth = 1.dp.toPx())
                      drawCircle(rose, radius = 3.dp.toPx(), center = Offset(4.dp.toPx(), center.y))
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
  openedDay?.let { day ->
    Dialog(onDismissRequest = { openedDay = null }) {
      Surface(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
          TextButton(onClick = { openedDay = null }, modifier = Modifier.align(Alignment.End).padding(horizontal = 8.dp)) {
            Text("Cerrar agenda")
          }
          dayAgenda(day)
        }
      }
    }
  }
  if (settingsForm) CalendarSettingsDialog(settings, busy, { settingsForm = false }) { saveSettings(it); settingsForm = false }
}

@Composable
internal fun CalendarSettingsDialog(settings: CalendarSettings, busy: Boolean, dismiss: () -> Unit, save: (CalendarSettings) -> Unit) {
  var start by remember { mutableStateOf(CalendarLayout.time(settings.start)) }
  var end by remember { mutableStateOf(CalendarLayout.time(settings.end)) }
  var sunday by remember { mutableStateOf(settings.sunday) }
  var error by remember { mutableStateOf<String?>(null) }
  AlertDialog(onDismissRequest = { if (!busy) dismiss() }, title = { Text("Ajustes del calendario") }, text = {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(start, { start = it }, enabled = !busy, label = { Text("Inicio · HH:MM") }, singleLine = true)
      OutlinedTextField(end, { end = it }, enabled = !busy, label = { Text("Fin · HH:MM (hasta 24:00)") }, singleLine = true)
      TextButton(enabled = !busy, onClick = { sunday = !sunday }) { Text("La semana empieza el ${if (sunday) "domingo" else "lunes"}") }
      error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
  }, confirmButton = { TextButton(enabled = !busy, onClick = {
    try { save(CalendarSettings(CalendarLayout.parse(start), CalendarLayout.parse(end), sunday)) }
    catch (failure: IllegalArgumentException) { error = failure.message }
  }) { Text("Guardar") } }, dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("Cancelar") } })
}
