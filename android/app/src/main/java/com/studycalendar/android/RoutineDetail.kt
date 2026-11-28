package com.studycalendar.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.studycalendar.core.RoutineDayStatus
import com.studycalendar.core.RoutineHistory
import com.studycalendar.core.streaks
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val spanish = Locale.forLanguageTag("es-ES")
private val habitDateFormat = DateTimeFormatter.ofPattern("EEEE d 'de' MMMM 'de' uuuu", spanish)
private val habitMonthFormat = DateTimeFormatter.ofPattern("MMMM 'de' uuuu", spanish)
private val habitSummaryDateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(spanish)

private enum class HabitCell(val label: String, val symbol: String, val background: Color, val foreground: Color) {
  COMPLETE("Completada", "✓", Color(0xFFD8F3E5), Color(0xFF105536)),
  MISSED("Sin completar", "×", Color(0xFFFAE3DC), Color(0xFF852F24)),
  PAUSED("En pausa", "–", Color(0xFFEBEDF1), Color(0xFF434E63)),
  PENDING("Pendiente hoy", "○", Color(0xFFFFEDC5), Color(0xFF664400)),
}

private fun RoutineDayStatus.cell(): HabitCell = when (this) {
  RoutineDayStatus.COMPLETE -> HabitCell.COMPLETE
  RoutineDayStatus.MISSED -> HabitCell.MISSED
  RoutineDayStatus.PAUSED -> HabitCell.PAUSED
  RoutineDayStatus.PENDING -> HabitCell.PENDING
}

@Composable
internal fun RoutineDetail(record: RoutineRecord, today: LocalDate, sunday: Boolean, busy: Boolean, edit: () -> Unit, complete: () -> Unit) {
  val routine = remember(record.row) { record.row.routine() }
  val completed = remember(record) { record.completedDates() }
  val history = remember(routine, today, completed) { RoutineHistory(routine, today, completed) }
  val streak = remember(routine, today, completed) { streaks(today, routine.active, routine.periods, completed) }
  var browsedMonth by rememberSaveable { mutableStateOf<String?>(null) }
  var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
  val currentMonth = YearMonth.from(today)
  val firstMonth = history.firstDay?.let(YearMonth::from)?.coerceAtMost(currentMonth) ?: currentMonth
  val month = browsedMonth?.let(YearMonth::parse)?.takeIf { it in firstMonth..currentMonth } ?: currentMonth
  val blanks = (month.atDay(1).dayOfWeek.value + if (sunday) 0 else 6) % 7
  val weekdays = listOf("Lun", "Mar", "Mié", "Jue", "Vie", "Sáb", "Dom")
  val labels = if (sunday) listOf(weekdays.last()) + weekdays.dropLast(1) else weekdays
  val selectedDay = selectedDate?.let(LocalDate::parse)?.takeIf { YearMonth.from(it) == month }
  val selectedCell = selectedDay?.let { history.status(it)?.cell() }

  Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(routine.title, Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
      OutlinedButton(onClick = edit, enabled = !busy) { Text("Editar") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      listOf("Racha actual" to streak.current, "Racha máxima" to streak.maximum).forEach { (label, days) ->
        RoutineStatCard(label, "$days " + if (days == 1) "día" else "días", Modifier.weight(1f))
      }
    }
    RoutineStatCard(
      "Último día completado",
      history.lastCompletedDay?.format(habitSummaryDateFormat) ?: "Aún sin completados",
      Modifier.fillMaxWidth(),
    )
    if (routine.due(today)) {
      if (today in completed) {
        OutlinedButton(onClick = complete, enabled = !busy) { Text("Marcar hoy como pendiente") }
      } else {
        Button(onClick = complete, enabled = !busy) { Text("Completar hoy") }
      }
    }
    val firstDay = history.firstDay
    if (firstDay == null) Text("Esta rutina aún no tiene historial.")
    else if (firstDay > today) Text("Empieza el ${firstDay.format(habitDateFormat)}.")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
      OutlinedIconButton(enabled = month > firstMonth, onClick = { browsedMonth = month.minusMonths(1).toString(); selectedDate = null }) {
        Icon(appIcon("Atrás"), "Mes anterior")
      }
      Text(month.format(habitMonthFormat), style = MaterialTheme.typography.titleMedium)
      OutlinedIconButton(enabled = month < currentMonth, onClick = { browsedMonth = month.plusMonths(1).toString(); selectedDate = null }) {
        Icon(appIcon("Siguiente"), "Mes siguiente")
      }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEach { label ->
          Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.labelSmall) }
        }
      }
      repeat((blanks + month.lengthOfMonth() + 6) / 7) { week ->
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          repeat(7) { weekday ->
            val day = week * 7 + weekday - blanks + 1
            val square = Modifier.weight(1f).aspectRatio(1f)
            if (day in 1..month.lengthOfMonth()) {
              val date = month.atDay(day)
              RoutineHistoryCell(date, history.status(date)?.cell(), square, date == today, date == selectedDay) {
                selectedDate = date.toString()
              }
            } else Spacer(square)
          }
        }
      }
    }
    HabitCell.entries.chunked(2).forEach { cells ->
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        cells.forEach { cell ->
          Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(22.dp).background(cell.background, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
              Text(cell.symbol, Modifier.clearAndSetSemantics {}, color = cell.foreground)
            }
            Text(cell.label, style = MaterialTheme.typography.labelMedium)
          }
        }
      }
    }
    if (selectedDay != null && selectedCell != null) Text("${selectedDay.format(habitDateFormat)} · ${selectedCell.label}")
  }
}

@Composable
private fun RoutineStatCard(label: String, value: String, modifier: Modifier) {
  OutlinedCard(modifier) {
    Column(Modifier.padding(12.dp)) {
      Text(label, style = MaterialTheme.typography.labelMedium)
      Text(value, style = MaterialTheme.typography.titleLarge)
    }
  }
}

@Composable
private fun RoutineHistoryCell(date: LocalDate, cell: HabitCell?, modifier: Modifier, today: Boolean, isSelected: Boolean, select: () -> Unit) {
  val shape = RoundedCornerShape(6.dp)
  val foreground = cell?.foreground ?: MaterialTheme.colorScheme.onSurfaceVariant
  val border = when {
    isSelected -> MaterialTheme.colorScheme.primary
    today -> foreground
    else -> MaterialTheme.colorScheme.outlineVariant
  }
  val interaction = if (cell == null) Modifier else Modifier.clickable(onClick = select).semantics {
    selected = isSelected
    contentDescription = date.format(habitDateFormat) + ", " + cell.label
  }
  Row(modifier.background(cell?.background ?: MaterialTheme.colorScheme.surface, shape)
    .border(if (isSelected) 2.dp else 1.dp, border, shape).then(interaction),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally)) {
    val textModifier = if (cell == null) Modifier else Modifier.clearAndSetSemantics {}
    Text(date.dayOfMonth.toString(), textModifier, color = foreground, style = MaterialTheme.typography.labelMedium)
    if (cell != null) Text(cell.symbol, textModifier, color = foreground, style = MaterialTheme.typography.labelSmall)
  }
}
