package com.standby.clock

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService

/**
 * Keeps the notification listener bound. Samsung's NotificationManager does not re-bind a listener whose
 * process died ("connection lost" → "Removing active service", verified on the M34), and requestRebind()
 * only undoes a requestUnbind(). What works is disabling our own component, letting NotificationManager
 * process that, then enabling it again so it binds afresh.
 * Checked from a JobScheduler job (every 15 min, survives reboot) and a 1-minute non-waking alarm, so a
 * dropped listener comes back within about a minute of the phone being awake, unless the user pressed Stop.
 */
class BindWatchdog : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        ensureBound(this, "watchdog") { jobFinished(params, false) }
        return true
    }

    override fun onStopJob(params: JobParameters) = false

    /** The 1-minute alarm, and re-arming everything after a reboot. */
    class Alarm : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) schedule(context)
            val pending = goAsync()
            ensureBound(context, "alarm") { pending.finish() }
        }
    }

    companion object {
        private const val JOB_ID = 1
        private const val PREFS = "standby"
        private const val KEY_STOPPED = "stopped_by_user"
        private const val TOGGLE_GAP_MS = 1_500L
        private const val ALARM_INTERVAL_MS = 60_000L

        fun schedule(context: Context) {
            context.getSystemService(JobScheduler::class.java).schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, BindWatchdog::class.java))
                    .setPeriodic(15 * 60 * 1000L)
                    .setPersisted(true)
                    .build()
            )
            // Non-waking: costs nothing while the phone sleeps, fires as soon as it is awake again.
            context.getSystemService(AlarmManager::class.java).setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + ALARM_INTERVAL_MS,
                ALARM_INTERVAL_MS,
                PendingIntent.getBroadcast(context, 0, Intent(context, Alarm::class.java), PendingIntent.FLAG_IMMUTABLE)
            )
        }

        fun setStoppedByUser(context: Context, stopped: Boolean) =
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_STOPPED, stopped).apply()

        /**
         * Binds the listener if access is granted, it is not running, and the user has not pressed Stop.
         * [done] runs when finished (immediately if nothing was needed).
         */
        fun ensureBound(context: Context, reason: String, done: () -> Unit = {}) {
            val listener = ComponentName(context, SensorService::class.java)
            if (SensorService.instance != null ||
                context.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_STOPPED, false) ||
                !context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(listener)
            ) return done()
            NotificationListenerService.requestRebind(listener)
            val pm = context.packageManager
            pm.setComponentEnabledSetting(listener, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            // Two changes in quick succession reach NotificationManager as one "still enabled" change, so wait.
            Handler(Looper.getMainLooper()).postDelayed({
                pm.setComponentEnabledSetting(listener, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                EventLog.append(context, "rebind requested ($reason)")
                // On One UI the toggle often isn't enough (NotificationManager keeps a stale "already binding"
                // entry after the listener's process dies). Only switching notification access off and on clears
                // it, which an app may not do itself, so offer it to the user; onListenerConnected removes this.
                postRestartPrompt(context, listener)
                done()
            }, TOGGLE_GAP_MS)
        }

        const val RESTART_NOTIFICATION_ID = 3

        fun restartIntent(listener: ComponentName): Intent =
            (if (android.os.Build.VERSION.SDK_INT >= 30) Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, listener.flattenToString())
            else Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        private fun postRestartPrompt(context: Context, listener: ComponentName) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel("status", "Standby status", NotificationManager.IMPORTANCE_DEFAULT))
            nm.notify(RESTART_NOTIFICATION_ID, Notification.Builder(context, "status")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("Standby Clock stopped")
                .setContentText("Tap, then switch Standby Clock off and on to restart it")
                .setContentIntent(PendingIntent.getActivity(context, 1, restartIntent(listener), PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .build())
        }
    }
}
