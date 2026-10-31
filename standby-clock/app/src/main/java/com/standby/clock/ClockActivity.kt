package com.standby.clock

import android.animation.LayoutTransition
import android.app.Activity
import android.app.ActivityOptions
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.BatteryManager
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.text.TextUtils
import android.transition.TransitionManager
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.sign

class ClockActivity : Activity() {

    private lateinit var icons: LinearLayout
    private lateinit var card: LinearLayout
    private lateinit var cardIcon: ImageView
    private lateinit var cardApp: TextView
    private lateinit var cardTitle: TextView
    private lateinit var cardText: TextView
    private lateinit var doubleTap: GestureDetector
    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var arcade: ArcadeView
    private lateinit var battery: BatteryView
    private lateinit var clock: StandbyClockView
    private lateinit var mario: MarioSceneView
    private lateinit var date: TextClock
    private lateinit var genie: GenieView
    private var iconSize = 22
    private var cardPackage: String? = null
    private var cardNotification: StatusBarNotification? = null
    private var opening = false
    /** Opened from the app's Preview button: may show unlocked, and only closes on double tap or Back. */
    private var preview = false
    /** Opened from the app's "Preview: charging complete" button: plays the end-of-charge ending of the chosen theme. */
    private var previewComplete = false
    private var charged = false
    private var swipeStartX = 0f
    private var swiping = false

