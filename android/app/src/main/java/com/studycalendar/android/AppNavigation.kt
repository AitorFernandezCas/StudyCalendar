package com.studycalendar.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription

enum class Destination(val label: String) { TASKS("Tareas"), CALENDAR("Calendario"), PROJECTS("Proyectos"), ROUTINES("Rutinas"), SETTINGS("Configuración") }

// Small local vectors keep navigation independent of font glyphs or extra icon libraries.
internal fun appIcon(name: String): ImageVector = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
  path(stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f) {
    when (name) {
      "Tareas" -> { moveTo(3f, 6f); lineTo(5f, 8f); lineTo(8f, 4f); moveTo(11f, 6f); lineTo(21f, 6f); moveTo(3f, 16f); lineTo(5f, 18f); lineTo(8f, 14f); moveTo(11f, 16f); lineTo(21f, 16f) }
      "Calendario" -> { moveTo(4f, 5f); lineTo(20f, 5f); lineTo(20f, 21f); lineTo(4f, 21f); close(); moveTo(4f, 10f); lineTo(20f, 10f); moveTo(8f, 2f); lineTo(8f, 7f); moveTo(16f, 2f); lineTo(16f, 7f) }
      "Proyectos" -> { moveTo(3f, 5f); lineTo(10f, 5f); lineTo(12f, 8f); lineTo(21f, 8f); lineTo(21f, 20f); lineTo(3f, 20f); close() }
      "Rutinas" -> { moveTo(20f, 10f); curveTo(18f, 2f, 6f, 2f, 4f, 10f); moveTo(4f, 5f); lineTo(4f, 10f); lineTo(9f, 10f); moveTo(4f, 14f); curveTo(6f, 22f, 18f, 22f, 20f, 14f); moveTo(20f, 19f); lineTo(20f, 14f); lineTo(15f, 14f) }
      "Atrás" -> { moveTo(15f, 4f); lineTo(7f, 12f); lineTo(15f, 20f) }
      "Siguiente" -> { moveTo(9f, 4f); lineTo(17f, 12f); lineTo(9f, 20f) }
      "Desplegar" -> { moveTo(6f, 9f); lineTo(12f, 15f); lineTo(18f, 9f) }
      "Añadir" -> { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }
      "Agenda" -> { moveTo(3f, 6f); lineTo(5f, 6f); moveTo(8f, 6f); lineTo(21f, 6f); moveTo(3f, 12f); lineTo(5f, 12f); moveTo(8f, 12f); lineTo(21f, 12f); moveTo(3f, 18f); lineTo(5f, 18f); moveTo(8f, 18f); lineTo(21f, 18f) }
      "Horario" -> { moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 20f); lineTo(4f, 20f); close(); moveTo(9f, 4f); lineTo(9f, 20f); moveTo(9f, 10f); lineTo(20f, 10f); moveTo(9f, 15f); lineTo(20f, 15f) }
      "Filtrar" -> { moveTo(3f, 5f); lineTo(21f, 5f); lineTo(14f, 13f); lineTo(14f, 19f); lineTo(10f, 17f); lineTo(10f, 13f); close() }
      "Seleccionado" -> { moveTo(5f, 12f); lineTo(10f, 17f); lineTo(19f, 7f) }
      "Más" -> { moveTo(5f, 11f); lineTo(5f, 13f); moveTo(12f, 11f); lineTo(12f, 13f); moveTo(19f, 11f); lineTo(19f, 13f) }
      else -> { moveTo(4f, 6f); lineTo(20f, 6f); moveTo(4f, 12f); lineTo(20f, 12f); moveTo(4f, 18f); lineTo(20f, 18f); moveTo(8f, 3f); lineTo(8f, 9f); moveTo(16f, 9f); lineTo(16f, 15f); moveTo(10f, 15f); lineTo(10f, 21f) }
    }
  }
}.build()

@Composable
internal fun AppNavigation(selected: Destination, pending: Int?, select: (Destination) -> Unit) {
  NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
    Destination.entries.filter { it != Destination.SETTINGS }.forEach { destination ->
      NavigationBarItem(selected == destination, onClick = { select(destination) }, icon = {
        BadgedBox(badge = { if (destination == Destination.TASKS && pending != 0) Badge(Modifier.semantics { contentDescription = pending?.let { "$it pendientes" } ?: "Cargando pendientes" }) { Text(pending?.toString() ?: "…") } }) {
          Icon(appIcon(destination.label), contentDescription = null)
        }
      }, label = { Text(destination.label, maxLines = 1) })
    }
  }
}
