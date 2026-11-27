package com.studycalendar.android

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.studycalendar.core.*
import java.math.BigDecimal
import java.time.*
import java.util.UUID

@Composable
fun GoalsScreen(model: CalendarViewModel) {
  val cloud by model.cloud.collectAsState()
  val local by model.goalStore.goals.collectAsState(initial = null)
  val localReviews by model.goalStore.reviews.collectAsState(initial = emptyList())
  val localSchedules by model.goalStore.schedules.collectAsState(initial = emptyList())
  val owner = cloud.session?.userId ?: "local"
  val goals = if (owner == "local") local?.map { it.goal() } else cloud.snapshot?.goals
  val reviews = if (owner == "local") localReviews.map { it.review() } else cloud.snapshot?.goalReviews.orEmpty()
  val schedules = if (owner == "local") localSchedules.map { it.schedule() } else cloud.snapshot?.goalSchedules.orEmpty()
  var editing by remember { mutableStateOf<Goal?>(null) }
  var form by rememberSaveable { mutableStateOf(false) }
  var selected by remember { mutableStateOf<Goal?>(null) }
  var deleting by remember { mutableStateOf<Goal?>(null) }
  var settings by rememberSaveable { mutableStateOf(false) }
  var history by rememberSaveable { mutableStateOf(false) }
  var dueOnly by rememberSaveable { mutableStateOf(false) }
  LaunchedEffect(model.goalReviewInbox) {
    if (model.goalReviewInbox) { dueOnly = true; history = false; model.goalReviewInbox = false }
  }
  var now by remember { mutableStateOf(Instant.now()) }
  LaunchedEffect(Unit) { while (true) { now = Instant.now(); kotlinx.coroutines.delay(30_000) } }
  val today = now.atZone(GoalReminders.zone).toLocalDate()
  val due = GoalReminders.due(goals.orEmpty(), reviews, schedules, now).map { it.id }.toSet()
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    if (goals == null) Text(if (cloud.snapshot != null) "Actualiza la cuenta para activar objetivos." else "Cargando objetivos…")
    else {
      Row {
        Button(enabled = !model.busy, onClick = { editing = null; form = true }) { Text("Nuevo objetivo") }
        TextButton(onClick = { settings = true }) { Text("Recordatorios") }
      }
      Row {
        FilterChip(history, { history = !history }, label = { Text("Historial") })
        Spacer(Modifier.width(8.dp))
        FilterChip(dueOnly, { dueOnly = !dueOnly }, label = { Text("Por revisar (${due.size})") })
      }
      val visible = goals.filter { (it.endsOn < today) == history && (!dueOnly || it.id in due) }.sortedWith(compareBy<Goal> { it.period }.thenBy { it.startsOn }.thenBy { it.title })
      if (visible.isEmpty()) Text("No hay objetivos en esta vista.")
      visible.forEach { goal ->
        val latest = reviews.filter { it.goalId == goal.id }.sortedWith(compareByDescending<GoalReview> { it.recordedAt }.thenByDescending { it.id }).firstOrNull { it.amount != null }
        Card(Modifier.fillMaxWidth()) {
          Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(goal.title, style = MaterialTheme.typography.titleMedium)
            Text("${goal.period.label} · ${goal.startsOn} – ${goal.endsOn}")
            if (goal.target != null) Text("${latest?.amount ?: BigDecimal.ZERO} / ${goal.target} ${goal.unit}")
            if (goal.achieved) Text("Conseguido")
            Row {
              TextButton(enabled = !model.busy, onClick = { selected = goal }) { Text("Revisar") }
              TextButton(enabled = !model.busy, onClick = { editing = goal; form = true }) { Text("Editar") }
              TextButton(enabled = !model.busy, onClick = { deleting = goal }) { Text("Eliminar") }
            }
          }
        }
      }
    }
  }
  if (form) GoalForm(editing, model.busy, { form = false }) { value -> model.goalAction({ form = false; if (schedules.isEmpty()) settings = true }) { saveGoal(value, editing) } }
  selected?.let { goal -> GoalReviewDialog(goal, reviews.filter { it.goalId == goal.id }, model.busy,
    { selected = null }, { value -> model.goalAction({ selected = null }) { reviewGoal(value) } },
    { model.goalAction({ selected = null }) { saveGoal(goal.copy(achieved = !goal.achieved), goal) } }) }
  deleting?.let { goal -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Eliminar ${goal.title}") },
    text = { Text("Se eliminarán el objetivo y todas sus revisiones.") }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancelar") } },
    confirmButton = { TextButton(enabled = !model.busy, onClick = { model.goalAction({ deleting = null }) { deleteGoal(goal) } }) { Text("Eliminar") } }) }
  if (settings) GoalSchedulesDialog(owner, schedules, model, { settings = false })
}

