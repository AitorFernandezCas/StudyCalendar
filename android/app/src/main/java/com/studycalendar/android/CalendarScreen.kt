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
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
private fun LocalDate.label() = format(DateTimeFormatter.ofPattern("EEE d MMM", spanish))
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
  var categoryMenu by remember { mutableStateOf(false) }
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
      val now = CalendarLayout.minute(LocalTime.now(ZoneId.of("Europe/Madrid")))
      vertical.scrollTo(((now - settings.start - 60).coerceAtLeast(0) * 72 * context.resources.displayMetrics.density / 60).toInt())
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
  val first = if (view == "Semana") CalendarLayout.week(date, settings.sunday) else date
  val dates = if (view == "Mes") CalendarLayout.month(date, settings.sunday) else (0L until if (view == "Semana") 7L else 1L).map(first::plusDays)
  fun target(key: String, day: LocalDate, start: Int? = null, end: Int? = null, allDay: Boolean = false) = Modifier.onGloballyPositioned {
    targets[key] = CalendarTarget(day, Rect(it.positionInWindow(), Size(it.size.width.toFloat(), it.size.height.toFloat())), it.boundsInWindow(), start, end, allDay)
  }
  @Composable fun chip(task: Task, modifier: Modifier = Modifier) {
    val coordinates = remember(task.id) { arrayOfNulls<LayoutCoordinates>(1) }
    val color = projects.find { it.id == task.projectId }?.color ?: categories.find { it.id == task.categoryId }?.color ?: "#7056ED"
    val currentTask by rememberUpdatedState(task)
    Surface(modifier.onGloballyPositioned {
      coordinates[0] = it
      geometries[task.id] = task to Rect(it.positionInWindow(), Size(it.size.width.toFloat(), it.size.height.toFloat()))
    }.graphicsLayer {
      val active = drag.session
      alpha = if (active?.task?.id != task.id) 1f else if (active.phase == DragPhase.DRAGGING) .2f else 0f
    }.testTag("calendar-task-${task.id}")
      .semantics { contentDescription = "${task.title}, ${task.date}, ${if (task.allDay) "todo el día" else "${task.start} a ${task.end}"}. Toca para editar; mantén pulsado para mover." }
      .pointerInput(task.id, busy, targets) {
        if (!busy) detectDragGesturesAfterLongPress(onDragStart = { offset ->
          val geometry = geometries[currentTask.id]
          if (geometry != null) drag.begin(currentTask, geometry.second, offset, Color(android.graphics.Color.parseColor(color)))?.let(::updateDestination)
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
      }.clickable { edit(task) }, shape = RoundedCornerShape(6.dp), color = Color(android.graphics.Color.parseColor(color)).copy(alpha = if (task.completed) .10f else .20f),
      border = BorderStroke(1.dp, Color(android.graphics.Color.parseColor(color)))) {
      Text((if (task.completed) "✓ " else "") + task.title, Modifier.padding(5.dp), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
    }
  }
  @Composable fun dayAgenda(day: LocalDate) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(day.label(), style = MaterialTheme.typography.titleMedium)
      TextButton(enabled = !busy, onClick = { create(day, null) }) { Text("Nueva tarea · ${day.dayOfMonth}") }
      val daily = filtered.filter { it.date == day }.sortedWith(compareByDescending<Task> { it.allDay }.thenBy { it.start }.thenBy { it.title })
      if (daily.isEmpty()) Text("No tienes tareas para este día.")
      daily.forEach { task -> TaskCard(task, projects.find { it.id == task.projectId }, categories.find { it.id == task.categoryId }, busy,
        complete = { complete(task) }, edit = { edit(task) }, delete = { delete(task) }, today = today, allTasks = tasks) }
    }
  }
  BoxWithConstraints {
  val dayWidth = (maxWidth - 48.dp).coerceAtLeast(180.dp)
  val weekWidth = ((maxWidth - 48.dp) / 7).coerceAtLeast(180.dp)
  val gridHeight = ((availableHeight ?: LocalConfiguration.current.screenHeightDp.dp) - 180.dp).coerceAtLeast(240.dp)
  Column(Modifier.onGloballyPositioned { rootViewport = it.boundsInWindow() }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      listOf("Día", "Semana", "Mes").forEach { value -> FilterChip(view == value, { view = value }, label = { Text(value) }) }
      IconButton(onClick = { settingsForm = true }) { Icon(appIcon("Configuración"), "Ajustes del calendario") }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
      IconButton(onClick = { select(when(view) { "Mes" -> date.minusMonths(1); "Semana" -> date.minusWeeks(1); else -> date.minusDays(1) }) }) { Icon(appIcon("Atrás"), "Anterior") }
      TextButton(modifier = Modifier.weight(1f), onClick = { DatePickerDialog(context, { _, year, month, day -> select(LocalDate.of(year, month + 1, day)) }, date.year, date.monthValue - 1, date.dayOfMonth).show() }) {
        Text(if (view == "Mes") date.format(DateTimeFormatter.ofPattern("MMMM yyyy", spanish)) else date.label(), maxLines = 2)
      }
      TextButton(onClick = { select(today) }) { Text("Hoy") }
      IconButton(onClick = { select(when(view) { "Mes" -> date.plusMonths(1); "Semana" -> date.plusWeeks(1); else -> date.plusDays(1) }) }) { Icon(appIcon("Siguiente"), "Siguiente") }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (view != "Mes") TextButton(onClick = { agenda = !agenda }) { Text(if (agenda) "Mostrar horario" else "Mostrar agenda") }
      Box {
        TextButton(onClick = { categoryMenu = true }) { Text(if (category == "*") "Todas las categorías" else if (category == "-") "Sin categoría" else categories.find { it.id == category }?.name ?: "Todas") }
        DropdownMenu(categoryMenu, { categoryMenu = false }) {
          (listOf("*" to "Todas las categorías", "-" to "Sin categoría") + categories.map { it.id to it.name }).forEach { (id, name) ->
            DropdownMenuItem(text = { Text(name) }, onClick = { category = id; categoryMenu = false })
          }
        }
      }
    }
    undo?.let { (before, after) ->
      Row { Text("Horario guardado", Modifier.weight(1f)); TextButton(enabled = !busy, onClick = { move(after, before) { success -> if (success) undo = null } }) { Text("Deshacer") }; TextButton(onClick = { undo = null }) { Text("Cerrar") } }
    }

    if (view == "Mes") {
      Row { dates.take(7).forEach { Text(it.format(DateTimeFormatter.ofPattern("EE", spanish)), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall) } }
      dates.chunked(7).forEach { week ->
        Row {
          week.forEach { day ->
            Column(Modifier.testTag("calendar-month-$day").weight(1f).height(116.dp).then(target("month-$day", day)).border(1.dp, MaterialTheme.colorScheme.outline).clickable { openedDay = day }.padding(2.dp)) {
              Text(day.dayOfMonth.toString(), color = if (day == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium)
              val daily = filtered.filter { it.date == day }
              daily.take(2).forEach { chip(it, Modifier.fillMaxWidth().height(32.dp)) }
              if (daily.size > 2) Text("+${daily.size - 2}", style = MaterialTheme.typography.labelSmall)
            }
          }
        }
      }
    } else if (agenda) dates.forEach { dayAgenda(it) }
    else {
      val outside = filtered.filter { it.date in dates && !CalendarLayout.visible(it, settings) }
      if (outside.isNotEmpty()) TextButton(onClick = { agenda = true }) { Text("${outside.size} tareas fuera del horario · Ver agenda") }
      val columnWidth = if (view == "Día") dayWidth else weekWidth
      val allDayHeight = (48 + (dates.maxOfOrNull { day -> filtered.count { it.date == day && it.allDay } } ?: 0) * 44).coerceAtMost(140).dp
      Column(Modifier.height(gridHeight).onGloballyPositioned { viewport = it.boundsInWindow() }.border(1.dp, MaterialTheme.colorScheme.outline)) {
        Row {
          Spacer(Modifier.width(48.dp))
          Row(Modifier.horizontalScroll(horizontal)) {
          dates.forEach { day ->
            Column(Modifier.width(columnWidth)) {
              TextButton(onClick = { openedDay = day }) { Text(day.label(), color = if (day == today) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) }
              Column(Modifier.testTag("calendar-all-$day").fillMaxWidth().height(allDayHeight).then(target("all-$day", day, allDay = true)).verticalScroll(rememberScrollState())) {
                TextButton(enabled = !busy, onClick = { create(day, null) }) { Text("Todo el día +") }
                filtered.filter { it.date == day && it.allDay }.forEach { chip(it, Modifier.fillMaxWidth().padding(2.dp)) }
              }
            }
          }
        }
        }
        HorizontalDivider()
        Row(Modifier.weight(1f).verticalScroll(vertical)) {
          Column(Modifier.width(48.dp)) {
            var minute = settings.start
            while (minute < settings.end) {
              val step = minOf(60, settings.end - minute)
              Text(CalendarLayout.time(minute), Modifier.height(hour * (step / 60f)), style = MaterialTheme.typography.labelSmall)
              minute += step
            }
          }
          Row(Modifier.horizontalScroll(horizontal)) {
            dates.forEach { day ->
              Box(Modifier.testTag("calendar-grid-$day").width(columnWidth).height(hour * ((settings.end - settings.start) / 60f)).then(target("grid-$day", day, settings.start, settings.end))) {
                var minute = settings.start
                while (minute < settings.end) {
                  val at = minute
                  Box(Modifier.offset(y = hour * ((at - settings.start) / 60f)).fillMaxWidth().height(hour * (minOf(15, settings.end - at) / 60f)).border(.3.dp, MaterialTheme.colorScheme.outline).semantics { contentDescription = "Nueva tarea, ${day.label()}, ${CalendarLayout.time(at)}" }.clickable(enabled = !busy && at < 1439) { create(day, at) })
                  minute += 15
                }
                CalendarLayout.lanes(filtered.filter { it.date == day && CalendarLayout.visible(it, settings) }).forEach { lane ->
                  val start = maxOf(settings.start, CalendarLayout.minute(lane.task.start!!))
                  val end = minOf(settings.end, CalendarLayout.minute(lane.task.end!!))
                  chip(lane.task, Modifier.offset(x = columnWidth * (lane.column.toFloat() / lane.columns), y = hour * ((start - settings.start) / 60f)).width(columnWidth / lane.columns).height((hour * ((end - start) / 60f)).coerceAtLeast(20.dp)))
                }
                var now by remember { mutableStateOf(LocalTime.now(ZoneId.of("Europe/Madrid"))) }
                LaunchedEffect(day) { while (true) { now = LocalTime.now(ZoneId.of("Europe/Madrid")); delay(30_000) } }
                val minuteNow = CalendarLayout.minute(now)
                if (day == today && minuteNow in settings.start until settings.end) HorizontalDivider(Modifier.offset(y = hour * ((minuteNow - settings.start) / 60f)), thickness = 2.dp, color = MaterialTheme.colorScheme.primary)
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
      Surface(shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.heightIn(max = 650.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
          TextButton(onClick = { openedDay = null }) { Text("Cerrar agenda") }
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
