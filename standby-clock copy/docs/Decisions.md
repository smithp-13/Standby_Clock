# Decisions

## Use raw AOSP values for hidden sensor types
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** `SensorService.TARGETS` uses `22` (tilt detector) and `27` (device orientation) instead of `Sensor.TYPE_TILT_DETECTOR` / `Sensor.TYPE_DEVICE_ORIENTATION`.
- **Why:** Both constants are `@hide` in the public SDK, so the code did not compile. The integer values are fixed in AOSP and `SensorManager.getDefaultSensor(type, true)` accepts them, which keeps the prototype probing the same sensors.
- **Rejected:** Reflection on the hidden fields: more code for the same constant, and subject to hidden-API restrictions at targetSdk 36.

## Drop required-permission column from the sensor capability report
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** `MainActivity.capabilityReport()` no longer prints `perm=` per sensor.
- **Why:** `Sensor.getRequiredPermission()` is `@hide` and does not compile against the public SDK.
- **Rejected:** Calling it via reflection: blocked or greylisted on recent API levels for apps targeting 36, so the column would be unreliable.

## Build with JDK 21, not Android Studio's bundled JDK 25
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** Gradle 8.13 is run with the JBR 21 at `~/Library/Java/JavaVirtualMachines/jbr-21.0.11`.
- **Why:** Gradle 8.13 fails immediately on Java 25 (error `25.0.4`), which is the JDK bundled with the installed Android Studio.
- **Rejected:** Upgrading Gradle/AGP to support Java 25: larger change than needed to get a build onto the device.

## Trigger the standby clock from the wake-up device-orientation sensor
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** Show the clock when `device_orientation` (type 27, wake-up) reports 1 or 3 (landscape) while the phone is plugged in; hide it on 0 or 2 (portrait).
- **Why:** On the SM-M346B this sensor delivered correct portrait/landscape events with the screen off (tested 15:18-15:19, `interactive=false`). It reports only changes, so no debouncing is needed.
- **Rejected:** Tilt detector alone: it fires on any tilt and carries no orientation. Significant motion: one-shot trigger, did not register through `registerListener`.

## Use plugged-in state, not BatteryManager.isCharging
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** The clock condition reads `EXTRA_PLUGGED` from the sticky `ACTION_BATTERY_CHANGED` intent.
- **Why:** On the test device `isCharging` returned false while USB was connected (status 2, charging), so it would have blocked the clock intermittently.
- **Rejected:** `BatteryManager.isCharging`: unreliable on this device, as above.

## Launch the clock with a full-screen-intent notification
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** `SensorService` posts a high-importance notification with `setFullScreenIntent` to open `ClockActivity`, which uses `showWhenLocked` and `turnScreenOn`.
- **Why:** A foreground service cannot start an activity directly because of Android's background-activity-launch limits. A full-screen intent is the sanctioned way to show UI over the keyguard. On Android 14+ the permission must be granted; for testing it was granted with `adb shell appops set com.standby.clock USE_FULL_SCREEN_INTENT allow`.
- **Rejected:** `SYSTEM_ALERT_WINDOW` ("display over other apps") to bypass background launch limits: broader permission than needed and doesn't help with the lock screen by itself.
- **Open:** A real user has to grant the permission in Settings > Apps > Special access > Full-screen notifications; the app does not prompt for it yet.

## Run SensorService as a NotificationListenerService instead of a foreground service
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** `SensorService` now extends `NotificationListenerService`. Sensors and the screen receiver are registered in `onListenerConnected` and released in `onListenerDisconnected`/`onDestroy`. The foreground service, its "Standby Clock monitoring" notification and the `FOREGROUND_SERVICE*` permissions are removed. Start opens Notification access settings (or calls `requestRebind` if already granted); Stop calls `requestUnbind`.
- **Why:** The user wanted the persistent notification gone. Android requires one for every foreground service, but the system keeps a granted notification listener bound (at foreground priority) with no notification. Notification access is needed anyway to show notification icons on the clock, so one grant covers both.
- **Rejected:** Keeping the foreground service with an `IMPORTANCE_MIN` channel: still visible in the shade. Asking the user to turn off the channel: works but is a manual step on every device, and the app still needed notification access for icons.
- **Tradeoff:** The listener only runs while notification access is granted. For testing it was granted with `adb shell cmd notification allow_listener com.standby.clock/com.standby.clock.SensorService`.