@Composable
internal fun GoalForm(original: Goal?, busy: Boolean, dismiss: () -> Unit, save: (Goal) -> Unit) {
  var title by rememberSaveable { mutableStateOf(original?.title ?: "") }
  var period by rememberSaveable { mutableStateOf(original?.period ?: GoalPeriod.WEEK) }
  var date by rememberSaveable { mutableStateOf((original?.startsOn ?: period.start(LocalDate.now(GoalReminders.zone))).toString()) }
  var target by rememberSaveable { mutableStateOf(original?.target?.toPlainString() ?: "") }
  var unit by rememberSaveable { mutableStateOf(original?.unit ?: "") }
  var error by remember { mutableStateOf<String?>(null) }
  val id = rememberSaveable { original?.id ?: UUID.randomUUID().toString() }
  AlertDialog(onDismissRequest = dismiss, title = { Text(if (original == null) "Nuevo objetivo" else "Editar objetivo") }, text = {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(title, { title = it }, label = { Text("Título") })
      GoalPeriod.entries.forEach { value -> FilterChip(period == value, { period = value; date = value.start(LocalDate.now(GoalReminders.zone)).toString() }, label = { Text(value.label) }) }
      OutlinedTextField(date, { date = it }, label = { Text("Fecha del período (AAAA-MM-DD)") })
      Text("Se usará la semana, mes o año que contiene esa fecha.")
      OutlinedTextField(target, { target = it }, label = { Text("Meta numérica (opcional)") })
      OutlinedTextField(unit, { unit = it }, label = { Text("Unidad, por ejemplo libros") })
      error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
  }, dismissButton = { TextButton(onClick = dismiss) { Text("Cancelar") } }, confirmButton = {
    TextButton(enabled = !busy, onClick = {
      try {
        val value = Goal(id, title.trim(), period, period.start(LocalDate.parse(date)), target.takeIf { it.isNotBlank() }?.replace(',', '.')?.let(::BigDecimal), unit, original?.achieved ?: false)
        value.validate(); save(value)
      } catch (_: Exception) { error = "Revisa el título, la fecha y la meta positiva." }
    }) { Text("Guardar") }
  })
}

@Composable
internal fun GoalReviewDialog(goal: Goal, reviews: List<GoalReview>, busy: Boolean, dismiss: () -> Unit, save: (GoalReview) -> Unit, achieve: () -> Unit) {
  var amount by rememberSaveable { mutableStateOf("") }
  var note by rememberSaveable { mutableStateOf("") }
  var error by remember { mutableStateOf<String?>(null) }
  val id = rememberSaveable { UUID.randomUUID().toString() }
  AlertDialog(onDismissRequest = dismiss, title = { Text(goal.title) }, text = {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (goal.target != null) OutlinedTextField(amount, { amount = it }, label = { Text("Total alcanzado (${goal.unit})") })
      OutlinedTextField(note, { note = it }, label = { Text("Notas de la revisión") })
      error?.let { Text(it) }
      TextButton(enabled = !busy, onClick = achieve) { Text(if (goal.achieved) "Reabrir objetivo" else "Marcar conseguido") }
      reviews.sortedWith(compareByDescending<GoalReview> { it.recordedAt }.thenByDescending { it.id }).forEach {
        Text("${it.recordedAt.atZone(GoalReminders.zone).toLocalDateTime()} · ${it.amount ?: "Revisado"}\n${it.note}")
      }
    }
  }, dismissButton = { TextButton(onClick = dismiss) { Text("Cerrar") } }, confirmButton = {
    TextButton(enabled = !busy, onClick = {
      try {
        val review = GoalReview(id, goal.id, Instant.now(), amount.takeIf { it.isNotBlank() }?.replace(',', '.')?.let(::BigDecimal), note)
        review.validate(); save(review)
      } catch (_: Exception) { error = "Introduce un total de cero o mayor." }
    }) { Text("Guardar revisión") }
  })
}

