package com.studycalendar.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject

private val fieldLabels = mapOf("title" to "Título", "name" to "Nombre", "date" to "Fecha", "start_time" to "Inicio", "end_time" to "Fin", "all_day" to "Todo el día", "completed" to "Completada", "active" to "Activa", "status" to "Estado", "color" to "Color", "project_id" to "Proyecto", "category_id" to "Categoría", "prerequisite_ids" to "Tareas previas", "delete" to "Eliminación", "calendar_start_time" to "Inicio del horario visible", "calendar_end_time" to "Fin del horario visible", "week_start" to "Primer día de semana")

@Composable
fun ConflictDialog(entry: JSONObject, dismiss: () -> Unit, resolve: (String, Set<String>?) -> Unit) {
  val op = entry.getJSONObject("operation")
  val result = entry.getJSONObject("result")
  val id = op.getString("operation_id")
  val fields = result.optJSONArray("fields")?.objectsStrings().orEmpty()
  val canChoose = result.optString("reason") == "fields" && result.optJSONObject("remote")?.optJSONObject("state") != null
  var localFields by remember(id) { mutableStateOf(fields.toSet()) }
  fun value(json: JSONObject?, field: String): String = when (val v = json?.opt(field)) {
    null, JSONObject.NULL -> "Sin valor"
    true -> "Sí"
    false -> "No"
    "monday" -> "Lunes"
    "sunday" -> "Domingo"
    else -> v.toString()
  }
  AlertDialog(onDismissRequest = dismiss, title = { Text("Revisar cambio pendiente") }, text = {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text(when (result.optString("reason")) {
        "deleted" -> "El elemento se eliminó en Supabase. Este cambio no puede recrearlo."
        "availability" -> "La disponibilidad de la rutina cambió. La marca conserva su fecha original y necesita revisión."
        "fields" -> "Hay cambios simultáneos. Elige qué valor conservar en cada campo."
        "exists" -> "Ya existe un elemento con este identificador. Revisa el cambio antes de continuar."
        else -> result.optString("message", "Revisa los datos y las asociaciones antes de volver a enviarlos.")
      })
      val server = result.optJSONObject("remote")?.optJSONObject("state")
      if (op.getString("action") == "delete" && server != null) {
        listOf("title", "name", "date", "completed", "status").filter { server.has(it) }.forEach { field ->
          Text("Supabase · ${fieldLabels[field] ?: field}: ${value(server, field)}")
        }
        server.optJSONArray("affected_tasks")?.let { tasks ->
          Text("Tareas afectadas: ${tasks.length()}")
          tasks.objects().forEach { task -> Text("${task.getString("title")} · ${if (task.getBoolean("completed")) "Completada" else "Pendiente"}") }
        }
        if (server.has("completion_count")) Text("Días completados que se eliminarán: ${server.getInt("completion_count")}")
      }
      val changes = op.getJSONObject("changes")
      if (op.getString("entity") == "routine_completion") Text("Día de la marca: ${op.getJSONObject("context").optString("day")}")
      if (canChoose) fields.forEach { field ->
        Text(fieldLabel(field), style = MaterialTheme.typography.titleSmall)
        if (field == "delete") {
          Text("El elemento o sus tareas cambiaron después de confirmar la eliminación. Confirma únicamente si quieres eliminarlos con su estado actual.")
        } else {
          Text("Teléfono: ${value(changes, field)}")
          Text("Supabase: ${value(result.getJSONObject("remote").getJSONObject("state"), field)}")
        }
        Row {
          RadioButton(field in localFields, onClick = { localFields = localFields + field })
          TextButton(onClick = { localFields = localFields + field }) { Text(if (field == "delete") "Confirmar eliminación" else "Teléfono") }
        }
        Row {
          RadioButton(field !in localFields, onClick = { localFields = localFields - field })
          TextButton(onClick = { localFields = localFields - field }) { Text(if (field == "delete") "Conservar elemento" else "Supabase") }
        }
      } else changes.keys().forEach { field -> Text("${fieldLabel(field)}: ${value(changes, field)}") }
      Text("Los demás cambios pendientes se conservan. Si descartas este, podrás corregirlo desde su formulario.")
    }
  }, confirmButton = {
    TextButton(onClick = { resolve(id, if (canChoose) localFields else null); dismiss() }) {
      Text(if (canChoose && op.getString("action") == "delete" && "delete" in localFields) "Confirmar eliminación" else if (canChoose) "Aplicar selección" else "Descartar este cambio")
    }
  }, dismissButton = { TextButton(onClick = dismiss) { Text("Revisar más tarde") } })
}

private fun fieldLabel(field: String) = when (field) {
  "period" -> "Período"
  "starts_on" -> "Fecha inicial"
  "target" -> "Meta"
  "unit" -> "Unidad"
  "achieved" -> "Conseguido"
  "goal_id" -> "Objetivo"
  "recorded_at" -> "Fecha de revisión"
  "amount" -> "Total alcanzado"
  "note" -> "Notas"
  "enabled" -> "Avisos activados"
  "frequency" -> "Frecuencia"
  "time" -> "Hora"
  "weekday" -> "Día de semana"
  "month_day" -> "Día de mes"
  "feature_id" -> "Funcionalidad"
  "description" -> "Descripción"
  "source_feature_id" -> "Funcionalidad previa"
  "target_feature_id" -> "Funcionalidad dependiente"
  else -> fieldLabels[field] ?: field
}