## StandBy-style clock face drawn in a custom view with Nunito Black
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** `StandbyClockView` draws each digit separately with Nunito (variable font, `wght` 1000), alternating translucent coral `argb(240,255,88,72)` and pink `argb(225,255,150,140)`, overlapping neighbours by 28% of the digit width, with the colon as two light-grey circles. The text is sized to the view height and shrunk to fit the width. `ClockActivity` hides the system bars on API 30+.
- **Why:** It matches the iOS StandBy "big numerals" face the user referenced. Drawing digits one at a time is the only way to get the overlap and the per-digit colours; a `TextClock` cannot.
- **Rejected:** SF Pro Rounded: Apple licenses it for Apple platforms only. M PLUS Rounded and other rounded fonts: Nunito Black is the closest heavy rounded open-licence (OFL) match.

## Show app icons for clearable notifications on the clock
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** `ClockActivity` shows one app icon per package with a clearable notification, excluding its own, in a row under the digits. The row is rebuilt on every post/remove broadcast from `SensorService`. The manifest declares a `<queries>` for launcher activities so `getApplicationIcon` can see other packages on API 30+.
- **Why:** The user asked for notification icons to appear when a message arrives and go away when it is cleared. Clearable notifications exclude ongoing ones such as music players and active foreground services.
- **Rejected:** The notifications' monochrome small icons: harder to recognise at a glance than app icons. `QUERY_ALL_PACKAGES`: broader than needed, since every app that posts user-facing notifications has a launcher entry in practice.

## Clock only shows while the phone is locked
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** The trigger condition is now landscape and plugged in and `KeyguardManager.isKeyguardLocked`. This replaces the earlier "landscape and plugged in" condition.
- **Why:** The user wants nothing to happen when the phone is unlocked and in use sideways (videos, games). It also removes the "Standby clock" heads-up that appeared while unlocked.
- **Rejected:** Using `PowerManager.isInteractive`: the screen is on while the clock or the lock screen is showing, so it can't tell "in use" from "idle on the lock screen".

## Double tap dismisses to the lock screen; clock returns on idle or next wake
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** Double tap finishes `ClockActivity`, revealing the Samsung keyguard. `SensorService.clockDismissed()` schedules a re-show after `IDLE_RETURN_MS` (10 s) under a partial wake lock. The clock also re-shows on every `SCREEN_ON` while the trigger condition holds. The pending re-show is cancelled on unlock (`USER_PRESENT`) or portrait.
- **Why:** The user chose both: after a set idle time, and on the next screen wake.
- **Constraint:** Apps cannot observe touches on the keyguard, so the 10 s are counted from the double tap, not from the last lock-screen touch.
- **Rejected:** A plain `Handler` delay without a wake lock: the CPU can suspend once the keyguard times out the screen, delaying the return indefinitely. `AlarmManager` exact alarms: need `SCHEDULE_EXACT_ALARM`, which Android 14+ denies by default.

## Re-hide system bars on every window focus gain
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** `ClockActivity` hides the system bars in `onWindowFocusChanged(true)` instead of once in `onCreate`.
- **Why:** Over the keyguard the navigation bar stayed visible with the one-time hide. Verified hidden over the keyguard on the SM-M346B after the change.
- **Rejected:** Hiding only in `onCreate`: the system restores the bars when the window regains focus over the keyguard.

## Auto-rotate off: no change needed
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** No code change. The trigger reads the wake-up orientation sensor directly, and `ClockActivity`'s `sensorLandscape` overrides the user's rotation lock.
- **Why:** Tested with `accelerometer_rotation=0`: the clock appeared in landscape over the keyguard on screen wake. The setting was restored to 1 afterwards.

## Show the clock on screen-off; treat screen-off as idle
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** `SCREEN_OFF` also triggers the clock. The idle condition is now keyguard locked or screen not interactive. This supersedes the keyguard-only condition in "Clock only shows while the phone is locked".
- **Why:** Pressing the power button while sideways and charging showed nothing until the next wake (log at 15:47:07). The device has `lock_screen_lock_after_timeout=5000`, so the keyguard is not locked yet when `SCREEN_OFF` arrives and the keyguard-only check failed.
- **Tradeoff:** While sideways and charging, the screen turns straight back on with the clock after the power button, as StandBy does. Turning the phone to portrait or unplugging is the way to get a dark screen. During the 5 s lock grace the clock shows without a keyguard behind it, so a double tap returns to the unlocked app.

