package com.standby.clock

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

// Runs as a notification listener rather than a foreground service: the system keeps a granted
// listener bound without a persistent notification, and the clock needs notification access anyway.
class SensorService : NotificationListenerService(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var landscape = false
    private val handler = Handler(Looper.getMainLooper())
    // After a double tap the clock comes back once on its own, even if the lock screen timed out meanwhile.
    private val returnClock = Runnable { if (standbyReady(sensorTurned = true)) showClock() }
    // Locked, sideways and left alone on the lock screen for 5 s: show the clock (never wakes a dark screen).
    private val idleShow = Runnable { if (standbyReady()) showClock() }
    private var lockChecks = 0
    // After the screen goes off the device only locks once its lock delay passes; check once a second.
    private val awaitLock = object : Runnable {
        override fun run() {
            if (standbyReady()) showClock() else if (++lockChecks < LOCK_WAIT_MS / LOCK_POLL_MS) handler.postDelayed(this, LOCK_POLL_MS)
        }
    }
    private val returnWakeLock by lazy {
        getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "standby:return")
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            log("${intent.action?.substringAfterLast('.')} ${state()}")
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> scheduleIdleShow()
                Intent.ACTION_POWER_CONNECTED -> if (standbyReady()) showClock()
                // A charging phone that times out may start the screen saver instead of turning the screen off
                // (the M34 does), so the screen saver starting counts as the timeout too.
                Intent.ACTION_SCREEN_OFF, Intent.ACTION_DREAMING_STARTED -> if (!landscape || !pluggedIn()) {
                    handler.removeCallbacks(idleShow)
                } else {
                    // Charging and sideways and idle until the timeout: lock now instead of waiting out the
                    // device's auto-lock delay, so standby starts straight away.
                    if (!getSystemService(KeyguardManager::class.java).isKeyguardLocked && LockAdmin.isActive(context)) {
                        getSystemService(DevicePolicyManager::class.java).lockNow()
                        log("locked for standby")
                    }
                    lockChecks = 0
                    handler.removeCallbacks(awaitLock)
                    returnWakeLock.acquire(LOCK_WAIT_MS + 1_000)
                    handler.post(awaitLock)
                }
                Intent.ACTION_USER_PRESENT -> {
                    handler.removeCallbacks(returnClock)
                    handler.removeCallbacks(idleShow)
                    handler.removeCallbacks(awaitLock)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensorManager = getSystemService(SensorManager::class.java)
    }

    override fun onListenerConnected() {
        release() // a rebind can connect again without a disconnect; never register twice
        if (!ClockActivity.showing) setStandbyQuiet(false) // undo DND left on by a process that died mid-standby
        instance = this
        BindWatchdog.schedule(this)
        getSystemService(NotificationManager::class.java).cancel(BindWatchdog.RESTART_NOTIFICATION_ID)
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
        registerSensors()
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_DREAMING_STARTED)
        })
    }

    override fun onListenerDisconnected() {
        runCatching { setStandbyQuiet(false) }
        release()
        sendBroadcast(Intent(ACTION_STATE_CHANGED).setPackage(packageName))
        log("service stopped")
    }

    override fun onDestroy() {
        release()
        super.onDestroy()
    }

    // Persisted, so if the process dies while the clock is up (update, low memory) the next connection restores DND.
    private var filterBeforeStandby: Int?
        get() = getSharedPreferences("standby", MODE_PRIVATE).getInt(KEY_FILTER_BEFORE, -1).takeIf { it >= 0 }
        set(value) = getSharedPreferences("standby", MODE_PRIVATE).edit().apply {
            if (value == null) remove(KEY_FILTER_BEFORE) else putInt(KEY_FILTER_BEFORE, value)
        }.apply()

    /**
     * While the lock-screen clock is up, ask for Do Not Disturb (priority only: the user's DND exceptions such
     * as alarms still apply) so other apps' banners don't cover it; new notifications still show as icons.
     * The previous filter is restored, and only if standby was the one that changed it.
     */
    fun setStandbyQuiet(quiet: Boolean) {
        if (quiet && filterBeforeStandby == null) {
            val before = currentInterruptionFilter
            if (before == INTERRUPTION_FILTER_ALL) {
                filterBeforeStandby = before
                requestInterruptionFilter(INTERRUPTION_FILTER_PRIORITY)
                log("standby quiet on")
            }
        } else if (!quiet) {
            filterBeforeStandby?.let { requestInterruptionFilter(it); log("standby quiet off") }
            filterBeforeStandby = null
        }
    }

    private fun release() {
        instance = null
        handler.removeCallbacks(returnClock)
        handler.removeCallbacks(idleShow)
        handler.removeCallbacks(awaitLock)
        sensorManager.unregisterListener(this)
        runCatching { unregisterReceiver(screenReceiver) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = notificationsChanged(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification) = notificationsChanged(sbn)

    // Ongoing ones (download progress, media) update many times a second and never show on the clock.
    private fun notificationsChanged(sbn: StatusBarNotification) {
        if (!sbn.isClearable || sbn.packageName == packageName) return
        sendBroadcast(Intent(ACTION_NOTIFICATIONS_CHANGED).setPackage(packageName))
    }

    /** Clears every clearable notification [pkg] has, group summaries included, so the app leaves the panel. */
    fun clearApp(pkg: String) {
        val keys = activeNotifications.orEmpty().filter { it.packageName == pkg && it.isClearable }.map { it.key }
        cancelNotifications(keys.toTypedArray())
        log("notifications swiped away $pkg (${keys.size})")
    }

    /** Clearable notifications from other apps, newest first, without group summaries. */
    fun clockNotifications(): List<StatusBarNotification> = activeNotifications.orEmpty()
        .filter {
            it.isClearable && it.packageName != packageName &&
                it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0
        }
        .sortedByDescending { it.postTime }

    private fun registerSensors() {
        for ((name, type) in TARGETS) {
            val sensor = sensorManager.getDefaultSensor(type, true)
            if (sensor == null) {
                log("$name: no wake-up sensor from public API")
                continue
            }
            val ok = sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            log("$name: ${sensor.name} wakeUp=${sensor.isWakeUpSensor} registered=$ok")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        log("EVENT ${event.sensor.stringType.substringAfterLast('.')} " +
            "values=${event.values.joinToString(",") { "%.2f".format(it) }} ${state()}")
        if (event.sensor.type == TYPE_DEVICE_ORIENTATION) {
            landscape = event.values[0].toInt() == 1 || event.values[0].toInt() == 3
            if (!landscape) {
                handler.removeCallbacks(returnClock)
                handler.removeCallbacks(idleShow)
                handler.removeCallbacks(awaitLock)
                hideClock()
            } else {
                // Turned sideways: after 5 s left alone the clock comes up (on battery it may wake the screen once).
                handler.removeCallbacks(returnClock)
                handler.postDelayed(returnClock, IDLE_RETURN_MS)
                returnWakeLock.acquire(IDLE_RETURN_MS + 1_000)
            }
        }
    }

    private fun scheduleIdleShow() {
        handler.removeCallbacks(idleShow)
        if (landscape && !ClockActivity.showing) handler.postDelayed(idleShow, IDLE_RETURN_MS)
    }

    /** The user's own Do Not Disturb is on (not the one standby turns on while the clock shows). */
    fun userDnd() = currentInterruptionFilter != INTERRUPTION_FILTER_ALL && filterBeforeStandby == null

    override fun onInterruptionFilterChanged(interruptionFilter: Int) {
        sendBroadcast(Intent(ACTION_NOTIFICATIONS_CHANGED).setPackage(packageName)) // the clock re-checks userDnd
    }

    /**
     * Sideways with the lock screen up. During the lock delay after the screen goes off there is no lock
     * screen yet (waking goes straight back to the app), so the clock waits for it; isDeviceLocked is not
     * used because Smart Lock / Extend Unlock reports a trusted phone as unlocked even on the lock screen.
     * On power the clock may turn the screen on. On battery it does so only when the sensor reports the
     * phone being turned sideways; screen-off and timers never wake it.
     */
    private fun standbyReady(sensorTurned: Boolean = false): Boolean {
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val screenOn = getSystemService(PowerManager::class.java).isInteractive
        return landscape && locked && (screenOn || sensorTurned || pluggedIn())
    }

    /** Called on double tap: the lock screen shows now, and the clock returns if the phone stays idle. */
    fun clockDismissed() {
        handler.removeCallbacks(returnClock)
        handler.postDelayed(returnClock, IDLE_RETURN_MS)
        returnWakeLock.acquire(IDLE_RETURN_MS + 1_000)
        log("clock dismissed")
    }

    private fun pluggedIn(): Boolean {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    }

    private fun showClock() {
        if (ClockActivity.showing) return
        val nm = getSystemService(NotificationManager::class.java)
        // A full-screen intent only launches for a new post, not an update of one still showing (e.g. left over
        // from a process that died before the clock cancelled it), so always post it fresh.
        nm.cancel(CLOCK_NOTIFICATION_ID)
        nm.createNotificationChannel(
            NotificationChannel(CLOCK_CHANNEL_ID, "Standby clock", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, ClockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(CLOCK_NOTIFICATION_ID, Notification.Builder(this, CLOCK_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Standby clock")
            .setContentIntent(open)
            // The user's own Do Not Disturb blocks ordinary notifications, full-screen intent included; alarms
            // pass by default, so the clock (an alarm-clock style screen) still opens.
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(open, true)
            .build())
        log("clock requested fullScreenAllowed=${android.os.Build.VERSION.SDK_INT < 34 || nm.canUseFullScreenIntent()}")
    }

    private fun hideClock() {
        getSystemService(NotificationManager::class.java).cancel(CLOCK_NOTIFICATION_ID)
        sendBroadcast(Intent(ACTION_HIDE_CLOCK).setPackage(packageName))
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private fun state(): String {
        val pm = getSystemService(PowerManager::class.java)
        val bm = getSystemService(BatteryManager::class.java)
        return "interactive=${pm.isInteractive} charging=${bm.isCharging} " +
            "idle=${pm.isDeviceIdleMode} battery=${bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)}"
    }

    private fun log(line: String) = EventLog.append(this, line)

    companion object {
        const val ACTION_HIDE_CLOCK = "com.standby.clock.HIDE_CLOCK"
        const val ACTION_NOTIFICATIONS_CHANGED = "com.standby.clock.NOTIFICATIONS_CHANGED"
        const val CLOCK_NOTIFICATION_ID = 2
        private const val IDLE_RETURN_MS = 5_000L
        private const val KEY_FILTER_BEFORE = "filter_before_standby"
        const val ACTION_STATE_CHANGED = "com.standby.clock.STATE_CHANGED"
        private const val LOCK_WAIT_MS = 30_000L // longest common lock delay after screen-off
        private const val LOCK_POLL_MS = 250L
        private const val CLOCK_CHANNEL_ID = "clock"
        // AOSP @hide value: Sensor.TYPE_DEVICE_ORIENTATION = 27. It is the only sensor the trigger uses;
        // the prototype's tilt detector woke the CPU on every tilt for nothing.
        private const val TYPE_DEVICE_ORIENTATION = 27
        private val TARGETS = listOf("device_orientation" to TYPE_DEVICE_ORIENTATION)

        /** Set while the system has the listener bound; null otherwise. */
        var instance: SensorService? = null
            private set
    }
}
