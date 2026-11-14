package com.studycalendar.android

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import com.studycalendar.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.KeyStore
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

// Public client configuration. This key cannot bypass authenticated RLS.
object SupabaseConfig {
  const val url = "https://nppktgavaulgidxqegse.supabase.co"
  const val key = "sb_publishable_Rq7WfZv5TvjEdG4IOhae5A_ypG-rTZe"
}

class CloudFailure(val status: Int, message: String) : Exception(message)

fun interface CloudTransport {
  suspend fun request(path: String, method: String, token: String?, body: JSONObject?): String
}

class SupabaseTransport : CloudTransport {
  override suspend fun request(path: String, method: String, token: String?, body: JSONObject?): String = withContext(Dispatchers.IO) {
    val connection = URL(SupabaseConfig.url + path).openConnection() as HttpsURLConnection
    try {
      connection.requestMethod = method
      connection.instanceFollowRedirects = false
      connection.connectTimeout = 15_000
      connection.readTimeout = 30_000
      connection.setRequestProperty("apikey", SupabaseConfig.key)
      connection.setRequestProperty("Accept", "application/json")
      connection.setRequestProperty("Content-Type", "application/json")
      if (path.startsWith("/rest/")) { connection.setRequestProperty("Accept-Profile", "Task"); connection.setRequestProperty("Content-Profile", "Task") }
      token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
      body?.let {
        connection.doOutput = true
        connection.outputStream.use { stream -> stream.write(it.toString().toByteArray(Charsets.UTF_8)) }
      }
      val status = connection.responseCode
      if (status !in 200..299) throw CloudFailure(status, when (status) {
        400, 401, 403, 422 -> "Revisa tu correo y contraseña o vuelve a iniciar sesión."
        429 -> "Demasiados intentos. Espera unos minutos antes de volver a intentarlo."
        else -> "Supabase no está disponible. Inténtalo de nuevo."
      })
      connection.inputStream.bufferedReader().use { it.readText() }
    } finally { connection.disconnect() }
  }
}

data class CloudSession(val userId: String, val email: String, val access: String, val refresh: String, val expiresAt: Long) {
  fun json() = JSONObject().put("userId", userId).put("email", email).put("access", access).put("refresh", refresh).put("expiresAt", expiresAt)
  companion object {
    fun stored(json: JSONObject) = CloudSession(UUID.fromString(json.getString("userId")).toString(), json.getString("email"), json.getString("access"), json.getString("refresh"), json.getLong("expiresAt"))
    fun response(json: JSONObject): CloudSession {
      val user = json.getJSONObject("user")
      return CloudSession(UUID.fromString(user.getString("id")).toString(), user.optString("email"), json.getString("access_token"), json.getString("refresh_token"),
        json.optLong("expires_at", Instant.now().epochSecond + json.getLong("expires_in")))
    }
  }
}

// Tokens are encrypted with a non-exportable Android Keystore key. Never store passwords.
class SessionVault(context: Context) {
  private val prefs = context.getSharedPreferences("supabase-session", Context.MODE_PRIVATE)
  private fun key(): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    return (store.getKey("studycalendar-session", null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
      init(KeyGenParameterSpec.Builder("studycalendar-session", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
    }.generateKey()
  }
  fun save(session: CloudSession) {
    val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
    val data = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(session.json().toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    check(prefs.edit().putString("encrypted", data).commit()) { "No se pudo conservar la sesión." }
  }
  fun load(): CloudSession? {
    val data = prefs.getString("encrypted", null) ?: return null
    return try {
      val parts = data.split(":")
      val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP))) }
      CloudSession.stored(JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8)))
    } catch (_: Exception) { clear(); null }
  }
  fun clear() { check(prefs.edit().clear().commit()) }
}

fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
fun JSONObject.nullable(name: String): String? = if (isNull(name)) null else getString(name)

