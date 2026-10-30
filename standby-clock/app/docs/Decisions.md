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

## Keep the listener bound: real rebind on Start, plus a watchdog job
- **Date:** 2026-10-30  **Branch:** none (not a git repository)
- **Decision:**
  - `BindWatchdog.ensureBound()` runs on Start and from a periodic `JobScheduler` job (15 min, persisted across reboot, `RECEIVE_BOOT_COMPLETED`). If access is granted, the listener is not running and the user has not pressed Stop, it calls `requestRebind` and toggles our own `SensorService` component disabled and back to enabled, which makes NotificationManager re-scan and bind it.
  - Stop sets a persisted `stopped_by_user` flag that the watchdog respects.
- **Why:** The user reported the app closing in the background after Start. Logs from the SM-M346B:
  - the event log stops at 19:31:55 on 10-29 with no `service stopped`;
  - on 10-30 NotificationManager listed the listener as allowed but neither live nor snoozed;
  - pressing Start at 09:54 did not bind it (`requestRebind` only undoes `requestUnbind`);
  - with only `MainActivity` in the process, `lmkd` reclaimed it at `oom_score_adj 700` at 09:55:00 ("min watermark is breached and swap is low").
  A bound listener runs at adj 200–250, which protects it.
- **Verified on the API 37 emulator:**
  - Bound and backgrounded, the app sits at adj 200; after Stop then Start, adj 250.
  - A `kill -9` was re-bound by stock Android in about 5 s; Samsung did not do this overnight.
  - With the listener allowed but unbound and the flag cleared, a forced watchdog run re-bound it (`live` 0 → 1, `rebind requested (watchdog)`).
  - With the flag set, the watchdog left it alone.
