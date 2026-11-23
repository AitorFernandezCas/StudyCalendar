package com.studycalendar.android

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.studycalendar.core.ProjectFeature
import com.studycalendar.core.ProjectFeatureLink
import org.json.JSONObject

@Entity(tableName = "project_features", indices = [Index("projectId")], foreignKeys = [
  ForeignKey(entity = ProjectRow::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.CASCADE),
])
data class ProjectFeatureRow(@PrimaryKey val id: String, val projectId: String, val name: String, val description: String?) {
  fun feature() = ProjectFeature(id, projectId, name, description)
  fun json(): JSONObject = JSONObject().put("id", id).put("project_id", projectId).put("name", name)
    .put("description", description ?: JSONObject.NULL)

  companion object {
    fun from(feature: ProjectFeature) = ProjectFeatureRow(feature.id, feature.projectId, feature.name, feature.description)
    fun parse(row: JSONObject) = ProjectFeatureRow(row.getString("id"), row.getString("project_id"), row.getString("name"), row.nullable("description"))
  }
}

@Entity(tableName = "project_feature_links", indices = [Index(value = ["sourceId", "targetId"], unique = true), Index("targetId")], foreignKeys = [
  ForeignKey(entity = ProjectFeatureRow::class, parentColumns = ["id"], childColumns = ["sourceId"], onDelete = ForeignKey.CASCADE),
  ForeignKey(entity = ProjectFeatureRow::class, parentColumns = ["id"], childColumns = ["targetId"], onDelete = ForeignKey.CASCADE),
])
data class ProjectFeatureLinkRow(@PrimaryKey val id: String, val projectId: String, val sourceId: String, val targetId: String) {
  fun link() = ProjectFeatureLink(id, projectId, sourceId, targetId)
  fun json(): JSONObject = JSONObject().put("id", id).put("project_id", projectId)
    .put("source_feature_id", sourceId).put("target_feature_id", targetId)

  companion object {
    fun from(link: ProjectFeatureLink) = ProjectFeatureLinkRow(link.id, link.projectId, link.sourceId, link.targetId)
    fun parse(row: JSONObject) = ProjectFeatureLinkRow(row.getString("id"), row.getString("project_id"),
      row.getString("source_feature_id"), row.getString("target_feature_id"))
  }
}

internal fun featureEdit(value: ProjectFeature, original: ProjectFeature?, current: ProjectFeature?): ProjectFeature {
  if (original == null) {
    require(current == null) { "La funcionalidad ya existe." }
    return value
  }
  require(current != null && current.projectId == value.projectId && original.id == value.id) { "La funcionalidad ya no está disponible." }
  require((value.name == original.name || current.name == original.name || current.name == value.name) &&
    (value.description == original.description || current.description == original.description || current.description == value.description)) {
    "La funcionalidad ha cambiado. Vuelve a abrirla."
  }
  return current.copy(name = if (value.name == original.name) current.name else value.name,
    description = if (value.description == original.description) current.description else value.description)
}