@Composable
private fun GoalSchedulesDialog(owner: String, schedules: List<GoalSchedule>, model: CalendarViewModel, dismiss: () -> Unit) {
  val context = LocalContext.current
  val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { (context.applicationContext as CalendarApplication).refreshGoalNotifications() }
  var type by rememberSaveable { mutableStateOf(GoalPeriod.WEEK) }
  val existing = schedules.find { it.period == type }
  val fallback = GoalSchedule(UUID.nameUUIDFromBytes("$owner/goal/${type.name}".toByteArray()).toString(), type)
  var value by remember(type, existing) { mutableStateOf(existing ?: fallback) }
  var dayMenu by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  AlertDialog(onDismissRequest = dismiss, title = { Text("Recordatorios de revisión") }, text = {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      GoalPeriod.entries.forEach { period -> FilterChip(type == period, { type = period }, label = { Text(period.label) }) }
      Row { Checkbox(value.enabled, { value = value.copy(enabled = it) }); Text("Activar recordatorios") }
      ReviewFrequency.entries.forEach { frequency -> FilterChip(value.frequency == frequency, { value = value.copy(frequency = frequency) }, label = { Text(frequency.label) }) }
      TimeField("Hora en Madrid", value.time, !model.busy) { value = value.copy(time = it) }
      if (value.frequency != ReviewFrequency.DAILY) {
        val weekly = value.frequency == ReviewFrequency.WEEKLY
        val days = if (weekly) listOf("Lunes", "Martes", "Miércoles", "Jueves", "Viernes", "Sábado", "Domingo") else (1..31).map(Int::toString)
        val selected = if (weekly) value.weekday else value.monthDay
        Box {
          OutlinedButton(enabled = !model.busy, modifier = Modifier.fillMaxWidth(), onClick = { dayMenu = true }) {
            Text("${if (weekly) "Día de la semana" else "Día del mes"}: ${days[selected - 1]}")
          }
          DropdownMenu(expanded = dayMenu, onDismissRequest = { dayMenu = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            days.forEachIndexed { index, label ->
              DropdownMenuItem(text = { Text(label) }, onClick = {
                value = if (weekly) value.copy(weekday = index + 1) else value.copy(monthDay = index + 1)
                dayMenu = false
              })
            }
          }
        }
        if (!weekly) Text("Si el mes tiene menos días, se usará su último día.", style = MaterialTheme.typography.bodySmall)
      }
      Text(if (NotificationManagerCompat.from(context).areNotificationsEnabled()) "Los avisos pueden llegar con retraso por los ajustes de batería de Android." else "Las notificaciones están desactivadas en este teléfono.")
      TextButton(onClick = {
        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
      }) { Text("Permitir notificaciones") }
      TextButton(onClick = { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)) }) { Text("Abrir ajustes de notificaciones") }
      error?.let { Text(it) }
    }
  }, dismissButton = { TextButton(onClick = dismiss) { Text("Cerrar") } }, confirmButton = {
    TextButton(enabled = !model.busy, onClick = {
      try {
        val result = value
        result.validate()
        model.goalAction { saveGoalSchedule(result) }
        if (result.enabled && Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
      } catch (_: Exception) { error = "Revisa la hora y el día." }
    }) { Text("Guardar horario") }
  })
}