## Unlocking closes the clock
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** `ClockActivity` finishes on `USER_PRESENT`, except while it is opening a notification.
- **Why:** The clock is for the lock screen only. Without this, a fingerprint unlock while the clock is showing left it on top of the unlocked phone.

## Tap icon shows the notification; tap the card to unlock and open it
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** Icons are now 22 dp. Tapping one shows a card with the app icon and name and the latest notification's title and text from that app (group summaries excluded). Tapping the icon again, or the background, hides it. Tapping the card calls `KeyguardManager.requestDismissKeyguard` (Samsung's normal PIN/fingerprint prompt). On success it sends the notification's `contentIntent`, with BAL opt-in on API 34+, cancels the notification if it is auto-cancel, and finishes the clock.
- **Why:** The user asked for it to behave like a normal notification: view on the lock screen, open after unlock. Verified: the Myntra card opened Myntra after unlock.
- **Rejected:** Showing full notification text for every app at once: cluttered at StandBy sizes. Opening the `contentIntent` without dismissing the keyguard: the target activity would launch behind the lock screen.

## Smoother UI: rolling digits, fade-ins, animated icon row
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:**
  - When the minute changes, changed digits roll: the old digit fades out upwards while the new one fades in from below over 700 ms.
  - The clock fades in over 500 ms; the card fades and slides over about 200 ms.
  - The icon row uses `LayoutTransition` and only adds or removes the icons that changed.
- **Why:** The user asked for a smoother UI. Rebuilding the icon row from scratch would make every icon animate on every notification change.

## Date above the time, iOS-style battery top right
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:**
  - A `TextClock` shows "EEEE d MMMM" in coral Nunito at the top centre. Digits are now sized to 0.9 × height to leave room for it.
  - `BatteryView` draws the percentage plus a rounded battery outline with a nub, filled to the level: white normally, red at 20% or less, green when plugged in.
  - While plugged in, a lightning bolt sits inside and a shine sweeps across the fill every 1.6 s.
- **Why:** The user asked for date and battery. The user mentioned an attached battery logo, but no image arrived, so the design follows the iOS battery glyph. "Charging" uses `EXTRA_PLUGGED`, consistent with the earlier `isCharging` finding.

## Main screen shows buttons only; Start walks through the permissions
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** `MainActivity` drops the sensor report and event-log text, and the Refresh button that only redrew them. It shows Start, Stop, Share log and Clear log, centred on black. Only the button matching the current state is enabled (Start when the listener is not bound, Stop when it is). Each Start tap handles the first missing requirement, in order: notification access settings; full-screen-intent settings (API 34+); `POST_NOTIFICATIONS` request (API 33+); otherwise `requestRebind`. Share log now exports `EventLog` directly.
- **Why:** The user asked for buttons only. Previously Start asked for notification permission and opened Settings at the same time, and full-screen intent could only be granted with adb, so a fresh install never got the clock over the lock screen.
- **Rejected:** A status text line: the user asked for buttons only, so the enabled state of Start and Stop carries the status.

## Register only the device-orientation sensor
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** `SensorService` registers only `device_orientation` (type 27). The tilt detector and significant-motion registrations from the prototype are removed.
- **Why:** The trigger never used them. The tilt detector is a wake-up sensor, so every tilt woke the CPU and wrote a log line, which wastes battery. Significant motion never registered at all.

## Stability and smoothness fixes from the full review
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:**
  - `onListenerConnected` releases first, so a rebind can't register the sensors or receivers twice.
  - `SensorService` only broadcasts changes for clearable notifications from other apps; download-progress and media notifications used to trigger an icon-row rebuild many times a second.
  - `ClockActivity` waits 250 ms after the last change before rebuilding the icon row.
  - `BatteryView` reuses one gradient shader moved with a matrix instead of allocating a gradient each frame, caches the percentage text, and pauses the shine whenever the view isn't visible.
  - `StandbyClockView` reuses a `FloatArray` for digit positions and draws from the string without creating new ones each frame.
- **Why:** The user asked for fewer bugs and smoother behaviour. Lint flagged the battery allocations (`DrawAllocation`), and per-frame allocations during the 60 fps shine and roll animations cause garbage-collection stutter.

