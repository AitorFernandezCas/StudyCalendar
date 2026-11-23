package com.studycalendar.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.studycalendar.core.Category
import com.studycalendar.core.Task

@Composable
internal fun CalendarToolbar(
  title: String,
  view: String,
  selectView: (String) -> Unit,
  agenda: Boolean,
  toggleAgenda: () -> Unit,
  category: String,
  categories: List<Category>,
  selectCategory: (String) -> Unit,
  pickDate: () -> Unit,
  previous: () -> Unit,
  today: () -> Unit,
  next: () -> Unit,
  openSettings: () -> Unit
) {
  val colors = MaterialTheme.colorScheme
  Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Row(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button, onClick = pickDate)
        .semantics { contentDescription = "Elegir fecha, $title" }.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f, fill = false), color = colors.onSurface,
          style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Icon(appIcon("Desplegar"), null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
      }
      IconButton(onClick = previous) { Icon(appIcon("Atrás"), "Anterior") }
      TextButton(onClick = today, colors = ButtonDefaults.textButtonColors(containerColor = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer), contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Hoy") }
      IconButton(onClick = next) { Icon(appIcon("Siguiente"), "Siguiente") }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
      Row(Modifier.clip(CircleShape).border(1.dp, colors.outlineVariant, CircleShape).selectableGroup(),
        verticalAlignment = Alignment.CenterVertically) {
        listOf("Día", "Semana", "Mes").forEachIndexed { index, value ->
          val selected = view == value
          if (index > 0) Spacer(Modifier.width(1.dp).height(24.dp).background(colors.outlineVariant))
          Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .background(if (selected) colors.primaryContainer else Color.Transparent)
            .selectable(selected = selected, onClick = { selectView(value) }, role = Role.RadioButton)
            .padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
            Text(value, style = MaterialTheme.typography.labelLarge, maxLines = 1,
              color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant)
          }
        }
      }
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (view != "Mes") {
          IconButton(onClick = toggleAgenda, colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (agenda) colors.primaryContainer else Color.Transparent,
            contentColor = if (agenda) colors.onPrimaryContainer else colors.onSurfaceVariant)) {
            Icon(appIcon(if (agenda) "Horario" else "Agenda"), if (agenda) "Mostrar horario" else "Mostrar agenda")
          }
        }
        CalendarCategoryFilter(category, categories, selectCategory)
        IconButton(onClick = openSettings) { Icon(appIcon("Configuración"), "Ajustes del calendario", tint = colors.onSurfaceVariant) }
      }
    }
  }
}

@Composable
private fun CalendarCategoryFilter(category: String, categories: List<Category>, selectCategory: (String) -> Unit) {
  val options = listOf("*" to "Todas las categorías", "-" to "Sin categoría") + categories.map { it.id to it.name }
  val categoryLabel = options.firstOrNull { it.first == category }?.second ?: options.first().second
  val filtered = category != "*"
  val colors = MaterialTheme.colorScheme
  var expanded by remember { mutableStateOf(false) }
  Box {
    IconButton(onClick = { expanded = true }, colors = IconButtonDefaults.iconButtonColors(
      containerColor = if (filtered) colors.primaryContainer else Color.Transparent,
      contentColor = if (filtered) colors.onPrimaryContainer else colors.onSurfaceVariant)) {
      Icon(appIcon("Filtrar"), "Filtrar por categoría: $categoryLabel")
    }
    DropdownMenu(expanded, { expanded = false }) {
      options.forEach { (id, name) ->
        DropdownMenuItem(text = { Text(name) }, leadingIcon = {
          if (id == category) Icon(appIcon("Seleccionado"), "Seleccionada") else Spacer(Modifier.size(24.dp))
        }, onClick = {
          selectCategory(id)
          expanded = false
        })
      }
    }
  }
}

@Composable
internal fun CalendarDateNumber(number: Int, today: Boolean, selected: Boolean, muted: Boolean = false) {
  val colors = MaterialTheme.colorScheme
  val background = when {
    today -> colors.onPrimaryContainer
    selected -> colors.primaryContainer
    else -> Color.Transparent
  }
  val text = when {
    today -> Color.White
    selected -> colors.onPrimaryContainer
    muted -> colors.onSurfaceVariant
    else -> colors.onSurface
  }
  Box(Modifier.sizeIn(minWidth = 28.dp, minHeight = 28.dp).background(background, CircleShape)
    .padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
    Text(number.toString(), color = text, style = MaterialTheme.typography.titleSmall,
      fontWeight = if (today || selected) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
  }
}

private val calendarTaskShape = RoundedCornerShape(6.dp)

@Composable
internal fun CalendarTaskBlock(task: Task, accent: Color, compact: Boolean, modifier: Modifier = Modifier) {
  val colors = MaterialTheme.colorScheme
  Surface(modifier, shape = calendarTaskShape,
    color = lerp(colors.surface, accent, if (task.completed) .10f else .24f), contentColor = colors.onSurface) {
    Box(Modifier.drawBehind { drawRect(lerp(colors.onSurface, accent, .72f), size = Size(3.dp.toPx(), size.height)) }) {
      Text((if (task.completed) "✓ " else "") + task.title,
        Modifier.padding(start = 6.dp, end = 3.dp, top = 2.dp, bottom = 2.dp),
        maxLines = if (compact) 1 else 3, overflow = TextOverflow.Ellipsis,
        style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium)
    }
  }
}
