package com.studycalendar.android

import android.app.*
import android.content.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.studycalendar.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.time.Instant

/** Every alarm is rebuilt from durable data. Notification delivery never needs a network. */
class GoalNotifications(private val app: CalendarApplication) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val ticks = MutableStateFlow(0)
  fun refresh() { ticks.value += 1 }
  private fun data() = combine(app.goals.goals, app.goals.reviews, app.goals.schedules, app.connection.state, ticks) { goals, reviews, schedules, cloud, _ ->
    val account = cloud.session != null
    NotificationData(cloud.session?.userId ?: "local", if (account) cloud.snapshot?.goals.orEmpty() else goals.map { it.goal() },
      if (account) cloud.snapshot?.goalReviews.orEmpty() else reviews.map { it.review() },
      if (account) cloud.snapshot?.goalSchedules.orEmpty() else schedules.map { it.schedule() })
  }
  suspend fun refreshNow() {
    app.connection.restore()
    reconcile(data().first())
  }
  fun start() {
    (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
      NotificationChannel("goal_reviews", "Revisión de objetivos", NotificationManager.IMPORTANCE_DEFAULT))
    scope.launch { app.connection.restore(); data().collect { reconcile(it) } }
  }
  @android.annotation.SuppressLint("MissingPermission")
  @Synchronized
  private fun reconcile(data: NotificationData) {
    val now = Instant.now()
    val manager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    val alarm = PendingIntent.getBroadcast(app, 901, Intent(app, GoalAlarmReceiver::class.java).setAction("com.studycalendar.GOAL_REVIEW_ALARM"), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    manager.cancel(alarm)
    val today = now.atZone(GoalReminders.zone).toLocalDate()
    val eligible = data.goals.filter { !it.achieved && it.endsOn >= today }
    val nextReview = GoalReminders.next(data.schedules.filter { schedule -> eligible.any { it.period == schedule.period } }, now)
    val expiration = eligible.minOfOrNull { it.endsOn.plusDays(1).atStartOfDay(GoalReminders.zone).toInstant() }
    listOfNotNull(nextReview, expiration).minOrNull()?.let {
      manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, it.toEpochMilli(), alarm)
    }
    val notifications = NotificationManagerCompat.from(app)
    val due = GoalReminders.due(data.goals, data.reviews, data.schedules, now)
    if (due.isEmpty()) { notifications.cancel(902); return }
    val preferences = app.getSharedPreferences("goal-delivery", Context.MODE_PRIVATE)
    fun occurrence(goal: Goal) = data.schedules.first { it.period == goal.period && it.enabled }.latest(now).toString()
    val channel = (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).getNotificationChannel("goal_reviews")
    if (!notifications.areNotificationsEnabled() || channel?.importance == NotificationManager.IMPORTANCE_NONE) return
    val allDelivered = due.all { preferences.getString("${data.owner}/${it.id}", null) == occurrence(it) }
    val showing = (app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).activeNotifications.any { it.id == 902 }
    if (allDelivered && !showing) return
    val open = PendingIntent.getActivity(app, 902, Intent(app, MainActivity::class.java).putExtra("goals", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notification = NotificationCompat.Builder(app, "goal_reviews").setSmallIcon(com.studycalendar.android.R.drawable.ic_calendar)
      .setContentTitle("Revisar objetivos").setContentText(if (due.size == 1) "1 objetivo pendiente de revisión" else "${due.size} objetivos pendientes de revisión")
      .setContentIntent(open).setOnlyAlertOnce(allDelivered).setAutoCancel(true).build()
    try {
      notifications.notify(902, notification)
      preferences.edit().also { editor -> due.forEach { editor.putString("${data.owner}/${it.id}", occurrence(it)) } }.apply()
    } catch (_: SecurityException) { /* Permission may be revoked between the check and delivery. */ }
  }
}
private data class NotificationData(val owner: String, val goals: List<Goal>, val reviews: List<GoalReview>, val schedules: List<GoalSchedule>)

class GoalAlarmReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action !in setOf("com.studycalendar.GOAL_REVIEW_ALARM", Intent.ACTION_BOOT_COMPLETED,
        Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
      try { withTimeout(8_000) { (context.applicationContext as CalendarApplication).refreshGoalsNow() } }
      catch (_: Exception) { android.util.Log.w("GoalReminders", "No se pudieron reconstruir los avisos. Se revisarán al abrir la app.") }
      finally { pending.finish() }
    }
  }
}
