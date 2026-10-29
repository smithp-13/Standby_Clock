package com.standby.clock

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout

class MainActivity : Activity() {

    private lateinit var startButton: Button
    private lateinit var stopButton: Button

    // Binding and unbinding finish asynchronously; the service reports when it actually connects or stops.
    private val stateChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            textSize = 18f
            setOnClickListener { onClick() }
        }

        startButton = button("Start") { start() }
        stopButton = button("Stop") {
            SensorService.instance?.requestUnbind()
            show(running = false)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            for (b in listOf(
                startButton,
                stopButton,
                button("Preview") {
                    startActivity(Intent(this@MainActivity, ClockActivity::class.java)
                        .putExtra(ClockActivity.EXTRA_PREVIEW, true))
                },
                button("Share log") { share() },
                button("Clear log") { EventLog.clear(this@MainActivity) },
            )) addView(b, LinearLayout.LayoutParams(dp(220), -2).apply { topMargin = dp(12) })
        })
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(stateChanged, IntentFilter(SensorService.ACTION_STATE_CHANGED), RECEIVER_NOT_EXPORTED)
        refresh()
    }

    override fun onPause() {
        unregisterReceiver(stateChanged)
        super.onPause()
    }

    /** Start asks for one missing permission per tap, in the order the clock needs them, then binds. */
    private fun start() {
        val nm = getSystemService(NotificationManager::class.java)
        val listener = ComponentName(this, SensorService::class.java)
        when {
            !nm.isNotificationListenerAccessGranted(listener) ->
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent() ->
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            Build.VERSION.SDK_INT >= 33 &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED ->
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            // The listener runs once notification access is granted; Start re-binds it after a Stop.
            else -> {
                NotificationListenerService.requestRebind(listener)
                show(running = true)
            }
        }
    }

    private fun refresh() = show(SensorService.instance != null)

    /** Only one of Start/Stop is usable at a time, so the buttons show whether standby is running. */
    private fun show(running: Boolean) {
        startButton.isEnabled = !running
        stopButton.isEnabled = running
    }

    private fun share() {
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, EventLog.read(this)),
            "Export log"
        ))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
