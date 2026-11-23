package com.studycalendar.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.studycalendar.core.*

@Composable
internal fun FeatureCard(group: FeatureGroup, color: String, busy: Boolean, open: () -> Unit, edit: () -> Unit, delete: () -> Unit) {
  OutlinedCard(onClick = open, modifier = Modifier.fillMaxWidth()) {
    Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
      ColorMark(color)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(group.title, style = MaterialTheme.typography.titleMedium)
        group.feature?.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text(group.progress, style = MaterialTheme.typography.labelMedium)
        if (group.tasks.isNotEmpty()) {
          LinearProgressIndicator(progress = { group.completed.toFloat() / group.tasks.size }, modifier = Modifier.fillMaxWidth(),
            color = Color(android.graphics.Color.parseColor(color)))
        }
      }
      if (group.feature != null) MoreActions(group.title, busy, edit, delete)
    }
  }
}

@Composable
internal fun FeatureForm(project: Project, original: ProjectFeature?, busy: Boolean, dismiss: () -> Unit, save: (ProjectFeature) -> Unit) {
  val id = rememberSaveable(original?.id, project.id) { original?.id ?: java.util.UUID.randomUUID().toString() }
  var name by rememberSaveable(id) { mutableStateOf(original?.name.orEmpty()) }
  var description by rememberSaveable(id) { mutableStateOf(original?.description.orEmpty()) }
  AlertDialog(onDismissRequest = { if (!busy) dismiss() },
    title = { Text(if (original == null) "Nueva funcionalidad" else "Editar funcionalidad") },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, enabled = !busy, modifier = Modifier.fillMaxWidth(), label = { Text("Nombre") }, singleLine = true)
        OutlinedTextField(description, { description = it }, enabled = !busy, modifier = Modifier.fillMaxWidth(),
          label = { Text("Descripción (opcional)") })
      }
    },
    confirmButton = { Button(enabled = !busy && name.isNotBlank(), onClick = {
      save(ProjectFeature(id, project.id, name, description).normalized())
    }) { Text("Guardar") } },
    dismissButton = { TextButton(enabled = !busy, onClick = dismiss) { Text("Cancelar") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeatureGraphView(
  project: Project, groups: List<FeatureGroup>, links: List<ProjectFeatureLink>?, busy: Boolean,
  session: ProjectGraphSession, preferences: ProjectGraphPreferences, height: Dp,
  open: (FeatureGroup) -> Unit, edit: (ProjectFeature) -> Unit, delete: (ProjectFeature) -> Unit,
  createLink: (ProjectFeatureLink, () -> Unit) -> Unit, deleteLink: (ProjectFeatureLink) -> Unit,
) {
  val nodes = remember(groups) { groups.filter { it.feature != null || it.tasks.isNotEmpty() }.associateBy { it.id } }
  val edges = remember(nodes, links) { Features.edges(nodes.values.toList(), links.orEmpty()) }
  val branch = remember(session.root, edges) { session.root?.let { Features.branch(it, edges) } }
  val visible = nodes.filterKeys { branch == null || it in branch }
  val visibleEdges = edges.filter { it.edge.source in visible && it.edge.target in visible }
  val layoutParents = remember(visibleEdges) { Features.layoutEdges(visibleEdges).groupBy({ it.target }, { it.source }) }
  val cards = visible.values.map { group ->
    GraphCard(PlanItem(group.id, group.title, layoutParents[group.id].orEmpty().toSet()), group.progress,
      group.tasks.isNotEmpty() && group.completed == group.tasks.size)
  }
  val featureList = groups.mapNotNull { it.feature }
  val moves = remember { mutableStateOf<((String, Float, Float) -> Unit)?>(null) }
  var error by remember { mutableStateOf<String?>(null) }
  val canConnect = !busy && links != null
  fun connect(source: String, target: String) {
    if (!canConnect) return
    val link = ProjectFeatureLink(projectId = project.id, sourceId = source, targetId = target)
    try {
      Features.validateLink(link, featureList, links)
      createLink(link) { session.connecting = null; error = null }
    } catch (failure: IllegalArgumentException) {
      error = failure.message
    }
  }
  LaunchedEffect(nodes.keys, links == null) {
    if (session.root != null && session.root !in nodes) { session.full(); session.fitPending = true }
    if (session.selected !in nodes) session.selected = null
    if (session.connecting !in nodes || links == null) session.connecting = null
    if (session.drag?.id?.let { it !in nodes } == true) session.cancelGestures()
  }
  BackHandler(session.drag != null || session.connecting != null || session.root != null) { session.back(); error = null }
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    session.root?.let { root ->
      Text("Rama de «${nodes[root]?.title.orEmpty()}»", style = MaterialTheme.typography.titleSmall)
      TextButton(onClick = session::full) { Text("Ver gráfico completo") }
    }
    if (nodes.isEmpty()) Text("Sin funcionalidades ni tareas.")
    else GraphViewport(cards, visibleEdges.map { GraphArrow(it.edge, dashed = it.saved == null) }, session, preferences,
      Color(android.graphics.Color.parseColor(project.color)), busy, height, "feature-graph", "feature-node", "Abrir plan",
      select = { id ->
        if (session.connecting != null) connect(session.connecting.orEmpty(), id)
        else nodes[id]?.let(open)
      },
      contentBeforeCanvas = {
        if (links == null) Text("Las conexiones de planificación aún no están disponibles.", style = MaterialTheme.typography.bodySmall)
        if (edges.isNotEmpty()) Text("Flecha continua: planificación · Flecha discontinua: tareas", style = MaterialTheme.typography.bodySmall)
        session.connecting?.let {
          Text("Elige la funcionalidad que depende de «${nodes[it]?.title.orEmpty()}».",
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
          TextButton(onClick = { session.cancelGestures(); error = null }) { Text("Cancelar conexión") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
      }, moves = moves, details = { session.selected = it; error = null })
  }
  nodes[session.selected]?.let { group ->
    ModalBottomSheet(onDismissRequest = { session.selected = null; error = null }) {
      Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(group.title, style = MaterialTheme.typography.titleLarge)
        group.feature?.description?.let { Text(it) }
        Text(group.progress)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          TextButton(onClick = { session.selected = null; open(group) }) { Text("Abrir plan") }
          TextButton(onClick = { session.focus(group.id) }) { Text("Ver rama") }
          group.feature?.let { feature ->
            TextButton(enabled = canConnect, onClick = { session.selected = null; session.connecting = feature.id }) { Text("Conectar con…") }
            TextButton(enabled = !busy, onClick = { session.selected = null; edit(feature) }) { Text("Editar") }
            TextButton(enabled = !busy, onClick = { session.selected = null; delete(feature) }) { Text("Eliminar") }
          }
        }
        Text("Funcionalidades previas", style = MaterialTheme.typography.titleMedium)
        val incoming = edges.filter { it.edge.target == group.id }
        if (incoming.isEmpty()) Text("Sin funcionalidades previas.")
        incoming.forEach { edge ->
          val source = nodes.getValue(edge.edge.source)
          val origin = when {
            edge.saved == null -> "Tareas"
            edge.inferred -> "Planificación y tareas"
            else -> "Planificación"
          }
          Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = { session.selected = source.id }, modifier = Modifier.weight(1f)) { Text("${source.title} · $origin") }
            edge.saved?.let { link ->
              TextButton(enabled = canConnect, onClick = { deleteLink(link) },
                modifier = Modifier.semantics { contentDescription = "Quitar conexión de ${source.title}" }) { Text("Quitar") }
            }
          }
        }
        val candidates = if (group.feature != null && canConnect) featureList.filter { candidate ->
          candidate.id != group.id && incoming.none { it.edge.source == candidate.id && it.saved != null }
        } else emptyList()
        if (candidates.isNotEmpty()) {
          var choosing by remember(group.id) { mutableStateOf(false) }
          OutlinedButton(onClick = { choosing = !choosing }) { Text(if (choosing) "Cerrar selector" else "Añadir funcionalidad previa") }
          if (choosing) candidates.forEach { candidate ->
            TextButton(onClick = { connect(candidate.id, group.id) }, modifier = Modifier.testTag("feature-prior-${candidate.id}")) {
              Text(candidate.name)
            }
          }
        }
        Text("Mover tarjeta", style = MaterialTheme.typography.titleSmall)
        FlowRow {
          listOf(Triple("Izquierda", -20f, 0f), Triple("Derecha", 20f, 0f), Triple("Arriba", 0f, -20f), Triple("Abajo", 0f, 20f)).forEach { (label, dx, dy) ->
            TextButton(onClick = { moves.value?.invoke(group.id, dx, dy) }) { Text(label) }
          }
        }
      }
    }
  }
}
