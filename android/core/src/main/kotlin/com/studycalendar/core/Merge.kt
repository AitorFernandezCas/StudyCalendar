package com.studycalendar.core

// Maps retain explicit nulls: omission and clearing a field are different.
data class MergeResult(val values: Map<String, String?>, val conflicts: Set<String>, val deletedRemotely: Boolean = false)

fun mergeFields(base: Map<String, String?>, changes: Map<String, String?>, remote: Map<String, String?>?): MergeResult {
  if (remote == null) return MergeResult(emptyMap(), changes.keys, deletedRemotely = true)
  val conflicts = changes.keys.filter { key ->
    val remoteChanged = base.containsKey(key) != remote.containsKey(key) || base[key] != remote[key]
    remoteChanged && (!remote.containsKey(key) || changes[key] != remote[key])
  }.toSet()
  return MergeResult(remote + changes.filterKeys { it !in conflicts }, conflicts)
}

data class PendingOperation(
  val id: String,
  val entityId: String,
  val base: Map<String, String?>,
  val changes: Map<String, String?>,
  val delete: Boolean = false,
)

fun deletionConflicts(base: Map<String, String?>, remote: Map<String, String?>?) = remote != null && base != remote