    private val closer = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { if (!opening && !preview) finish() }
    }
    private val unlocked = object : BroadcastReceiver() {
        // While a notification is being opened, the unlock it triggers must not close us first.
        override fun onReceive(context: Context, intent: Intent) { if (!opening && !preview) finish() }
    }
    private val power = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!previewComplete) applyPower(intent.action == Intent.ACTION_POWER_CONNECTED, animate = true)
        }
    }
    private val batteryChanged = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { if (!previewComplete) applyCharge(intent) }
    }
    private val refreshIcons = Runnable { showIcons() }
    private val notificationsChanged = object : BroadcastReceiver() {
        // Apps often post several updates at once; rebuild the row once they settle.
        override fun onReceive(context: Context, intent: Intent) {
            icons.removeCallbacks(refreshIcons)
            icons.postDelayed(refreshIcons, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        previewComplete = intent.getBooleanExtra(EXTRA_PREVIEW_COMPLETE, false)
        preview = intent.getBooleanExtra(EXTRA_PREVIEW, false) || previewComplete
        window.setBackgroundDrawableResource(android.R.color.black)
        // Lay out into the camera cutout too, so the arcade's outer wall can bend around the punch-hole.
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        doubleTap = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!preview) SensorService.instance?.clockDismissed()
                finish()
                return true
            }
        })
        val nunito = resources.getFont(R.font.nunito)
        icons = LinearLayout(this).apply { layoutTransition = LayoutTransition() }
        cardIcon = ImageView(this)
        cardApp = TextView(this).apply { textSize = 13f; setTextColor(Color.argb(170, 255, 255, 255)) }
        cardTitle = TextView(this).apply {
            textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.create(nunito, 800, false)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        cardText = TextView(this).apply {
            textSize = 14f; setTextColor(Color.argb(220, 255, 255, 255))
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
        }
        card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setOnClickListener { cardNotification?.let { open(it) } }
            setOnTouchListener { v, e -> swipeCard(v, e) }
            setPadding(dp(16), dp(12), dp(16), dp(14))
            background = GradientDrawable().apply { setColor(materialSurface()); cornerRadius = dp(22).toFloat() }
            addView(LinearLayout(this@ClockActivity).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(cardIcon, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(8) })
                addView(cardApp)
            })
            addView(cardTitle, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            addView(cardText)
        }
        arcade = ArcadeView(this)
        battery = BatteryView(this)
        clock = StandbyClockView(this)
        mario = MarioSceneView(this)
        date = TextClock(this).apply {
            format12Hour = "EEEE d MMMM"
            format24Hour = "EEEE d MMMM"
            typeface = Typeface.create(nunito, 800, false)
            setTextColor(Color.argb(200, 255, 140, 130))
        }
        // Everything but the arcade frame; it moves inside the frame's inner wall while charging.
        content = FrameLayout(this).apply {
            addView(clock)
            addView(date)
            addView(battery,
                FrameLayout.LayoutParams(dp(96), dp(16), Gravity.TOP or Gravity.END).apply {
                    topMargin = dp(18); marginEnd = dp(28)
                })
            addView(icons, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = dp(12) })
            addView(card, FrameLayout.LayoutParams(dp(380), -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
                .apply { bottomMargin = dp(48) })
        }
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f
            setOnClickListener { hideCard() }
            // The Mario theme extends edge-to-edge behind the arcade walls and content.
            addView(mario, FrameLayout.LayoutParams(-1, -1))
            addView(content)
            addView(arcade)
            genie = GenieView(this@ClockActivity)
            addView(genie)
        }
        setContentView(root)
        // The Mario icons follow the ground, which is only known once the scene has its size.
        mario.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) root.post { positionIcons() }
        }
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        applyPower(plugged != 0 || previewComplete, animate = false)
        if (!previewComplete) battery?.let { applyCharge(it) } else playFinale()
        revealIfLandscape(resources.configuration)
        register(closer, IntentFilter(SensorService.ACTION_HIDE_CLOCK))
        // USER_PRESENT comes from SystemUI, an app uid, which a not-exported receiver silently drops. It is a
        // protected broadcast, so only the system side can send it and exporting this receiver is safe.
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(unlocked, IntentFilter(Intent.ACTION_USER_PRESENT), RECEIVER_EXPORTED)
        else registerReceiver(unlocked, IntentFilter(Intent.ACTION_USER_PRESENT))
        register(batteryChanged, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        register(power, IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        })
        register(notificationsChanged, IntentFilter(SensorService.ACTION_NOTIFICATIONS_CHANGED))
        showIcons()
    }

    override fun onStart() {
        super.onStart()
        showing = true
        getSystemService(NotificationManager::class.java).cancel(SensorService.CLOCK_NOTIFICATION_ID)
        applyTheme(Theme.current(this))
        if (!preview) SensorService.instance?.setStandbyQuiet(true)
    }

    private var theme = Theme.PACMAN

    private fun applyTheme(value: Theme) {
        theme = value
        arcade.theme = value
        clock.visibility = if (value == Theme.MARIO) View.GONE else View.VISIBLE
        mario.visibility = if (value == Theme.MARIO) View.VISIBLE else View.GONE
        date.typeface = if (value == Theme.MARIO) resources.getFont(R.font.press_start_2p)
        else Typeface.create(resources.getFont(R.font.nunito), 800, false)
        // Pac-Man: the arcade's lilac, as on the "95% IN" line.
        date.setTextColor(if (value == Theme.MARIO) Theme.marioInk(this) else Color.rgb(0x8F, 0x98, 0xFF))
        applyPower(plugged, animate = false)
        applyDnd()
    }

    private var plugged = false

    /** Press Start 2P is much wider than Nunito, so the Mario date uses a smaller size. */
    private fun applyDateSize() {
        date.textSize = when {
            theme == Theme.MARIO && plugged -> 9f
            theme == Theme.MARIO -> 13f
            plugged -> 16f
            else -> 22f
        }
    }

    // The rotation from the portrait lock screen is handled in place (configChanges in the manifest)
    // instead of destroying and rebuilding the clock, which cost ~190 ms on the M34.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        revealIfLandscape(newConfig)
    }

    /** Launched over the portrait lock screen, the window starts upright; stay black until it is sideways (or always in preview). */
    private fun revealIfLandscape(config: Configuration) {
        if ((preview || config.orientation == Configuration.ORIENTATION_LANDSCAPE) && root.alpha == 0f) {
            root.animate().alpha(1f).setDuration(150).start()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        previewComplete = intent.getBooleanExtra(EXTRA_PREVIEW_COMPLETE, false)
        preview = intent.getBooleanExtra(EXTRA_PREVIEW, false) || previewComplete
        if (previewComplete) {
            applyTheme(Theme.current(this))
            applyPower(true, animate = false)
            playFinale()
        }
    }

    // Standby is for the locked screen only. USER_PRESENT reached this activity ~6 s after a fingerprint
    // unlock on the M34 (Android 16 defers it), so while visible the clock also checks the lock itself.
    private val lockWatch = object : Runnable {
        override fun run() {
            if (!preview && !opening && !getSystemService(KeyguardManager::class.java).isKeyguardLocked) finish()
            else root.postDelayed(this, 300)
        }
    }

    override fun onResume() {
        super.onResume()
        lockWatch.run()
    }

    override fun onPause() {
        root.removeCallbacks(lockWatch)
        super.onPause()
    }

    override fun onStop() {
        showing = false
        SensorService.instance?.setStandbyQuiet(false)
        super.onStop()
    }

    // Re-hide on every focus gain: over the keyguard the system restores the bars after the first hide.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && Build.VERSION.SDK_INT >= 30) window.insetsController?.apply {
            hide(WindowInsets.Type.systemBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Taps on the Mario ? blocks punch them; tapping those quickly must not count as the closing double tap.
        if (mario.visibility == View.VISIBLE && mario.blockAt(ev.x, ev.y) != null) return super.dispatchTouchEvent(ev)
        return doubleTap.onTouchEvent(ev) || super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        icons.removeCallbacks(refreshIcons)
        unregisterReceiver(closer)
        unregisterReceiver(unlocked)
        unregisterReceiver(power)
        unregisterReceiver(batteryChanged)
        unregisterReceiver(notificationsChanged)
        super.onDestroy()
    }

    private fun register(receiver: BroadcastReceiver, filter: IntentFilter) {
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, filter)
    }

    /**
     * The phone has stopped charging while still plugged in: it reports full, or its own charge limit cut in (Samsung's
     * Protect battery stops at a set percent and reports "not charging"). Below [CAPPED_FROM] percent a "not charging"
     * report is a temporary pause (heat), not the end.
     */
    private fun applyCharge(battery: Intent) {
        val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 /
            battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val done = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0 && (
            status == BatteryManager.BATTERY_STATUS_FULL || level >= 100 ||
                status == BatteryManager.BATTERY_STATUS_NOT_CHARGING && level >= CAPPED_FROM)
        if (done != charged) {
            charged = done
            EventLog.append(this, "charge complete=$done status=$status level=$level")
        }
        mario.complete = done
        arcade.complete = done
    }

    /** Restarts the finale from the beginning, for the Preview button. */
    private fun playFinale() {
        mario.complete = false
        arcade.complete = false
        root.postDelayed({ mario.complete = true; arcade.complete = true }, 1_200)
    }

    /**
     * Charging: arcade frame, clock moved inside its inner wall, screen kept on. On battery: the simple clock,
     * and the screen times out as usual so standby never drains it.
     */
    private fun applyPower(plugged: Boolean, animate: Boolean) {
        if (animate) TransitionManager.beginDelayedTransition(root)
        if (plugged) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        arcade.visibility = if (plugged) View.VISIBLE else View.GONE
        battery.visibility = if (plugged) View.GONE else View.VISIBLE
        // Inside the arcade frame space is tight: date goes small to the top left and the icons shrink,
        // so the digits can grow.
        this.plugged = plugged
        mario.plugged = plugged
        applyDateSize()
        // Mario has no wall: the date goes in the top left corner, opposite the charging info.
        val isMario = theme == Theme.MARIO
        date.layoutParams = FrameLayout.LayoutParams(-2, -2,
            Gravity.TOP or if (plugged || isMario) Gravity.START else Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(if (isMario) 20 else if (plugged) 4 else 12)
            marginStart = dp(if (isMario) 24 else if (plugged) 8 else 0)
        }
        clock.heightFactor = if (plugged) 1.08f else 0.9f
        iconSize = if (isMario) 20 else if (plugged) 16 else 22
        for (i in 0 until icons.childCount) icons.getChildAt(i).layoutParams = iconParams()
        positionIcons()
        val inset = if (plugged && !isMario) dp(64) else 0
        content.layoutParams = FrameLayout.LayoutParams(-1, -1).apply { setMargins(inset, inset, inset, inset) }
    }

    /** Mario: the icons sit in the first row of ground bricks, the card just above them. Otherwise the bottom edge. */
    private fun positionIcons() {
        val bottom = if (theme == Theme.MARIO && mario.height > 0)
            (mario.height - mario.brickRowCenter() - dp(iconSize) / 2f).toInt()
        else dp(if (plugged) 4 else 12)
        icons.layoutParams = (icons.layoutParams as FrameLayout.LayoutParams).apply { bottomMargin = bottom }
        card.layoutParams = (card.layoutParams as FrameLayout.LayoutParams).apply {
            bottomMargin = maxOf(dp(48), bottom + dp(iconSize) + dp(12))
        }
    }

    private fun latest(pkg: String) = SensorService.instance?.clockNotifications().orEmpty().firstOrNull { it.packageName == pkg }

    /** Material You colours (Android 12+), with the earlier coral/charcoal as the fallback. */
    private fun materialAccent() =
        if (Build.VERSION.SDK_INT >= 31) getColor(android.R.color.system_accent1_200) else Color.argb(200, 255, 140, 130)

    private fun materialSurface() =
        if (Build.VERSION.SDK_INT >= 31) (getColor(android.R.color.system_neutral1_800) and 0x00FFFFFF) or (235 shl 24)
        else Color.argb(235, 38, 38, 40)

    /** With the user's own Do Not Disturb on, the clock shows just the time (and the arcade when charging). */
    private fun applyDnd() {
        icons.visibility = if (SensorService.instance?.userDnd() == true) View.GONE else View.VISIBLE
        if (icons.visibility == View.GONE) hideCard()
    }

    /** Material-style icon: the notification's own monochrome glyph, tinted; the app icon only as a fallback. */
    private fun glyph(sbn: StatusBarNotification): Drawable? =
        runCatching { sbn.notification.smallIcon?.loadDrawable(createPackageContext(sbn.packageName, 0)) }.getOrNull()
            ?.mutate()?.apply { setTint(Color.argb(230, 255, 255, 255)) }
            ?: runCatching { packageManager.getApplicationIcon(sbn.packageName) }.getOrNull()

    /** One icon per app with an unread notification. Only added/removed icons change, so they animate. */
    private fun showIcons() {
        applyDnd()
        val latest = SensorService.instance?.clockNotifications().orEmpty().distinctBy { it.packageName }
        val packages = latest.map { it.packageName }
        for (i in icons.childCount - 1 downTo 0) if (icons.getChildAt(i).tag !in packages) icons.removeViewAt(i)
        val present = (0 until icons.childCount).map { icons.getChildAt(it).tag }
        for (sbn in latest) {
            val pkg = sbn.packageName
            if (pkg in present) continue
            val icon = glyph(sbn) ?: continue
            icons.addView(ImageView(this).apply {
                tag = pkg
                setImageDrawable(icon)
                setOnClickListener { if (cardPackage == pkg) hideCard() else showCard(pkg) }
            }, iconParams())
        }
        cardPackage?.let { if (it in packages) fillCard(it) else hideCard() }
    }

    private fun iconParams() =
        LinearLayout.LayoutParams(dp(iconSize), dp(iconSize)).apply { marginStart = dp(iconSize / 4); marginEnd = dp(iconSize / 4) }

    private fun showCard(pkg: String) {
        cardPackage = pkg
        fillCard(pkg)
        card.visibility = View.VISIBLE
        card.alpha = 0f
        card.translationX = 0f
        card.translationY = dp(12).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(220).start()
        card.removeCallbacks(autoHide)
        card.postDelayed(autoHide, CARD_TIMEOUT_MS)
    }

    // An opened card goes away by itself after 3 s instead of staying up.
    private val autoHide = Runnable { hideCard() }

    private fun hideCard() {
        card.removeCallbacks(autoHide)
        cardPackage = null
        card.animate().alpha(0f).translationY(dp(12).toFloat()).setDuration(180)
            .withEndAction { if (cardPackage == null) card.visibility = View.GONE }.start()
    }

    private fun fillCard(pkg: String) {
        val sbn = latest(pkg) ?: return hideCard()
        val extras = sbn.notification.extras
        cardIcon.setImageDrawable(glyph(sbn))
        cardApp.text = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)) }
            .getOrDefault(pkg)
        cardTitle.text = extras.getCharSequence(Notification.EXTRA_TITLE)
        cardText.text = extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT)
        cardNotification = sbn
    }

    /** The card follows a sideways drag; past 30% of its width it flies off and clears the notification. */
    private fun swipeCard(v: View, e: MotionEvent): Boolean {
        val dx = e.rawX - swipeStartX
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { swipeStartX = e.rawX; swiping = false; card.removeCallbacks(autoHide) }
            MotionEvent.ACTION_MOVE -> {
                if (!swiping && abs(dx) > ViewConfiguration.get(this).scaledTouchSlop) swiping = true
                if (swiping) {
                    v.translationX = dx
                    v.alpha = 1 - (abs(dx) / v.width).coerceAtMost(1f)
                }
            }
            MotionEvent.ACTION_UP -> when {
                swiping && abs(dx) > v.width * 0.3f -> dismissCard(sign(dx))
                swiping -> {
                    v.animate().translationX(0f).alpha(1f).setDuration(150).start()
                    card.postDelayed(autoHide, CARD_TIMEOUT_MS)
                }
                else -> v.performClick()
            }
            MotionEvent.ACTION_CANCEL -> v.animate().translationX(0f).alpha(1f).setDuration(150).start()
        }
        return true
    }

    /** Pours the swiped card into its app's icon (genie), pops the icon, then clears the app's notifications. */
    private fun dismissCard(direction: Float) {
        val sbn = cardNotification ?: return
        cardPackage = null
        val icon = (0 until icons.childCount).map { icons.getChildAt(it) }.firstOrNull { it.tag == sbn.packageName }
        if (icon == null || card.width == 0) {
            card.animate().translationX(direction * card.width).alpha(0f).setDuration(180).withEndAction {
                card.visibility = View.GONE
                card.translationX = 0f
                SensorService.instance?.clearApp(sbn.packageName)
            }.start()
            return
        }
        val snapshot = Bitmap.createBitmap(card.width, card.height, Bitmap.Config.ARGB_8888)
        card.draw(Canvas(snapshot))
        val source = boundsInRoot(card).apply { offset(card.translationX, 0f) }
        val target = boundsInRoot(icon)
        val alpha = card.alpha
        card.visibility = View.GONE
        card.translationX = 0f
        card.alpha = 1f
        genie.play(snapshot, source, target, alpha) {
            icon.animate().scaleX(1.35f).scaleY(1.35f).setDuration(90).withEndAction {
                icon.animate().scaleX(1f).scaleY(1f).setDuration(110).start()
            }.start()
            SensorService.instance?.clearApp(sbn.packageName)
        }
    }

    /** [view]'s untransformed bounds in root coordinates. */
    private fun boundsInRoot(view: View): RectF {
        val r = IntArray(2).also { root.getLocationInWindow(it) }
        val v = IntArray(2).also { view.getLocationInWindow(it) }
        val left = (v[0] - r[0]).toFloat() - view.translationX
        val top = (v[1] - r[1]).toFloat() - view.translationY
        return RectF(left, top, left + view.width, top + view.height)
    }

    /** Asks for the normal Samsung unlock (fingerprint/PIN), then opens the notification like the shade does. */
    private fun open(sbn: StatusBarNotification) {
        opening = true
        val launch = {
            sbn.notification.contentIntent?.let { pending ->
                val options = ActivityOptions.makeBasic()
                if (Build.VERSION.SDK_INT >= 34) {
                    @Suppress("DEPRECATION")
                    options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }
                runCatching { pending.send(this, 0, null, null, null, null, options.toBundle()) }
            }
            if (sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) SensorService.instance?.cancelNotification(sbn.key)
            finish()
        }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) launch()
        else keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = launch()
            override fun onDismissCancelled() { opening = false }
            override fun onDismissError() { opening = false }
        })
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PREVIEW = "com.standby.clock.PREVIEW"
        const val EXTRA_PREVIEW_COMPLETE = "com.standby.clock.PREVIEW_COMPLETE"
        private const val CAPPED_FROM = 80
        private const val CARD_TIMEOUT_MS = 3_000L

        /** True between onStart and onStop, so the service does not re-post the full-screen intent. */
        var showing = false
            private set
    }
}