## Launcher icon
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** An adaptive icon (`mipmap-anydpi/ic_launcher.xml`) on black, with two overlapping coral and pink rounded bars and the two-dot colon, set as `android:icon`.
- **Why:** Lint `MissingApplicationIcon`; the app showed the generic Android icon.

## Gradle wrapper scripts, Gradle 8.14.6, 60 s network timeout
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:**
  - Added `gradlew`, `gradlew.bat` and `gradle-wrapper.jar`.
  - Kept the Gradle 8.14.6 that Android Studio had switched `gradle-wrapper.properties` to at 15:07.
  - Set `networkTimeout=60000`.
  - Builds rely on the existing `gradle/gradle-daemon-jvm.properties` (`toolchainVersion=21`).
- **Why:**
  - Studio sync kept failing because the 8.14.6 download had timed out and left an empty `.part` file. It was downloaded manually and checked against the published SHA-256 (`7988ed07…bd24`).
  - On this connection the download took 4 minutes, so the wrapper's default 10 s timeout was the original `SocketTimeoutException`.
  - With the daemon-JVM file, `./gradlew clean assembleDebug lintDebug installDebug` succeeds even when launched with Android Studio's bundled Java 25, which Gradle 8.13/8.14 cannot run on directly.
- **Rejected:** Reverting to 8.13: Studio chose 8.14.6 and AGP 8.13 supports it. Pointing `org.gradle.java.home` at a JDK path: it is machine-specific and would be wrong on any other computer.
- **Remaining lint warnings, accepted:**
  - `OldTargetApi`: target 36 is the installed SDK.
  - `UnspecifiedRegisterReceiverFlag`: the pre-API-33 branch has no flags API.
  - `DiscouragedApi`: fixed orientation is only ignored on large screens; the M34 is a phone.
  - `DataExtractionRules`: backups are off.

## Swipe the notification card sideways to clear the notification
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** The card follows a horizontal drag (fading as it goes). Released past 30% of its width, it flies off and calls `cancelNotification(key)` for the notification it shows. Otherwise it springs back, and a tap still opens it. Each swipe clears one notification; if the app has more, its icon stays and the card shows the next. Every swipe is logged (`notification swiped away <pkg>`).
- **Why:** The user asked for left/right swipe to remove notifications. One-at-a-time matches the Android shade.
- **Verified:** Swipes during testing removed the test notification (`com.android.shell`), WhatsApp and `com.nis.app` from the system entirely (0 records in `dumpsys notification`). IPO Premium and Flipkart kept their other notifications.
- **Accepted lint warning:** `ClickableViewAccessibility`. `swipeCard()` calls `performClick()` on a tap, but lint can't see it through the lambda.

## Standby on battery shows the simple clock; arcade frame only while charging
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** This supersedes the requirement that the phone be plugged in, from "Clock only shows while the phone is locked" and "Show the clock on screen-off".
  - The clock now shows whenever the phone is sideways and idle (locked or screen off).
  - On battery it only appears on a screen that is already on (wake, or turning sideways while the lock screen is showing). It does not keep the screen on, so the normal timeout turns the screen off.
  - On power, the existing behaviour stays: it may turn the screen on and keeps it on.
  - `ClockActivity` switches between the simple layout and the arcade layout live on `POWER_CONNECTED`/`POWER_DISCONNECTED`, animated with `TransitionManager`, instead of closing on unplug.
- **Why:** The user asked for the clock on the lock screen even when not charging, with the charging animation only while charging. Turning the screen on or holding it on while on battery would drain the phone and could wake it in a pocket.
- **Verified:** With `dumpsys battery unplug` the open clock switched to the simple layout and `FLAG_KEEP_SCREEN_ON` cleared (`fl=81810500`). After `dumpsys battery reset` the arcade frame and the flag (`fl=81810580`) came back.

## Plugging in triggers the clock
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** `SensorService` also triggers on `ACTION_POWER_CONNECTED`.
- **Why:** The "standby inactive" report. At 16:32:20 the USB link dropped for 9 s and the screen went off at 16:32:24, while the phone counted as unplugged. When power returned at 16:32:29 nothing re-checked, so no clock until 16:40. Separately, at 16:40:23 the process was force-stopped (`am_kill … stop com.standby.clock due to from pid 19929`) and Android re-bound the listener within a second.