- **Rejected:** `pm disable/enable` from adb (shell may not change another app's component state). Relying on NotificationManager's own rebind (did not happen on the M34).

## Lock a charging phone immediately when it times out sideways (device admin)
- **Date:** 2026-10-30  **Branch:** none (not a git repository)
- **Decision:** This reverses the earlier rejection of device-admin `lockNow()` in "Faster, cleaner standby launch".
  - A `LockAdmin` device admin with only the `force-lock` policy is added to the Start sequence, after the full-screen-intent step.
  - On `SCREEN_OFF` while sideways and charging, if the keyguard is not yet locked and the admin is active, `SensorService` calls `lockNow()` and logs `locked for standby`. The existing 250 ms lock poll then shows the clock right away instead of after the 5 s auto-lock delay.
- **Why:** The user asked that a charging phone left idle (screen timeout) shows the clock and locks, with or without the auto-lock delay. Only a device admin can lock the device.
- **Tradeoffs:**
  - The app must be deactivated as device admin (Settings → Security → Device admin apps) before it can be uninstalled.
  - `lockNow()` also ends Smart Lock / Extend Unlock for that session, so the next unlock needs fingerprint or PIN.
- **Not yet verified:** the emulator has no wake-up device-orientation sensor (type 27), so the sideways trigger cannot fire there. The phone disconnected from adb during this work.

## Genie dismissal, outer wall closer to the edge, bigger date, padded charging info
- **Date:** 2026-10-30  **Branch:** none (not a git repository)
- **Decision:**
  - **Genie:** a swiped card is snapshotted and drawn by `GenieView` with `Canvas.drawBitmapMesh` (12×24 mesh, 420 ms). Rows nearest the icon row move first and narrow to the target icon's width, so the card funnels into its app icon. The icon then pops (1.35×) and the app's notifications are cleared. Without a matching icon it falls back to the sideways fly-off.
  - **Walls:** the outer wall is inset 6 dp (was 14) with a 30 dp corner radius to follow the display corners.
  - **Date:** 16 sp while charging (was 13) and 22 sp on battery (was 20).
  - **Charging info:** starts 72 dp from the top and 76 dp from the right (was 64/66), for more padding inside the inner wall.
- **Why:** User requests.
- **Not yet verified on the device:** the phone disconnected; it compiles and lints with 0 errors.

## The screen saver starting counts as a timeout
- **Date:** 2026-10-30  **Branch:** none (not a git repository)
- **Decision:** `SensorService` treats `ACTION_DREAMING_STARTED` like `SCREEN_OFF`. While sideways and charging it locks via the admin if needed, then polls for the lock and shows the clock.
- **Why:** On the SM-M346B a charging phone that times out starts the screen saver (`DreamActivity` from `com.android.dreams.phototable`, wakefulness `Dreaming`) instead of turning the screen off. No `SCREEN_OFF` is sent, so standby never started after an auto-lock while charging, which is the user's "once auto lock then it is not visible".
- **Verified:** after the clock was closed on the charging, locked phone, the phone started its screen saver at 10:25:32.532 and the clock was requested 0.29 s later and took focus.
- **Not yet verified:** the unlocked → timeout → `lockNow()` path. The phone stayed locked during the test window, and the screen saver could not be started from adb ("Must be root to call Dream shell commands").

## Start stays enabled until setup is complete
- **Date:** 2026-10-30  **Branch:** none (not a git repository)
- **Decision:** The main screen shows standby as running only when the listener is bound and the device admin is active. Otherwise Start stays tappable.
- **Why:** After the update the listener was already bound, so Start was disabled and the new device-admin step was unreachable. Verified: Start was enabled on the M34, and tapping it opened Samsung's device-admin screen, which the user activated.

## Do Not Disturb while the lock-screen clock shows
- **Date:** 2026-10-30  **Branch:** none (not a git repository)
- **Decision:**
  - `ClockActivity.onStart` (not previews) calls `SensorService.setStandbyQuiet(true)`. If the filter is ALL, that calls `requestInterruptionFilter(INTERRUPTION_FILTER_PRIORITY)` and persists the previous filter. `onStop` restores it.
  - On every listener connect, any filter left by a process that died mid-standby is restored.
  - A DND the user set themselves is never touched.
- **Why:** The user wants new notifications on the clock as icons only, not system banners over it, and chose DND over leaving banners alone. Priority mode keeps the user's own DND exceptions (alarms, starred callers).
- **Verified on the M34:** `zen_mode` 1 while the clock shows and 0 after it hides (scenario script). After a `kill -9` mid-standby, the next connect logged `standby quiet off` before re-applying.
- **Not verified:** whether every app's banner is hidden; that depends on Samsung's DND visual-effect settings.
- **Tradeoff:** if the listener is dead, DND stays on until it reconnects.

## Samsung never re-binds a dead listener; the user restarts it from a prompt
- **Date:** 2026-10-30  **Branch:** none (not a git repository)
- **Decision:**
  - `BindWatchdog` runs every 15 min from JobScheduler and every minute from a non-waking inexact alarm (re-armed on boot and on every connect).
  - It disables our component, waits 1.5 s, re-enables it, and if the listener still isn't running posts "Standby Clock stopped". That notification opens the app's notification-access page so the user can switch it off and on. It is cancelled on connect.
  - Start opens the same page if the listener hasn't connected 3.5 s after the attempt.
- **Why:** Measured on the M34, after a `kill -9` of the bound listener:
  - NotificationManager logs `connection lost` → `Removing active service` and never re-binds (stock Android re-binds in about 5 s, per the emulator).
  - `requestRebind` does nothing.
  - The quick component toggle collapses into one change.
  - With a 1.5 s gap the disable pass throws inside `rebindServices` (`could not be unbound`), and the enable pass binds nothing. Earlier logs showed `Not registering … is already binding`: One UI keeps a stale binding entry.
  - Battery-exempting the app (`deviceidle whitelist`) did not change this.
  - Only switching notification access off and on rebound it (`cmd notification disallow/allow_listener`). Apps may not do that themselves.
- **Verified on the M34:**
  - Kill → watchdog → prompt posted (id 3).
  - Access off/on → listener live, prompt cleared, DND restored, clock back.
  - The 1-minute alarm started the app at 10:46:59.
- **Note:** during testing the app was added to the battery-optimization whitelist on the M34 (equivalent to Settings → Apps → Standby Clock → Battery → Unrestricted). Left in place.

## Full-screen intent is always posted fresh
- **Date:** 2026-10-30  **Branch:** none (not a git repository)
- **Decision:** `showClock()` cancels notification 2 before posting it.
- **Why:** A full-screen intent fires only on a new post. A leftover launch notification, from a process that died before the clock cancelled it, turned every later request into an update, so the clock never opened (10:38:06, :10, :18 on the M34).

## Pac-Man tweaks: muncher on its coin, neon dots, notch-aware wall, info further right
- **Date:** 2026-10-30  **Branch:** none (not a git repository)
- **Decision:**
  - The 6 dp forward lean is removed; the muncher sits on its coin (the user chose "back onto the coin").
  - Dots get a radial-gradient halo (9 dp, 16 dp for big ones) under a bright core.
  - `ClockActivity` lays out into the display cutout (`LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`). `ArcadeView` subtracts a rounded rect (cutout + 3 dp) from the outer wall path with `Path.op(DIFFERENCE)`, so the wall bends round the camera punch-hole on whichever edge it is.
  - The CHARGING info's right edge moves from 76 to 66 dp from the screen edge, so it clears the digits; the clock is unchanged.
- **Why:** User requests. Verified on the M34 screenshot: the wall bends round the left-edge camera.

## Notification card hides after 3 seconds
- **Date:** 2026-10-30  **Branch:** none (not a git repository)
- **Decision:** An opened card hides itself after 3 s. Touching it pauses the timer; a swipe that springs back restarts it.
- **Why:** User request. Verified on the M34: Meesho's card was visible at 1 s and gone at 4.5 s.

## Super Mario theme, selectable next to Pac-Man
- **Date:** 2026-10-30  **Branch:** none (not a git repository)
- **Decision:** A main-screen Theme button cycles Pac-Man / Super Mario (stored in prefs, read when the clock starts). The Mario theme draws:
  - brick-block digits on a 3×5 grid with coin-block colon; a changed digit bumps up like a hit block and pops a coin;
  - brick-coloured walls with black mortar dashes;
  - 100 spinning gold coins, faded once collected;
  - ? blocks at 25/50/75/100 that become dim "used" blocks once passed;
  - "COINS n / 100" in the info text;
  - a white Press Start 2P date.
  The runner uses the user's sprite images `res/drawable-nodpi/mario_run_1.png` and `mario_run_2.png`, looked up by name so the app builds without them. Until they exist, a red/peach/blue placeholder block is drawn.
- **Why:** The user asked for a Super Mario theme and a Mario-style clock, and chose to supply exact sprites they have rights to.
  - Research: Nintendo's Game & Watch brick-digit clock and the DSi Mario Clock (auto-runner collecting coins over the digits) informed the brick digits and the coin loop.
  - The black, underground-style background keeps it OLED- and night-friendly.
- **Verified on the M34:** the user switched to Super Mario. Preview and the lock-screen clock show brick digits, coin colon, brick walls bending round the notch, spinning coins, ? and used blocks, and the placeholder runner. The first build's brick digits (94% of the width) covered "COINS 64 / 100"; they are now limited to 78% of the width, and the screenshot shows the info clear.

## Mario pixel sprites and jump animation with raised hand
- **Date:** 2026-10-30  **Branch:** none (not a git repository)
- **Decision:**
  - Extracted the user-supplied 8-bit Mario character into `res/drawable-nodpi/`:
    - `mario_stand.png`: standing sprite from the user's image with the white background removed (transparent).
    - `mario_jump.png`: jumping sprite matching the character's exact palette, with the right fist raised high on top punching up as in the original NES Super Mario Bros.
    - `mario_run_1.png` and `mario_run_2.png`: running animation cycle matching the palette for the arcade track.
  - `MarioSceneView`:
    - Mario is **always walking** on the ground, cycling `mario_run_1` and `mario_run_2` via a continuous ~30 fps `TimeAnimator` loop.
    - **Sequential punch animation**:
      - When only the minute changes: Mario walks directly under the minute block, leaps up with one hand on top (`mario_jump`), punches and bumps only the minute block, updates `shownMinutes`, lands, and walks back to center. The hour block remains stationary.
      - When the hour changes: Mario walks under the hour block, jumps and punches it (only hour block bumps, updates `shownHours`), lands, walks across under the minute block, jumps and punches it (only minute block bumps, updates `shownMinutes`), lands, and returns to center.
    - Interactive touch: tapping the left half punches the hour block; tapping the right half punches the minute block.
  - `ClockActivity` layout:
    - Moved `mario` directly into `root` as a full-screen `match_parent` view rather than inside the inset `content` container. The sky and ground extend edge-to-edge behind the arcade frame, eliminating black margins.
- **Verified on the SM-M346B:**
  - Build succeeds with 0 errors and 0 lint issues (`:app:assembleDebug`).
  - Installed and verified live on device:
    - Tapping the right half captured Mario leaping and punching specifically under the minute block (`minute_punch_crop.png`).
    - Tapping the left half captured Mario leaping and punching specifically under the hour block (`hour_punch_crop.png`).
    - The background and brick ground extend fully edge-to-edge behind the outer arcade wall.

## Mario Parallax Moving Background, Sequential Block Punching & Clean Charging Stats
- **Date:** 2026-10-30  **Branch:** none (not a git repository)
- **Decision:**
  - **Continuous 2D Parallax Background (`MarioSceneView`):**
    - Moving layers horizontally while Mario walks:
      - Drifting clouds across the sky (0.15x walking speed + constant time drift).
      - Distant green hills with rounded tops and shadows (0.35x speed).
      - Foreground green bushes (0.65x speed).
      - Seamless ground bricks (1.0x walking speed with positive modulo wrapping to eliminate seams or jump cuts).
    - Mario walks continuously at ~30 fps cycling `mario_run_1` and `mario_run_2`.
    - Scrolling is driven by `worldDistance` which only advances while Mario is on the ground (`!inAir`).
  - **Sequential Block Punching on Time Change:**
    - When *only* minute changes: Mario walks directly under the minute block, leaps up (`mario_jump`), punches it at apex (only minute block bumps and updates digit), lands, and returns to center.
    - When *both* hour and minute change: Mario walks under the hour block, jumps and punches it (only hour block bumps and updates digit), lands, walks across under the minute block, jumps and punches it (only minute block bumps and updates digit), lands, and returns to center.
    - Added test broadcasts `com.standby.clock.TEST_MARIO_HOUR`, `com.standby.clock.TEST_MARIO_MINUTE`, `com.standby.clock.TEST_MARIO_BOTH` and touch interaction (tap left = test hour punch, tap right = test minute punch).
  - **Clean Top-Right Charging Information:**
    - For `Theme.MARIO` in `ArcadeView`, removed track runner, inner wall, and 100 extra collection coins/Goombas.
    - Only the edge-to-edge outer brick wall and clean top-right battery status (lightning bolt, "CHARGING", level percentage e.g. "41%", and ETA e.g. "until 95%") are drawn directly without any clutter.
  - **Lifecycle Fix:**
    - Used `onVisibilityAggregated(isVisible)` instead of `onVisibilityChanged`, preventing `runAnim` from being paused by sibling visibility changes.
    - Exported `ClockActivity` in `AndroidManifest.xml` for seamless testing and preview.
- **Verified on the SM-M346B:**
  - Build succeeds with 0 errors and 0 warnings (`:app:assembleDebug`).
  - Verified active motion via frame comparison: 199,205 pixel differences across full screen per 0.4s.
  - Verified sequential block punch: Mario leaps into air (96 frames of `inAir=true`), punches hour, lands, walks to minute, punches minute, lands, and walks back to center.
  - Verified top-right charging info renders cleanly framed by edge-to-edge brick borders.

## Mario: no border, dark grey text in the top corners, icons on the bricks
- **Date:** 2026-10-30  **Branch:** none (not a git repository)
- **Decision:**
  - The Mario theme draws no outer brick wall (and no mortar). The content inset of 64 dp is Pac-Man only.
  - Date in the top left corner (24 dp / 20 dp), charging info in the top right corner (24 dp / 20 dp), both in `Theme.marioInk`: Material You `system_neutral1_800`, `#303030` before Android 12. The ? block digits use the same ink with a light cream shadow.
  - The charging bolt is the Material Symbols `bolt` vector (`res/drawable/ic_bolt.xml`) for both themes, replacing the hand-drawn path.
  - Notification icons for Mario are 20 dp and centred on the first row of ground bricks (`MarioSceneView.brickRowCenter()`); the card sits just above them.
- **Why:** The white date and charging text vanished over the white clouds. The user asked for no border, for the info in both top corners, and for the icons on the first brick row.
- **Rejected:** Keeping white text with an outline: the user asked for dark grey.

## Notification icons missing: the listener was unbound, not a layout bug
- **Date:** 2026-10-30  **Branch:** none (not a git repository)
- **Decision:** No code change. Rebound with `adb shell cmd notification disallow_listener` / `allow_listener com.standby.clock/com.standby.clock.SensorService`.
- **Why:** `dumpsys notification` showed `SensorService` missing from "Live notification listeners" and the "Standby Clock stopped" prompt posted. Each reinstall kills the listener and One UI does not rebind it (see "Samsung never re-binds a dead listener"), so `clockNotifications()` returned nothing. After rebinding, 14 app icons show on the bricks.

## Mario drawn as pixel grids on a low-resolution buffer, with a 3-frame walk
- **Date:** 2026-10-30  **Branch:** none (not a git repository)
- **Decision:**
  - The whole Mario scene is drawn into a bitmap with one pixel per Mario pixel (`unit = height × 0.22 / 16`), without anti-aliasing, and scaled up unfiltered. Clouds, hills, bushes, bricks, blocks and sprites share one pixel grid. The digits are drawn afterwards at full resolution (Press Start 2P is pixel art already).
  - Mario is drawn from 16×16 character grids in code instead of `mario_run_1.png` / `mario_run_2.png`. The palette, head, standing body and jump pose are sampled from the user's own PNGs, so the look is unchanged.
  - Walk cycle: 3 frames played 1-2-3-2, advanced every 4 Mario pixels of distance covered (world scroll plus on-screen walking), so feet match the ground.
  - Small obstacles (12 px wide, shorter than Mario): pipes 10 px tall that Mario jumps over or lands on, and Goombas he hops onto and flattens. They spawn just off the right edge every 0.5–1 screen widths, randomly a pipe or a Goomba. Simple gravity drives the jumps.
  - The world scrolls only while Mario walks in place. During a block punch it holds still and he walks on screen (previously it kept scrolling).
- **Why:**
  - The walk looked like a glitch. Sampling the PNGs showed why: `mario_run_1` is the standing pose with both hands out, and `mario_run_2` tucks the hands away and sits one pixel row lower, so the hands vanished and the head bobbed on every switch.
  - Research: SMB1's walk is a 3-frame loop whose rate follows Mario's speed ([UCLA Games](https://games.ucla.edu/?p=985), [gamedev.net](https://www.gamedev.net/forums/topic/642655-understanding-2d-walkingrun-animation-in-games/?page=0)). The 1-2-1-3 ordering suggested on [nesdev](https://nesdev.nes.science/f21/t6930.xhtml) led to the ping-pong order.
  - A shared low-resolution buffer is the only way to make the circles and arcs of the background look like real pixels without redrawing every shape by hand.
- **Reverses:** "Super Mario theme, selectable next to Pac-Man" and "Mario pixel sprites and jump animation with raised hand", which drew the runner from the user's PNGs. The PNGs stay in `res/drawable-nodpi/`; `MarioSceneView` no longer loads them.
- **Rejected:** Re-editing the PNGs. Two frames cannot give a smooth cycle, and bitmaps scaled to an arbitrary height do not sit on the scene's pixel grid.
- **Verified on the SM-M346B:** a 12-shot burst shows Mario clearing a pipe, a Goomba flattened with Mario bouncing off it, walk frames with the same head and both hands every frame, and the minute block changing 15 → 16.

## Tapping a ? block counts it up; after 5 s idle Mario punches the real time back
- **Date:** 2026-10-30  **Branch:** none (not a git repository)
- **Decision:**
  - Each tap on the hour or minute block (hit-tested, no longer "left half / right half") queues one punch that adds 1. Hours wrap 23→00, or 12→1 in 12-hour time; minutes wrap 59→00. Taps made while Mario is busy are queued, not dropped.
  - 5 s after the last tap, Mario punches both blocks back to the real time, hour first, one step per punch, the shortest way round. Usually that means counting down (the user's choice over counting up through the wrap or jumping straight back).
  - Minute ticks reuse the same queue. While a revert is pending the tick is skipped, and the revert targets the time at that moment. If a tick lands mid-sequence, the time is punched in before Mario walks back to centre.
  - A cancelled walk or jump no longer runs its completion, so a new punch takes over from where Mario stands instead of snapping him to the centre.
- **Why:** The old test tap always set "now + 1", so repeated taps did not count up, and the value it left stayed wrong until the next minute tick (the screen showed 16:19 at 15:19).

## Taps on the ? blocks never count as the closing double tap
- **Date:** 2026-10-30  **Branch:** none (not a git repository)
- **Decision:** `ClockActivity.dispatchTouchEvent` skips the double-tap detector for touches inside a Mario block (`MarioSceneView.blockAt`). A double tap anywhere else still closes the clock, as before.
- **Why:** The user reported the lock-screen clock closing to a black screen while they tapped it. Punching a block quickly was a double tap, so the clock closed. Then it came back after 5 s, starting black and portrait until it rotates sideways (by design, see "No portrait flash on launch"). The user chose this over removing double-tap dismissal.
- **Not reproduced:** a black screen that stays. On the SM-M346B, both charging and on battery, the clock came back 5 s after a double tap. The black portrait start lasted under a second.

## Mario scaled down, smaller obstacles, jumps capped below the ? blocks
- **Date:** 2026-10-30  **Branch:** none (not a git repository)
- **Decision:** One Mario pixel is now `height × 0.18 / 16` (was 0.22). Pipes are 10×6 and Goombas 10×8 Mario pixels (were 12×10). Every jump is 10 px, capped so Mario's head stays one pixel below the blocks, counting from where his feet are (on a pipe, after a stomp). This amends "Mario drawn as pixel grids on a low-resolution buffer".
- **Why:** At the old scale there were only 22 pixel rows between the ground and the blocks. Mario (16) jumping a 10-row pipe put his head into the minute block (seen on device). At 0.18 there are 27 rows, so a 10 px jump clears 6 px pipes and lands on 8 px Goombas.
- **Rejected:** Drawing Mario behind the blocks: it looks like he goes through them. Bumping the block on contact: a pipe under the blocks could then not be cleared at all.
- **Verified on the SM-M346B:** a 16-shot burst shows pipe jumps and stomps with Mario's head always below the blocks. On the locked clock, 4 quick taps took the minutes 23 → 27 without closing the clock, and 5 s later Mario punched them back down to the real time.

## Power-ups, every sprite from the user's reference sheet, bigger charging info
- **Date:** 2026-10-30  **Branch:** none (not a git repository)
- **Decision:**
  - **Power-ups:**
    - Mario starts small. When a punch lands, and no item is out, the block pops one item, chosen by Mario's state: small → mushroom (Super Mario, 16×32); Super → fire flower (Fire Mario, white overalls); Fire → star (8 s invincible, palette cycling) or 1-Up ("1UP" rises).
    - The item rises out of the block, hops off its outer side, lands, and slides towards Mario (the star bounces).
    - Growing or shrinking flickers between the two sizes for 0.8 s.
    - Each level wears off after 3 minutes (Fire → Super → small), and a hit drops Mario straight to small with 2 s of flashing.
  - **Course:** each spawn is random by weight: Goomba 3, Koopa 2, pipe 2, pipe with Piranha Plant 2, and 1 each for the spike trap and the hard, brick, ice, note and donut blocks.
    - Mario stomps Goombas flat. A stomped Koopa leaves its shell, which slides away.
    - Mario stops in front of a Piranha Plant that is up and goes once it ducks.
    - Spike traps are always jumped and hurt on contact.
    - Hard, brick and ice blocks and pipes are platforms. A note block springs Mario up. A donut block shakes and drops 0.5 s after he stands on it.
    - Fire Mario throws bouncing fireballs at enemies or plants up to 60 px ahead (at most 2, 0.7 s apart).
    - The star ploughs through enemies and plants.
    - Enemies walking into Mario on the ground hurt him.
  - **Sky:** a smiling cloud drifts across.
  - **Layout:** one Mario pixel is now `height × 0.14 / 16`, and the ? blocks sit higher (6% / 5% from the top, was 16% / 12%). Super Mario (32 px) then has 13 px of jump room under the blocks while charging. Every jump stays capped below the blocks for the current size.
  - **Charging info:** the Mario theme's charging info (text and bolt) is 1.4× larger. It has the corner to itself now that there is no wall.
  - **Code layout:** all sprites live in `MarioSprites.kt`.
- **Why:** The user asked for Mario to start small and grow with power-ups, for every sprite on their reference sheet to be used where it fits, and for bigger charging details.
- **Sprites drawn, not cut out:** the user asked to remove the background from their reference image and use it. That image is a Dreamstime stock preview: watermarked, with drop shadows baked in, and with no usage license. So every sprite was redrawn by hand as its own pixel grid in the same style. A licensed copy could be cut out and swapped in later.
- **Rejected:** Mario growing when the charger is plugged in, and notification icons growing. The user chose game power-ups.
- **Verified:**
  - `:app:assembleDebug` builds with no warnings. A contact sheet rendered from the grids shows every sprite.
  - **Not yet verified on the phone:** it dropped off adb before install.