data class CloudSnapshot(val tasks: List<TaskRow>, val projects: List<ProjectRow>, val routines: List<RoutineRecord>, val reset: LocalTime, val downloadedAt: String, val protocol: Int = 0) {
  companion object {
    fun parse(json: JSONObject, user: String): CloudSnapshot {
      require(json.getString("owner") == user) { "La copia pertenece a otra cuenta." }
      val data = json.getJSONObject("data")
      fun rows(table: String) = data.getJSONArray(table).objects().onEach { require(it.getString("user_id") == user) { "Respuesta de otra cuenta." } }
      val projects = rows("projects").map { ProjectRow.from(Project(it.getString("id"), it.getString("name"), it.getString("color"), ProjectStatus.valueOf(it.getString("status").uppercase()))) }
      val tasks = rows("tasks").filter { it.getString("task_type") != "routine" }.map {
        TaskRow(it.getString("id"), it.getString("title"), it.nullable("date"), it.nullable("start_time"), it.nullable("end_time"), it.getBoolean("all_day"), it.getBoolean("completed"), it.nullable("project_id"), it.getString("task_type") == "project", it.getJSONArray("prerequisite_ids").toString())
      }
      val periods = rows("routine_period").groupBy { it.getString("routine_id") }
      val completions = rows("routine_completion").groupBy { it.getString("routine_id") }
      val routines = rows("routine").map {
        val id = it.getString("id")
        val routine = Routine(id, it.getString("title"), it.getString("color"), it.getBoolean("active"), periods[id].orEmpty().map { period -> ActivePeriod(LocalDate.parse(period.getString("starts_on")), period.nullable("ends_on")?.let(LocalDate::parse)) })
        RoutineRecord(RoutineRow.from(routine), completions[id].orEmpty().map { completion -> CompletionRow(id, completion.getString("occurrence_date"), completion.getBoolean("completed")) })
      }
      val settings = rows("user_settings").singleOrNull()
      return CloudSnapshot(
        tasks.sortedWith(compareBy<TaskRow> { it.date }.thenByDescending { it.allDay }.thenBy { it.start }.thenBy { it.title.lowercase() }),
        projects.sortedBy { it.name.lowercase() }, routines.sortedBy { it.row.title.lowercase() },
        settings?.getString("routine_reset_time")?.let(LocalTime::parse) ?: LocalTime.MIDNIGHT, json.getString("downloadedAt"), json.optInt("protocol"))
    }
  }
}

class CloudReader(private val transport: CloudTransport) {
  suspend fun login(email: String, password: String): CloudSession = CloudSession.response(JSONObject(transport.request("/auth/v1/token?grant_type=password", "POST", null, JSONObject().put("email", email.trim()).put("password", password))))
  suspend fun renew(session: CloudSession): CloudSession {
    val next = CloudSession.response(JSONObject(transport.request("/auth/v1/token?grant_type=refresh_token", "POST", null, JSONObject().put("refresh_token", session.refresh))))
    require(next.userId == session.userId) { "La sesión pertenece a otra cuenta." }
    return next
  }
  suspend fun apply(session: CloudSession, operation: JSONObject): JSONObject = JSONObject(transport.request("/rest/v1/rpc/sync_apply", "POST", session.access, JSONObject().put("p_operation", operation)))
  suspend fun snapshot(session: CloudSession): JSONObject {
    try {
      return JSONObject(transport.request("/rest/v1/rpc/sync_snapshot", "POST", session.access, JSONObject()))
    } catch (failure: CloudFailure) { if (failure.status != 404) throw failure }

    val tables = listOf("tasks", "projects", "routine", "routine_period", "routine_completion", "user_settings")
    val data = JSONObject()
    for (table in tables) {
      val all = JSONArray()
      var cursor: String? = null
      do {
        val sort = if (table == "user_settings") "user_id" else "id"
        val path = "/rest/v1/$table?select=*&user_id=eq.${session.userId}&order=$sort.asc&limit=200" + (cursor?.let { "&$sort=gt.$it" } ?: "")
        val page = JSONArray(transport.request(path, "GET", session.access, null))
        page.objects().forEach { row ->
          require(row.getString("user_id") == session.userId) { "Respuesta de otra cuenta." }
          all.put(row)
        }
        val next = page.objects().lastOrNull()?.getString(sort)
        require(next == null || next != cursor) { "La descarga no avanza." }
        cursor = next
      } while (page.length() > 0)
      data.put(table, all)
    }
    return JSONObject().put("owner", session.userId).put("data", data).put("downloadedAt", Instant.now().toString())
  }
}

data class CloudState(
  val session: CloudSession? = null,
  val snapshot: CloudSnapshot? = null,
  val working: Boolean = false,
  val error: String? = null,
  val pending: Int = 0,
  val conflicts: List<JSONObject> = emptyList(),
)

