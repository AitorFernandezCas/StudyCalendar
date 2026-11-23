package com.studycalendar.android

import android.content.Context
import androidx.compose.runtime.*
import androidx.lifecycle.ViewModel
import com.studycalendar.core.*
import org.json.JSONObject

/** Presentation preferences only: never part of Room or the account outbox. */
class ProjectGraphPreferences(context: Context, owner: String, projectId: String, diagram: String? = null) {
  private val prefs = context.getSharedPreferences("project-graph-$owner-$projectId" + (diagram?.let { "-$it" } ?: ""), Context.MODE_PRIVATE)
  var graph: Boolean
    get() = prefs.getBoolean("graph", true)
    set(value) { prefs.edit().putBoolean("graph", value).apply() }
  fun read(): Map<String, PlanPoint> = try {
    val json = JSONObject(prefs.getString("positions", "{}") ?: "{}")
    json.keys().asSequence().mapNotNull { id ->
      val row = json.optJSONObject(id) ?: return@mapNotNull null
      val point = PlanPoint(row.optDouble("x", Double.NaN).toFloat(), row.optDouble("y", Double.NaN).toFloat())
      if (point.valid()) id to point else null
    }.toMap()
  } catch (_: org.json.JSONException) { emptyMap() }
  fun write(positions: Map<String, PlanPoint>) {
    val json = JSONObject()
    positions.filterValues { it.valid() }.forEach { (id, point) -> json.put(id, JSONObject().put("x", point.x).put("y", point.y)) }
    prefs.edit().putString("positions", json.toString()).apply()
  }
}

data class PlanDrag(val id: String, val original: PlanPoint?, val grab: PlanPoint, val pointer: PlanPoint)

class ProjectGraphSession(initial: Map<String, PlanPoint> = emptyMap()) {
  var positions by mutableStateOf(initial)
  var branchPositions by mutableStateOf<Map<String, PlanPoint>>(emptyMap())
  var root by mutableStateOf<String?>(null)
    private set
  var camera by mutableStateOf(PlanCamera())
  private var fullCamera: PlanCamera? = null
  var fitPending by mutableStateOf(true)
  var selected by mutableStateOf<String?>(null)
  var connecting by mutableStateOf<String?>(null)
  var drag by mutableStateOf<PlanDrag?>(null)
    private set
  var gestureEpoch by mutableIntStateOf(0)
    private set
  val activePositions get() = if (root == null) positions else branchPositions
  fun place(id: String, position: PlanPoint) {
    if (root == null) positions = positions + (id to position) else branchPositions = branchPositions + (id to position)
  }
  fun beginDrag(id: String, position: PlanPoint, pointer: PlanPoint) {
    drag = PlanDrag(id, activePositions[id], PlanPoint(ProjectGraph.world(pointer, camera).x - position.x, ProjectGraph.world(pointer, camera).y - position.y), pointer)
  }
  fun dragTo(pointer: PlanPoint) {
    val active = drag ?: return
    drag = active.copy(pointer = pointer)
    val world = ProjectGraph.world(pointer, camera)
    place(active.id, PlanPoint((world.x - active.grab.x).coerceAtLeast(16f), (world.y - active.grab.y).coerceAtLeast(16f)))
  }
  fun finishDrag() { drag = null }
  fun cancelDrag() {
    drag?.let { active ->
      val next = if (active.original == null) activePositions - active.id else activePositions + (active.id to active.original)
      if (root == null) positions = next else branchPositions = next
    }
    drag = null
  }
  fun cancelGestures() { cancelDrag(); connecting = null; gestureEpoch++ }
  fun back(): Boolean = when {
    selected != null -> { selected = null; true }
    drag != null || connecting != null -> { cancelGestures(); true }
    root != null -> { full(); true }
    else -> false
  }
  fun focus(id: String) {
    cancelGestures()
    if (root == null) fullCamera = camera
    root = id; branchPositions = emptyMap(); selected = null; fitPending = true
  }
  fun full() {
    cancelGestures()
    root = null; branchPositions = emptyMap(); selected = null
    camera = fullCamera ?: PlanCamera(); fullCamera = null; fitPending = false
  }
  fun arrange() {
    cancelGestures()
    if (root == null) positions = emptyMap() else branchPositions = emptyMap()
    fitPending = true
  }
}

/** Retains cameras/branch selection on rotation, without restoring them after process death. */
class ProjectGraphSessions : ViewModel() {
  private val sessions = mutableMapOf<Triple<String, String, String?>, ProjectGraphSession>()
  private var active: Pair<String, String>? = null
  fun activate(owner: String, project: String?) {
    val next = project?.let { owner to it }
    if (active != next) {
      active?.let { previous ->
        sessions.filterKeys { it.first == previous.first && it.second == previous.second }.values.forEach { session ->
          session.cancelGestures()
          if (session.root != null) session.full()
          session.selected = null
          session.camera = PlanCamera()
          session.fitPending = true
        }
      }
      active = next
    }
  }
  fun get(owner: String, project: String, preferences: ProjectGraphPreferences, diagram: String? = null) =
    sessions.getOrPut(Triple(owner, project, diagram)) { ProjectGraphSession(preferences.read()) }
}
