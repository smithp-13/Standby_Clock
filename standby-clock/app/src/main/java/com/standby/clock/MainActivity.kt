package com.standby.clock

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Setup and settings: standby status, the setup checklist, the theme picker, Preview and the event log. */
class MainActivity : Activity() {

    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusText: TextView
    private lateinit var actionButton: TextView
    private lateinit var stepList: LinearLayout
    private val themeCards = mutableMapOf<Theme, LinearLayout>()

    private val nunito by lazy { resources.getFont(R.font.nunito) }
    private val listener by lazy { ComponentName(this, SensorService::class.java) }
    private val nm by lazy { getSystemService(NotificationManager::class.java) }

    // Binding and unbinding finish asynchronously; the service reports when it actually connects or stops.
    private val stateChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = refresh()
    }

    /** One permission standby needs: whether it is granted, and the button that grants it. */
    private class Step(val title: String, val detail: String, val action: String, val done: () -> Boolean, val open: () -> Unit)

    // In the order the clock needs them; the stepper and Continue setup always go to the first one still missing.
    private val steps by lazy {
        listOfNotNull(
            Step("Notification access", "Runs standby and shows your notifications on the clock", "Open settings",
                { nm.isNotificationListenerAccessGranted(listener) }) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            if (Build.VERSION.SDK_INT >= 34) Step("Full-screen alerts", "Lets the clock open over the lock screen", "Allow",
                { nm.canUseFullScreenIntent() }) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:$packageName")))
            } else null,
            if (Build.VERSION.SDK_INT >= 33) Step("Notifications", "Needed to launch the clock", "Allow",
                { checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED }) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
            } else null,
            Step("Screen lock", "Locks a charging phone at timeout so the clock shows at once", "Activate",
                { LockAdmin.isActive(this) }) {
                startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, LockAdmin.component(this))
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        "Lets Standby Clock lock the screen when a charging phone times out, so the clock appears immediately."))
            },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }
        page.addView(text("Standby Clock", 30f, Color.WHITE, bold = true))
        page.addView(text("A big clock on the lock screen whenever the phone lies sideways.", 15f, MUTED)
            .apply { setPadding(0, dp(4), 0, 0) })

        page.addView(section("Status"))
        page.addView(card().apply {
            addView(LinearLayout(this@MainActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                statusDot = View(this@MainActivity)
                addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(10) })
                statusTitle = text("", 19f, Color.WHITE, bold = true)
                addView(statusTitle)
            })
            statusText = text("", 14f, MUTED).apply { setPadding(0, dp(6), 0, dp(14)) }
            addView(statusText)
            actionButton = button("", filled = true) { onAction() }
            addView(actionButton, LinearLayout.LayoutParams(-1, dp(48)))
        })

        page.addView(section("Setup"))
        stepList = card().apply { setPadding(dp(18), dp(18), dp(18), dp(18)) }
        page.addView(stepList)

        page.addView(section("Theme"))
        page.addView(LinearLayout(this).apply {
            for ((i, theme) in Theme.entries.withIndex()) {
                val themeCard = themeCard(theme)
                themeCards[theme] = themeCard
                addView(themeCard, LinearLayout.LayoutParams(0, -2, 1f).apply { if (i > 0) marginStart = dp(12) })
            }
        })
        page.addView(button("Preview the clock", filled = false) {
            startActivity(Intent(this, ClockActivity::class.java).putExtra(ClockActivity.EXTRA_PREVIEW, true))
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(14) })
        // Plays the chosen theme's ending: the Mario flagpole and castle, or the Pac-Man maze clearing and flashing.
        page.addView(button("Preview: charging complete", filled = false) {
            startActivity(Intent(this, ClockActivity::class.java).putExtra(ClockActivity.EXTRA_PREVIEW_COMPLETE, true))
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(10) })

        page.addView(section("Event log"))
        page.addView(LinearLayout(this).apply {
            addView(button("Share log", filled = false) { share() }, LinearLayout.LayoutParams(0, dp(44), 1f))
            addView(button("Clear log", filled = false) {
                EventLog.clear(this@MainActivity)
                Toast.makeText(this@MainActivity, "Log cleared", Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(12) })
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(BACKGROUND)
            isFillViewport = true
            addView(page)
            // Android 15+ draws the app edge to edge: keep the page clear of the status and navigation bars.
            setOnApplyWindowInsetsListener { v, insets ->
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(WindowInsets.Type.systemBars())
                    v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                } else @Suppress("DEPRECATION") {
                    v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                        insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                }
                insets
            }
        })
        BindWatchdog.schedule(this)
    }

    // RECEIVER_NOT_EXPORTED is a plain flag that Android ignores before API 33, so it is passed on every version.
    @android.annotation.SuppressLint("InlinedApi")
    override fun onResume() {
        super.onResume()
        registerReceiver(stateChanged, IntentFilter(SensorService.ACTION_STATE_CHANGED), RECEIVER_NOT_EXPORTED)
        refresh()
    }

    override fun onPause() {
        unregisterReceiver(stateChanged)
        super.onPause()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) =
        refresh()

    /** The status card's button: the next setup step, Start once set up, Stop while running. */
    private fun onAction() {
        val missing = steps.firstOrNull { !it.done() }
        when {
            missing != null -> missing.open()
            SensorService.instance != null -> {
                BindWatchdog.setStoppedByUser(this, true)
                SensorService.instance?.requestUnbind()
                refresh()
            }
            // The listener runs once notification access is granted; Start re-binds it after a Stop or a drop.
            else -> {
                BindWatchdog.setStoppedByUser(this, false)
                BindWatchdog.ensureBound(this, "start")
                // If One UI won't re-bind it, take the user straight to the off/on switch that does.
                actionButton.postDelayed({
                    if (SensorService.instance == null) {
                        Toast.makeText(this, "Switch Standby Clock off and on to restart it", Toast.LENGTH_LONG).show()
                        startActivity(BindWatchdog.restartIntent(listener))
                    }
                }, 3_500)
            }
        }
    }

    private fun refresh() {
        val left = steps.count { !it.done() }
        val next = steps.indexOfFirst { !it.done() }
        val running = SensorService.instance != null
        val (color, title, detail) = when {
            left > 0 -> Triple(AMBER, "Setup needed", "Step ${next + 1} of ${steps.size}: ${steps[next].title}.")
            running -> Triple(GREEN, "Running", "Turn the phone sideways on the lock screen to see the clock.")
            else -> Triple(MUTED, "Stopped", "Standby is off. Start it to show the clock on the lock screen.")
        }
        statusDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
        statusTitle.text = title
        statusText.text = detail
        actionButton.text = when {
            left > 0 -> "Continue setup"
            running -> "Stop"
            else -> "Start"
        }

        stepList.removeAllViews()
        for ((i, step) in steps.withIndex()) {
            stepList.addView(stepRow(step, i + 1, active = i == next, last = i == steps.lastIndex))
        }
        val current = Theme.current(this)
        for ((theme, view) in themeCards) view.background = outline(if (theme == current) ACCENT else LINE, theme == current)
    }

    /**
     * One step of the vertical stepper: a numbered circle joined to the next by a line. Done steps show a tick,
     * the first missing one is active (accent number and its own button), later missing ones wait their turn.
     */
    private fun stepRow(step: Step, number: Int, active: Boolean, last: Boolean) = LinearLayout(this).apply {
        val done = step.done()
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(TextView(this@MainActivity).apply {
                text = if (done) "✓" else number.toString()
                gravity = Gravity.CENTER
                textSize = 14f
                typeface = Typeface.create(nunito, 900, false)
                setTextColor(if (done || active) BACKGROUND else MUTED)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    when {
                        done -> setColor(GREEN)
                        active -> setColor(ACCENT)
                        else -> setStroke(dp(2), LINE)
                    }
                }
            }, LinearLayout.LayoutParams(dp(30), dp(30)))
            // The connector to the next step: green once this step is done.
            if (!last) addView(View(this@MainActivity).apply { setBackgroundColor(if (done) GREEN else LINE) },
                LinearLayout.LayoutParams(dp(2), 0, 1f).apply { topMargin = dp(4); bottomMargin = dp(4) })
        }, LinearLayout.LayoutParams(dp(30), -1).apply { marginEnd = dp(14) })
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(3), 0, if (last) 0 else dp(20))
            addView(text(step.title, 16f, if (active) Color.WHITE else MUTED, bold = true))
            addView(text(if (done) "Done" else step.detail, 13f, if (done) GREEN else MUTED))
            if (active) addView(button(step.action, filled = true) { step.open() }.apply { setPadding(dp(22), 0, dp(22), 0) },
                LinearLayout.LayoutParams(-2, dp(40)).apply { topMargin = dp(10) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    /** A selectable card with a drawn preview of [theme]. */
    private fun themeCard(theme: Theme) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(10))
        isClickable = true
        foreground = RippleDrawable(ColorStateList.valueOf(Color.argb(40, 255, 255, 255)), null,
            GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(18).toFloat() })
        setOnClickListener {
            Theme.set(this@MainActivity, theme)
            refresh()
        }
        addView(FrameLayout(this@MainActivity).apply {
            clipToOutline = true
            background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(Color.BLACK) }
            addView(ThemePreview(this@MainActivity, theme))
        }, LinearLayout.LayoutParams(-1, dp(84)))
        addView(text(theme.label, 15f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
    }

    /** Miniature of each clock face, drawn in code so it always matches the real colours. */
    private class ThemePreview(context: Context, private val theme: Theme) : View(context) {
        private val d = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val r = RectF()

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            if (theme == Theme.PACMAN) {
                canvas.drawColor(Color.BLACK)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * d; paint.color = Color.rgb(0x2B, 0x3B, 0xFF)
                r.set(5 * d, 5 * d, w - 5 * d, h - 5 * d)
                canvas.drawRoundRect(r, 10 * d, 10 * d, paint)
                paint.style = Paint.Style.FILL; paint.color = Color.rgb(0xFF, 0xF1, 0xE8)
                var x = w * 0.42f
                while (x < w - 16 * d) { canvas.drawCircle(x, h - 15 * d, 1.6f * d, paint); x += 9 * d }
                paint.color = Color.rgb(0xFF, 0xD4, 0x00)
                r.set(w * 0.22f, h - 23 * d, w * 0.22f + 16 * d, h - 7 * d)
                canvas.drawArc(r, 35f, 290f, true, paint)
                text.typeface = context.resources.getFont(R.font.nunito)
                text.textSize = h * 0.42f; text.color = Color.rgb(0xFF, 0xD4, 0x00)
                text.isFakeBoldText = true
                canvas.drawText("12:30", w / 2, h * 0.56f, text)
            } else {
                canvas.drawColor(Color.rgb(0x5C, 0x94, 0xFC))
                val brick = h * 0.16f
                paint.color = Color.rgb(0xC8, 0x4C, 0x0C)
                canvas.drawRect(0f, h - 2 * brick, w, h, paint)
                paint.color = Color.BLACK
                for (row in 0..1) {
                    val y = h - (2 - row) * brick
                    canvas.drawRect(0f, y, w, y + d, paint)
                    var x = if (row == 0) 0f else -brick
                    while (x < w) { canvas.drawRect(x, y, x + d, y + brick, paint); x += brick * 2 }
                }
                val size = h * 0.38f
                text.typeface = context.resources.getFont(R.font.press_start_2p)
                text.textSize = size * 0.36f
                for ((i, value) in listOf("12", "30").withIndex()) {
                    val left = w / 2 + (if (i == 0) -size - 3 * d else 3 * d)
                    val top = h * 0.12f
                    paint.color = Color.BLACK
                    canvas.drawRect(left - d, top - d, left + size + d, top + size + d, paint)
                    paint.color = Color.rgb(0xF8, 0xB8, 0x00)
                    canvas.drawRect(left, top, left + size, top + size, paint)
                    text.color = Theme.marioInk(context)
                    canvas.drawText(value, left + size / 2, top + size / 2 + text.textSize / 2, text)
                }
            }
        }
    }

    private fun section(title: String) = text(title.uppercase(), 12f, MUTED, bold = true).apply {
        letterSpacing = 0.08f
        setPadding(dp(4), dp(26), 0, dp(10))
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(16), dp(18), dp(18))
        background = GradientDrawable().apply { setColor(SURFACE); cornerRadius = dp(20).toFloat() }
    }

    private fun outline(color: Int, selected: Boolean) = GradientDrawable().apply {
        setColor(SURFACE)
        cornerRadius = dp(18).toFloat()
        setStroke(dp(if (selected) 2 else 1), color)
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        ellipsize = TextUtils.TruncateAt.END
        typeface = Typeface.create(nunito, if (bold) 800 else 500, false)
    }

    /** A rounded button: filled in the accent colour, or a quiet outlined one. */
    private fun button(label: String, filled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 15f
        gravity = Gravity.CENTER
        isAllCaps = false
        typeface = Typeface.create(nunito, 800, false)
        setTextColor(if (filled) BACKGROUND else Color.WHITE)
        val shape = GradientDrawable().apply {
            cornerRadius = dp(24).toFloat()
            if (filled) setColor(ACCENT) else { setColor(SURFACE); setStroke(dp(1), LINE) }
        }
        background = RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)), shape, null)
        setOnClickListener { onClick() }
    }

    private fun share() {
        startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, EventLog.read(this)),
            "Export log"
        ))
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        val BACKGROUND = Color.rgb(0x0E, 0x0F, 0x13)
        val SURFACE = Color.rgb(0x1B, 0x1D, 0x24)
        val LINE = Color.rgb(0x33, 0x36, 0x40)
        val MUTED = Color.rgb(0x9A, 0x9F, 0xAD)
        val ACCENT = Color.rgb(0xFF, 0xD4, 0x00) // the arcade yellow, shared with the clock
        val GREEN = Color.rgb(0x34, 0xC7, 0x59)
        val AMBER = Color.rgb(0xFF, 0x9F, 0x0A)
    }
}
