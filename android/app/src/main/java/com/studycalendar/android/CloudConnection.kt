package com.studycalendar.android

import android.content.Context
import android.util.AtomicFile
import com.studycalendar.core.Project
import com.studycalendar.core.Task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class CloudConnection(context: Context, private val reader: CloudReader = CloudReader(SupabaseTransport())) : CalendarCommands {
  private val vault = SessionVault(context)
  private val directory = File(context.filesDir, "account-cache").apply { mkdirs() }
  private val localMutex = Mutex()
  private val networkMutex = Mutex()
  private var replica: AccountReplica? = null
  private var restored = false
  private var unreadable = false
  val state = MutableStateFlow(CloudState(working = true))
  private fun file(user: String) = AtomicFile(File(directory, "${UUID.fromString(user)}.json"))

  // Called under localMutex. Network waits never hold this lock.
  private suspend fun load(session: CloudSession) {
    unreadable = false
    replica = withContext(Dispatchers.IO) {
      try { file(session.userId).openRead().bufferedReader().use { AccountReplica.load(JSONObject(it.readText()), session.userId) } }
      catch (_: java.io.FileNotFoundException) { null }
      catch (_: Exception) { unreadable = true; null }
    }
    state.value = CloudState(session = session, working = state.value.working,
      error = if (unreadable) "No se pudo leer la copia local. Se ha conservado para recuperar los cambios pendientes." else null)
    publish()
  }
  suspend fun restore() = localMutex.withLock {
    if (!restored) {
      val session = withContext(Dispatchers.IO) { vault.load() }
      if (session == null) state.value = CloudState() else { load(session); state.update { it.copy(working = false) } }
      restored = true
    }
  }
  private fun publish() {
    val view = replica?.snapshot()
    state.update { it.copy(snapshot = view, pending = replica?.pending ?: 0, conflicts = replica?.conflicts.orEmpty()) }
  }
  private suspend fun persist(next: AccountReplica) {
    next.snapshot() // Validate the rendered document before replacing its durable predecessor.
    withContext(Dispatchers.IO) {
      val target = file(next.owner)
      val stream = target.startWrite()
      try { stream.write(next.document.toString().toByteArray(Charsets.UTF_8)); target.finishWrite(stream) }
      catch (failure: Exception) { target.failWrite(stream); throw failure }
    }
    replica = next
    publish()
  }
  suspend fun login(email: String, password: String) = operation {
    val session = reader.login(email, password)
    localMutex.withLock {
      withContext(Dispatchers.IO) { vault.save(session) }
      load(session)
      restored = true
    }
    synchronize()
  }
  suspend fun refresh() = operation { synchronize() }
  suspend fun logout() = operation {
    localMutex.withLock {
      withContext(Dispatchers.IO) { vault.clear() }
      replica = null
      unreadable = false
      state.value = CloudState(working = true)
    }
  }
  private suspend fun synchronize() {
    localMutex.withLock { check(!unreadable) { "La copia local necesita recuperación antes de sincronizar." } }
    var session = requireNotNull(state.value.session) { "Inicia sesión para sincronizar." }
    if (session.expiresAt <= Instant.now().epochSecond + 60) {
      session = reader.renew(session)
      withContext(Dispatchers.IO) { vault.save(session) }
      state.update { it.copy(session = session) }
    }
    suspend fun download() {
      val json = reader.snapshot(session)
      localMutex.withLock {
        if (json.optInt("protocol") != 1 && (replica?.pending ?: 0) > 0) throw IllegalArgumentException("El servidor todavía no permite enviar estos cambios. Se conservarán en el teléfono.")
        val next = replica?.copy()?.apply { replaceRemote(json) } ?: AccountReplica.load(json, session.userId)
        persist(next)
      }
    }
    download()
    var applied = false
    while (true) {
      val op = localMutex.withLock {
        val next = replica?.copy() ?: return@withLock null
        val prepared = next.prepareNext() ?: return@withLock null
        persist(next) // Persist the immutable operation before HTTP, including its original day.
        prepared
      } ?: break
      val result = reader.apply(session, op)
      localMutex.withLock {
        val next = requireNotNull(replica).copy()
        require(next.operations.getJSONObject(0).getJSONObject("operation").getString("operation_id") == op.getString("operation_id"))
        next.acknowledge(result)
        persist(next) // Includes any local edits appended while HTTP was in flight.
      }
      if (result.getString("status") != "applied") break
      applied = true
    }
    if (applied) download()
  }
  private suspend fun operation(action: suspend () -> Unit) = networkMutex.withLock {
    state.update { it.copy(working = true, error = null) }
    try { action() } catch (failure: Exception) {
      if (failure is kotlinx.coroutines.CancellationException) throw failure
      state.update { it.copy(error = if (failure is CloudFailure || failure is IllegalArgumentException) failure.message else "No se pudo sincronizar. La copia y los cambios pendientes siguen guardados. Revisa internet y vuelve a intentarlo.") }
    } finally { state.update { it.copy(working = false) } }
  }
  private suspend fun edit(change: AccountReplica.() -> Unit) = localMutex.withLock {
    require(state.value.session != null && state.value.snapshot?.protocol == 1) { "El contrato de sincronización todavía no está disponible." }
    val next = requireNotNull(replica).copy()
    next.change()
    persist(next)
  }
  suspend fun resolve(id: String, keepLocalFields: Set<String>?) = edit { resolve(id, keepLocalFields) }
  override suspend fun save(task: Task) = edit { save(task) }
  override suspend fun complete(id: String) = edit { complete(id) }
  override suspend fun delete(id: String) = edit { delete(id) }
  override suspend fun saveProject(project: Project) = edit { saveProject(project) }
  override suspend fun deleteProject(id: String) = edit { deleteProject(id) }
  override suspend fun saveRoutine(id: String, title: String, color: String, active: Boolean, startsOn: LocalDate) = edit { saveRoutine(id, title, color, active, startsOn, Instant.now()) }
  override suspend fun completeRoutine(id: String, date: LocalDate, completed: Boolean) = edit { completeRoutine(id, date, completed, Instant.now()) }
  override suspend fun deleteRoutine(id: String) = edit { deleteRoutine(id) }
}
