package com.studycalendar.android

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

object SyncScheduling {
  fun enqueue(context: Context) {
    val work = OneTimeWorkRequestBuilder<AccountSyncWorker>()
      .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
      .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
    WorkManager.getInstance(context).enqueueUniqueWork("account-sync", ExistingWorkPolicy.APPEND_OR_REPLACE, work)
  }
}

class AccountSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
  override suspend fun doWork(): Result {
    val connection = (applicationContext as CalendarApplication).connection
    connection.restore()
    if (connection.state.value.session == null || connection.state.value.pending == 0) return Result.success()
    connection.refresh()
    val state = connection.state.value
    return if (state.error != null && state.pending > 0 && state.conflicts.isEmpty() && runAttemptCount < 6) Result.retry() else Result.success()
  }
}