## Arcade charging frame from the "Arcade charging screen" mockup
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** `ArcadeView` reproduces `ChargeMuncher.dc.html`:
  - **Dots:** 100 positions round a loop at a 36 dp inset (36 top, 14 right, 36 bottom, 14 left, as in its `pos()`). Dots are `#FFD9C7`, 6 dp, with 12 dp every 5%. Eaten dots are at 10% opacity, and the next dot pulses between 100% and 35% over 0.9 s.
  - **Walls:** the mockup's `#2B3BFF` 5 dp inner wall at 58 dp, plus a matching outer wall at 14 dp that the user asked for, forming a corridor.
  - **Info text:** CHARGING / level / EATEN in Press Start 2P, shrunk from the mockup's 14/56/11 to 8/22/6.5 dp as the user asked. The bolt glows over 1.4 s.
  - **Icons:** the user's muncher icon replaces the mockup's character, in the accent yellow `#FFD400` because the supplied black outline would be invisible on black. Its mouth chomps every 0.26 s and it bobs every 0.52 s. The user's red ghost replaces the cyan hexagon at every 25%. Eaten ghosts are filled, as the mockup fills collected cells.
  - **Layout:** the clock content moves inside the inner wall (64 dp inset) while charging.
  - **Cost:** a `TimeAnimator` runs only while the view is visible. Ghost and bolt paths are built once; the muncher path is reset each frame, not reallocated.
- **Why:** The user supplied the mockup and the two icons. The mockup's 892×412 dp artboard is exactly this phone's landscape size in dp, so its numbers are used directly.
- **Rejected:** Rendering the HTML in a WebView: it needs the React runtime and Google Fonts over the network, and is heavier than drawing ~100 shapes on a canvas.

## Correction: the M34 is 832×384 dp, not the mockup's 892×412
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** No code change. `ArcadeView` already positions the loop from the view's actual width and height with the mockup's dp insets, so it fits.
- **Why:** The previous entry said the artboard matched this phone exactly. The device reports `w832dp h384dp` at 450 dpi.

## Standby only on the lock screen, judged by isKeyguardLocked
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** This supersedes the "locked or screen off" idle rule in "Show the clock on screen-off; treat screen-off as idle".
  - Standby requires the lock screen to be up (`isKeyguardLocked`).
  - On screen-off while on power, the service polls once a second, for up to 30 s, until the lock screen is up.
  - `ClockActivity` checks `isKeyguardLocked` every 300 ms while resumed, and closes the moment the phone is unlocked.
  - The `USER_PRESENT` receiver is registered `RECEIVER_EXPORTED`.
- **Why:**
  - The user wants the clock never on an unlocked phone. Treating screen-off as idle let it show during the lock delay, when the phone was not locked yet.
  - `isDeviceLocked` was tried first and rejected on the device. Google Extend Unlock (`trustState=TRUSTED, deviceLocked=0`) made it false while the lock screen was showing, which blocked standby entirely.
  - The not-exported `USER_PRESENT` receiver never fired, because SystemUI (an app uid) sends it. Exported, it arrived about 6 s late on Android 16 (clock finished at 16:58:58.7 for an unlock at 16:58:52.4), so the user saw the clock "stay". The 300 ms check closes it instantly, which the user confirmed.
- **Rejected:** `isDeviceLocked` (Smart Lock, above). Relying on `USER_PRESENT` alone (late, above).

## On battery, turning the phone sideways while locked wakes the screen
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** This supersedes "on battery it only appears on a screen that is already on" for the sensor case only. A device-orientation change to landscape while locked may turn the screen on, on battery too. Screen-off, power-connected and the idle timer still never wake the phone on battery. The screen still times out normally.
- **Why:** The user asked that standby be enabled whenever the phone is locked and the sensor detects it as horizontal.
- **Tradeoff:** Turning a locked phone sideways in a bag will light the screen until the timeout.

## Preview button
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** A Preview button on the main screen opens `ClockActivity` with `EXTRA_PREVIEW`. A preview may show while unlocked, ignores unlock and portrait signals, closes on double tap or Back, and does not schedule the idle return. `onNewIntent` resets the flag when a real standby launch reuses the instance.
- **Why:** The user asked to be able to see the clock on demand. Automatic standby stays lock-screen only.

