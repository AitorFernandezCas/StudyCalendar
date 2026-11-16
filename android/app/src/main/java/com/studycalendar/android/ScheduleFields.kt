package com.studycalendar.android

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun DateField(label: String, date: LocalDate, enabled: Boolean, change: (LocalDate) -> Unit) {
  val context = LocalContext.current
  OutlinedButton(enabled = enabled, modifier = Modifier.fillMaxWidth(), onClick = {
    DatePickerDialog(context, { _, year, month, day -> change(LocalDate.of(year, month + 1, day)) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
  }, contentPadding = PaddingValues(16.dp)) {
    Column(Modifier.fillMaxWidth()) {
      Text(label, style = MaterialTheme.typography.labelMedium)
      Text(date.format(DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", Locale.forLanguageTag("es-ES"))), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
    }
  }
}

@Composable
internal fun TimeField(label: String, value: LocalTime, enabled: Boolean, change: (LocalTime) -> Unit) {
  val context = LocalContext.current
  OutlinedButton(enabled = enabled, modifier = Modifier.fillMaxWidth(), onClick = {
    TimePickerDialog(context, { _, hour, minute -> change(LocalTime.of(hour, minute)) }, value.hour, value.minute, true).show()
  }, contentPadding = PaddingValues(16.dp)) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Text(label)
      Text(value.format(DateTimeFormatter.ofPattern("HH:mm")), color = MaterialTheme.colorScheme.onSurface)
    }
  }
}
