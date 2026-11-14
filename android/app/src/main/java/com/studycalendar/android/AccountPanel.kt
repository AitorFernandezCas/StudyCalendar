package com.studycalendar.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun AccountPanel(state: CloudState, pending: Int?, login: (String, String) -> Unit, refresh: () -> Unit, logout: () -> Unit, resolve: (String, Set<String>?) -> Unit) {
  var email by remember(state.session?.email) { mutableStateOf(state.session?.email ?: "") }
  var password by remember { mutableStateOf("") }
  var showLogin by remember { mutableStateOf(false) }
  var showConflict by remember { mutableStateOf(false) }
  var confirmLogout by remember { mutableStateOf(false) }
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      if (state.session == null) {
        Text("Datos locales", style = MaterialTheme.typography.titleMedium)
        Text("Conecta tu cuenta para consultar las tareas, proyectos y rutinas de la web. Tus datos de prueba se conservarán por separado.")
        Text("Cambios locales guardados: ${pending ?: "…"}", style = MaterialTheme.typography.labelMedium)
        TextButton(enabled = !state.working, onClick = { showLogin = !showLogin }) { Text("Conectar con Supabase") }
      } else {
        Text(state.session.email, style = MaterialTheme.typography.titleMedium)
        Text(if (state.snapshot?.protocol == 1) "Cuenta conectada · cambios guardados sin conexión" else "Cuenta conectada · consulta y copia sin conexión")
        if (state.snapshot?.protocol != 1) Text("El servidor todavía no tiene el contrato de escritura disponible. Los datos de la cuenta se muestran para consulta.")
        else Text("Cambios pendientes: ${state.pending}")
        if (state.conflicts.isNotEmpty()) TextButton(enabled = !state.working, onClick = { showConflict = true }) { Text("Revisar conflicto · envío detenido") }
        state.snapshot?.let { Text("Última descarga: ${it.downloadedAt}", style = MaterialTheme.typography.labelMedium) }
        Row {
          TextButton(enabled = !state.working, onClick = refresh) { Text("Actualizar") }
          TextButton(enabled = !state.working, onClick = { showLogin = !showLogin }) { Text("Renovar acceso") }
        }
        TextButton(enabled = !state.working, onClick = { if (state.pending > 0) confirmLogout = true else logout() }) { Text("Cerrar sesión y volver a datos locales") }
      }
      if (showLogin) {
        OutlinedTextField(email, { email = it }, enabled = !state.working, label = { Text("Correo de tu cuenta") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, enabled = !state.working, label = { Text("Contraseña") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
        Button(enabled = !state.working && email.isNotBlank() && password.isNotEmpty(), onClick = {
          val secret = password
          password = ""
          showLogin = false
          login(email, secret)
        }) { Text("Iniciar sesión") }
      }
      if (state.working) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Sincronizando datos…") }
      state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
  }
  if (showConflict) state.conflicts.firstOrNull()?.let { entry -> ConflictDialog(entry, { showConflict = false }, resolve) }
  if (confirmLogout) AlertDialog(onDismissRequest = { confirmLogout = false }, title = { Text("¿Cerrar sesión?") }, text = { Text("Hay ${state.pending} cambios pendientes. Seguirán guardados en este teléfono para cuando vuelvas a entrar con esta misma cuenta.") }, confirmButton = { TextButton(onClick = { confirmLogout = false; logout() }) { Text("Cerrar sesión") } }, dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("Cancelar") } })
}