## No portrait flash on launch
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** The window background is black, and the content fades in only when `onCreate` runs in landscape.
- **Why:** Launched over the portrait lock screen, the activity is first created upright and then recreated sideways (two `wm_on_create_called` 0.7 s apart in the logs). The user saw the portrait layout first; now the first pass is plain black.

## Swiping a card clears the whole app
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** This supersedes "one notification per swipe" in "Swipe the notification card sideways". Swiping calls `SensorService.clearApp(pkg)`, which cancels every clearable notification of that package, group summaries included.
- **Why:** The user chose "All of that app's". Clearing only the shown child left the app, or its group header, in the panel.
- **Verified:** Two shell test notifications plus their automatic group summary: 3 records before, 0 after one swipe.

## Arcade: no speed lines, smaller muncher on the coins, inverted ghost states
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** This supersedes the muncher size and "eaten ghosts are filled" in the arcade entry.
  - The speed-line "tails" are removed.
  - The muncher is 36 dp (10% smaller than 40), drawn centred on its body circle instead of the icon box, so it sits on its coin. It leans 6 dp forward so the mouth meets the next coin.
  - Remaining ghosts are solid red; eaten ghosts are unfilled outlines at 50% opacity.
- **Why:** All four were user requests. Centring on the icon box had put the body about 5 dp off the dot path.

## Start/Stop switch on one tap
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:**
  - Start (when nothing is missing) and Stop set the buttons to their target state immediately.
  - `SensorService` broadcasts `com.standby.clock.STATE_CHANGED` on connect and disconnect.
  - `MainActivity` listens for it while resumed, so the buttons always end on the real state.
- **Why:** `requestRebind`/`requestUnbind` complete asynchronously. The old `refresh()` ran straight after and still saw the old `instance`, so the buttons didn't change and the user tapped again. The user confirmed one tap each now.
- **Accepted lint warning:** `InlinedApi` for `RECEIVER_NOT_EXPORTED` on API 28–32. The flags overload exists since API 26, and the inlined constant is ignored there.

## Faster, cleaner standby launch
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:**
  - `ClockActivity` declares `configChanges="orientation|screenSize|screenLayout|smallestScreenSize"`, so the rotation from the portrait lock screen happens in place instead of a destroy-and-recreate.
  - The clock fades in over 150 ms (was 500 ms) as soon as the configuration is landscape.
  - After screen-off, the lock wait polls every 250 ms (was 1 s).
- **Why:** Measured on the SM-M346B, screen-off 17:05:47.401 to clock:
  - +220 ms for NotificationManager to post the full-screen intent;
  - SystemUI then calls `PowerManager.wakeUp` itself and the display shows our black portrait window (screen on at +406 ms);
  - Android only rotates at +1.28 s (`setRotation: rotation=1`);
  - then the relaunch (+190 ms) and the 500 ms fade.
  After the change, screen-off 17:08:03.699 → screen on 04.272 → rotation 05.202 → clock about 150 ms later, roughly 0.5 s faster.
- **Constraint (not fixable from the app):** SystemUI wakes the screen for a full-screen intent before the activity draws, and the rotation waits about 0.9 s for the lock-screen hand-off. That leaves a short black upright moment.
  - Showing the clock before the lock engages is ruled out by the lock-screen-only rule.
  - With the M34's 5 s auto-lock delay, a timeout adds 5 s of dark screen before the clock. Setting auto-lock to Immediately removes that wait.
- **Rejected:** A device-admin `lockNow()` to skip the auto-lock delay: it needs device-admin activation, which is heavy for this. Holding the first frame until landscape: WindowManager's draw timeout (~1 s) would release the screen-on block anyway.

## Charging layout gives the digits more room
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** While charging:
  - the date moves to the top left at 13 sp (from centred, 20 sp);
  - notification icons shrink to 16 dp (from 22) and sit 4 dp from the bottom;
  - `StandbyClockView.heightFactor` goes from 0.9 to 1.08, so the digits are about 20% bigger;
  - the arcade's CHARGING / % / EATEN text shrinks to 6 / 14 / 5 dp (from 8 / 22 / 6.5), tucked 64 dp from the top and right.
  The simple (battery) layout is unchanged.
- **Why:** The user asked for the date on the left and smaller, smaller icons and smaller battery info, so the clock can be bigger.
