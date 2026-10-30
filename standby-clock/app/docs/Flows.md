# Flows

## no-branch
Sensor probing prototype (state as of 2026-10-29, project is not under git).

1. User opens **Standby Clock** from the launcher; `MainActivity` shows Start / Stop / Refresh / Share / Clear and a text report.
2. The report lists every sensor from `SensorManager.getSensorList(TYPE_ALL)` (type, name, wake-up flag), followed by the event log from `EventLog`.
3. **Start** requests `POST_NOTIFICATIONS` (API 33+) and starts `SensorService` as a foreground service (`specialUse`).
4. `SensorService` posts an ongoing notification with a Stop action, then tries to register the wake-up variants of tilt detector (22), significant motion and device orientation (27). Each result, including "no wake-up sensor", is written to `EventLog`.
5. It also listens for screen on, screen off and user-present broadcasts. Every sensor event and broadcast is logged with interactive, charging, idle and battery state.
6. **Stop** (button or notification action) stops the service, unregisters listeners and logs "service stopped". **Share** exports the report text; **Clear** wipes the log.

Failure paths: a sensor missing on the device is logged and skipped. If notification permission is denied, the service still runs but its notification may be hidden.

## no-branch (standby clock)
Extends the sensor probing flow above with a landscape standby clock (2026-10-29). Steps 1-6 above are unchanged.

1. While `SensorService` is running, every `device_orientation` event is checked after it is logged.
2. Landscape (value 1 or 3) and plugged in (`EXTRA_PLUGGED != 0`): the service posts notification 2 on the high-importance `clock` channel with a full-screen intent for `ClockActivity`, and logs `clock requested fullScreenAllowed=<bool>`.
3. Android launches `ClockActivity` over the lock screen and turns the screen on. It cancels notification 2, keeps the screen on, and shows a white `TextClock` (h:mm / HH:mm) on black, locked to landscape.
4. Portrait (value 0 or 2): the service cancels notification 2 and broadcasts `com.standby.clock.HIDE_CLOCK` to its own package; `ClockActivity` finishes.
5. Unplugging (`ACTION_POWER_DISCONNECTED`) also finishes `ClockActivity`.

Failure paths: without the full-screen-intent permission (Android 14+), step 3 degrades to a heads-up notification the user has to tap; the log line shows `fullScreenAllowed=false`. If the service is not running (not started, or killed after reinstall), nothing triggers.

## no-branch (standby clock v2: notification listener + StandBy face)
Supersedes how `SensorService` starts and stops in the two sections above, and step 3 of the standby clock flow (2026-10-29). The orientation trigger, the plugged-in check, the full-screen intent and the hide paths are unchanged.

1. **Lifecycle.** The user taps **Start**. If notification access is not granted, Android's Notification access settings open, and the user enables Standby Clock there. Once access is granted, the system binds `SensorService` as a notification listener. It stays bound across reboots and app updates, with no persistent notification. `onListenerConnected` registers the wake-up sensors and screen receiver; `onListenerDisconnected` releases them and logs `service stopped`. **Stop** calls `requestUnbind()`; **Start** calls `requestRebind()` if access is already granted.
2. **Notifications.** Every `onNotificationPosted`/`onNotificationRemoved` broadcasts `com.standby.clock.NOTIFICATIONS_CHANGED` to the app's own package.
3. **Clock face.** `ClockActivity` hides the status and navigation bars (API 30+), shows `StandbyClockView` (overlapping Nunito Black digits in coral/pink with a two-dot colon, redrawn on `TIME_TICK`, time change and time-zone change), and below it a row of app icons for every package with a clearable notification. The row is rebuilt on each `NOTIFICATIONS_CHANGED`, so icons appear when a message arrives and disappear when it is cleared or read.

Failure paths: without notification access the listener is never bound, so neither the clock nor the icons work, and the event log shows no `registered=` lines after Start. If the screen is on and unlocked when the trigger fires, Android shows the full-screen intent as a heads-up notification instead of opening the clock; tapping it opens the clock. An icon for a package with no launcher entry is skipped.

## no-branch (standby clock v3: locked-only trigger, double-tap dismiss, idle return)
Supersedes the trigger condition and the "unlocked heads-up" failure path of the sections above (2026-10-29). Lifecycle, the notification icons and the clock face are unchanged.

**Condition.** Standby is ready when the last device-orientation value is landscape (1 or 3), the phone is plugged in, and the keyguard is locked. The Auto-rotate setting is ignored.

1. **Show.** The clock is posted via full-screen intent when standby becomes ready because:
   - the orientation changes to landscape;
   - `SCREEN_ON` arrives (power button, double-tap-to-wake, Lift to wake); or
   - the idle-return timer fires.
   Nothing is posted while `ClockActivity.showing` is true.
2. **Display.** `ClockActivity` cancels the full-screen-intent notification in `onStart` and hides the status and navigation bars on every focus gain.
3. **Double tap.** `ClockActivity` calls `SensorService.clockDismissed()` and finishes, revealing the Samsung lock screen. The service logs `clock dismissed`, holds a partial wake lock for 11 s, and schedules the idle return for 10 s later.
4. **Idle return.** After 10 s, if standby is still ready, the clock comes back, turning the screen on if it went off. If the user unlocks (`USER_PRESENT`) or turns the phone to portrait first, the return is cancelled.
5. **Hide.** Portrait hides the clock and cancels any pending return. Unplugging closes the clock.

Verified on the SM-M346B: sideways while unlocked posts nothing; wake while locked with Auto-rotate off shows the clock with no navigation bar; double tap shows the keyguard and the clock returns about 10 s later.

Failure paths: lock-screen touches are not visible to the app, so the clock can return while the user is interacting with the lock screen if that takes longer than 10 s. Without the full-screen-intent permission, nothing shows over the keyguard.

## no-branch (standby clock v4: screen-off trigger, notification card, date and battery)
Supersedes the condition and step 1 of v3, and the clock face and icon row of v2 (2026-10-29). Double tap and idle return are unchanged.

**Condition.** Sideways (1 or 3), plugged in, and either keyguard locked or screen off.

1. **Show.** Triggered by an orientation change to landscape, `SCREEN_OFF` (power button or timeout), `SCREEN_ON`, or the idle-return timer.
2. **Face.** Top: the date ("Thursday 29 October") centred, and the battery top right (percentage plus fill; green with a bolt and a moving shine while plugged in, red at 20% or less). Middle: the overlapping coral/pink digits; changed digits roll on each minute. Bottom: 22 dp app icons, one per app with a clearable non-summary notification, newest first. Icons are added and removed with animation as notifications arrive and are cleared.
3. **Notification.** Tapping an icon shows a card with the app's latest notification (app icon, app name, title, text). Tapping the icon again or the background hides it. Tapping the card shows Samsung's PIN/fingerprint prompt. After a successful unlock, the notification's own action opens (as from the shade), an auto-cancel notification is removed, and the clock closes. Cancelling the prompt leaves the clock as it was.
4. **Unlock.** Any unlock (`USER_PRESENT`) closes the clock, apart from the one triggered in step 3.

Verified on the SM-M346B:
- Sideways while unlocked shows nothing.
- The power button shows the clock over the keyguard within 3–5 s (`SCREEN_OFF` to the clock's `SCREEN_ON`).
- Date, battery and icons render as described.
- Tapping the Myntra icon showed its card; tapping the card brought up the unlock prompt, and Myntra opened after unlock.
- Power button again brought the clock back.

Failure paths:
- A notification without a `contentIntent` just unlocks.
- If the target app blocks background launches despite the opt-in, the unlock succeeds but nothing opens.
- During the device's 5 s lock grace, the clock shows with no keyguard behind it.

## no-branch (standby clock v5: buttons-only setup, single sensor, build)
Supersedes the main screen (sensor report, log view, Refresh) and the Start step of v2's lifecycle, and the sensor list of the original prototype flow (2026-10-29). The clock behaviour from v3/v4 is unchanged.

1. **Main screen.** It shows four buttons on black: Start, Stop, Share log and Clear log. Start is enabled only while standby is stopped, Stop only while it runs; the state is re-checked on every resume.
2. **Start.** Each tap does the first thing still missing:
   1. Opens Notification access (enable Standby Clock).
   2. On Android 14+, opens Full-screen notifications for the app.
   3. On Android 13+, asks for notification permission.
   4. Calls `requestRebind`.
   On a fresh install the user taps Start up to four times, returning to the app each time.
3. **Stop.** Calls `requestUnbind`. The listener releases its sensor and receivers and logs `service stopped`.
4. **Sensors.** Only the wake-up device-orientation sensor is registered.
5. **Notifications to the clock.** Only clearable notifications from other apps cause an icon refresh, and the clock rebuilds the row 250 ms after the last change.
6. **Build.** `./gradlew assembleDebug` (or Run in Android Studio) uses Gradle 8.14.6 from the wrapper. The daemon-JVM criteria select JDK 21 whatever JDK launches Gradle. The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

Verified: `./gradlew clean :app:assembleDebug :app:lintDebug :app:installDebug` launched with Studio's Java 25.0.4 succeeded, with lint at 4 warnings and 0 errors. After the install the listener rebound by itself (screen events logged) and the clock showed on screen-off. The user confirmed the main screen shows only the buttons.

Failure paths: if the Gradle download times out again on a slow connection, the wrapper now waits 60 s per network operation before failing.

## no-branch (standby clock v6: battery standby, arcade charging frame, swipe to clear)
Supersedes the condition and show triggers of v4, and the unplug-closes-clock path of v2/v4 (2026-10-29). Notification card opening, double tap and idle return are unchanged.

**Condition.** Sideways (1 or 3) and idle (keyguard locked, or screen off), plus either the screen is already on or the phone is plugged in.

1. **Triggers.** Orientation change to landscape, `SCREEN_ON`, `SCREEN_OFF`, `POWER_CONNECTED`, or the idle-return timer. On battery only the ones that find the screen already on can show the clock, so it never wakes the phone by itself.
2. **On battery: simple screen.** Date, digits, white battery top right, notification icons. Keep-screen-on is off, so the phone's normal timeout turns the screen off. The next wake while sideways shows the clock again.
3. **On power: arcade screen.** The content moves 64 dp inward. `ArcadeView` draws:
   - the outer and inner blue walls;
   - 100 dots, where the dots behind the muncher, up to the battery %, are eaten;
   - a ghost at 25/50/75/100;
   - the muncher chomping at the current level;
   - small CHARGING / % / "n / 100 EATEN" text top right inside the inner wall.
   Keep-screen-on is on. Battery % updates move the muncher.
4. **Plugging or unplugging while the clock is open** switches between steps 2 and 3 with a layout animation; the clock no longer closes on unplug.
5. **Swipe.** Dragging the open notification card sideways past 30% of its width removes that notification from the phone (`cancelNotification`), logs it, and hides the card. If the app has more notifications its icon stays; otherwise the icon leaves the row about 250 ms later.

Verified on the SM-M346B:
- Arcade screen rendered at 84% with 3 eaten ghosts.
- Swipes removed notifications from the system.
- A simulated unplug switched to the simple screen with keep-screen-on cleared, and a reset restored both.

Failure paths:
- If the USB link flickers while the screen is off, the clock now returns when power comes back.
- On battery, sideways in a pocket with the screen off shows nothing until the phone is woken.

## no-branch (standby clock v7: lock-screen only, preview, sensor wake on battery, arcade tweaks)
Supersedes the condition, the screen-off trigger and the on-battery rule of v6, the swipe step of v6, and the muncher and ghost drawing of v6 (2026-10-29).

**Condition.** Sideways (1 or 3) and the lock screen is up (`isKeyguardLocked`). Smart Lock / Extend Unlock does not matter. In addition, one of these: the screen is already on; the phone is on power; or (on battery) the trigger is the sensor reporting the phone turned sideways.

1. **Screen off on power.** The service polls once a second, for up to 30 s under a partial wake lock, until the lock screen is up, then shows the clock. The power button locks instantly on the M34 (clock 16 ms after `SCREEN_OFF`). A timeout uses the 5 s lock delay, during which nothing shows. Unlocking or turning to portrait cancels the poll.
2. **On battery.** Turning the phone sideways while it is locked wakes the screen with the simple clock. Screen-off and the idle timer never wake it. The screen times out normally.
3. **Launch.** The clock window is created upright over the portrait lock screen and then recreated sideways. The upright instance stays black (alpha 0, black window background), so only the landscape clock is ever visible.
4. **Unlock closes it.** While resumed, `ClockActivity` checks `isKeyguardLocked` every 300 ms and finishes as soon as it is false, unless it is a preview or a notification is being opened. `USER_PRESENT` is also handled, through an exported receiver.
5. **Preview.** The main screen's Preview button opens `ClockActivity` with `EXTRA_PREVIEW`. It shows on an unlocked phone, ignores unlock and portrait signals, and closes on double tap (without scheduling the idle return) or Back. If the phone is locked while a preview is open, the next standby launch reuses the instance as a normal lock-screen clock (`onNewIntent` clears the flag).
6. **Swipe.** Swiping the card clears every clearable notification of that app, group summaries included, through `cancelNotifications`. It logs `notifications swiped away <pkg> (<count>)`.
7. **Arcade.**
   - The muncher has no speed lines, is 36 dp, and is centred on its own coin, leaning 6 dp forward so its mouth is at the next coin. The mockup's 2 dp bob is on top of that.
   - Ghosts still ahead are solid red; eaten ghosts are outlines at 50% opacity.

Verified on the SM-M346B:
- The lock screen showed the clock even with Extend Unlock trusting the phone.
- The muncher sat on coin 82 with its mouth on coin 83.
- Ghost states were inverted as described.
- One swipe removed all 3 shell records (2 notifications + group summary), leaving 0 in `dumpsys notification`.
- Preview opened on the unlocked phone.
- The power button brought the clock up on the lock screen.
- The user confirmed the clock closes instantly on fingerprint unlock.

Failure paths:
- If the lock delay is longer than 30 s, a screen-off on power shows nothing until the next wake.
- On battery, turning the phone sideways in a bag while locked will light the screen until it times out. The user asked for this behaviour.

## no-branch (standby clock v8: one-tap Start/Stop, faster launch, charging layout)
Supersedes the Start/Stop button state handling of v5 and the launch step of v7 (2026-10-29). Conditions and triggers are unchanged.

1. **Start/Stop.** Each tap flips the buttons at once. The service broadcasts `STATE_CHANGED` when it actually connects or disconnects, and the main screen re-reads the state on that broadcast and on every resume. Stop stays in effect across app reinstalls until Start is tapped.
2. **Launch.**
   1. Screen off.
   2. The lock check runs every 250 ms (up to 30 s) until the lock screen is up.
   3. The full-screen intent is posted.
   4. SystemUI wakes the screen.
   5. The clock window appears black while still upright.
   6. Android rotates.
   7. The same activity instance receives `onConfigurationChanged` (no rebuild) and fades in over 150 ms.
3. **Charging layout.** Date top left (13 sp), digits at 1.08 × the content height (before fitting the width), 16 dp icons at the bottom, small CHARGING / % / EATEN in the top-right corner. On battery the layout is as before.

Verified on the SM-M346B:
- About 1.65 s from power button to clock: screen on +0.57 s, rotation +1.5 s.
- Charging layout screenshot shows the bigger digits with the date top left.
- The user confirmed Stop and Start each take one tap.
- Lint: 0 errors.

## no-branch (standby clock v9: bind watchdog, lock-on-timeout, genie)
Adds to v8; supersedes v5's Start sequence and the swipe animation of v6/v7 (2026-10-30).

1. **Start sequence.** Each tap does the first thing still missing:
   1. Notification access.
   2. Full-screen notifications (Android 14+).
   3. Notification permission (Android 13+).
   4. Device admin (lock screen only).
   5. Clear the `stopped_by_user` flag and run `ensureBound`: `requestRebind`, then toggle the `SensorService` component off and on.
   Stop sets `stopped_by_user` and calls `requestUnbind`.
2. **Watchdog.** `BindWatchdog` (JobScheduler id 1, every 15 min, persisted) runs `ensureBound`. It does nothing if the listener is live, the user pressed Stop, or access is missing. Each rebind it requests is logged as `rebind requested (start|watchdog)`. It is scheduled whenever the main screen opens.
3. **Lock on timeout.** On `SCREEN_OFF` while sideways and charging, if the lock screen is not up and the admin is active, `lockNow()` is called (`locked for standby`). The lock poll then shows the clock without waiting for the auto-lock delay.
4. **Genie swipe.** Swiping the card past 30% snapshots it, hides it, and pours it into its app icon with `GenieView`. The icon pops, then `clearApp` removes that app's notifications.

Failure paths:
- If the job is delayed by Doze, a dropped listener can stay down for up to the job's flex window. Opening the app or tapping Start fixes it immediately.
- Without the device admin, a timeout falls back to waiting for the auto-lock delay.

Verified on the API 37 emulator:
- Bound + background = adj 200/250.
- After `kill -9`, Android re-bound in about 5 s.
- The watchdog re-bound an allowed-but-unbound listener.
- Stop then Start through the buttons gives live 0 → 1.

Not verified on the emulator (no type-27 sensor): the standby trigger, lock-on-timeout and genie. They need the SM-M346B, which disconnected from adb.

**Update on the SM-M346B (2026-10-30):**
- `ACTION_DREAMING_STARTED` is handled like `SCREEN_OFF`, because a charging M34 starts the screen saver on timeout instead of turning off. Verified: screen saver at 10:25:32.532, clock requested at 10:25:32.826.
- The device admin was activated by the user from Start.
- The watchdog job is scheduled.
- After the install the listener was live.
- Still unverified on the device: the unlocked → timeout → `lockNow()` path, and the genie swipe.

## no-branch (standby clock v10: themes, DND, listener recovery, scenario script)
Adds to v9 (2026-10-30).

1. **Theme.** The main screen has a Theme button cycling Pac-Man / Super Mario. `ClockActivity.onStart` applies it to `ArcadeView`, `StandbyClockView` and the date. Super Mario uses brick digits, a coin loop, ? blocks and the user's sprites from `res/drawable-nodpi/mario_run_1.png` / `mario_run_2.png` (placeholder until added).
2. **DND.** The lock-screen clock showing turns on priority-only DND if it was off. The clock hiding restores it. A dead process's leftover DND is restored on the next connect.
3. **Card.** An opened card hides after 3 s unless it is being touched.
4. **Launch.** Every clock request cancels and re-posts the full-screen notification.
5. **Listener recovery.**
   - A 1-minute non-waking alarm and the 15-minute job check the listener.
   - If it is dead: toggle the component (works on stock Android), then post "Standby Clock stopped". The notification opens the app's notification-access page, where switching it off and on re-binds it on One UI. It is cleared on connect.
   - Start opens that page itself if the listener hasn't connected within 3.5 s.
6. **Notch.** The window covers the display cutout. The outer wall goes round each cutout rect with a 3 dp gap.

**Scenario script.** `scripts/standby-scenarios.sh` simulates charging with `dumpsys battery` and always resets it at the end. Precondition: the phone is locked and sideways. It checks:
1. Charging + power button → clock, keep-on, DND on.
2. Unplug → clock stays, keep-on off.
3. Battery + power button → screen stays off, DND restored.
4. Battery + wake → clock, no keep-on.
5. Re-plug → keep-on again.
Result on the SM-M346B with the final build: 11/11 PASS (an earlier run after a mid-standby reinstall failed 7/11, which led to the fresh-post and DND-persistence fixes).

Failure paths: on One UI a killed listener stays down until the user switches access off/on from the prompt. DND stays on in that window.

## Super Mario 2D Parallax Clock & Clean Charging Theme (v11)
Adds to v10 (2026-10-30).

1. **Parallax Horizon & Ground:**
   - 4-layer parallax scrolling: ground bricks (1.0x walking speed), bushes (0.65x), green hills (0.35x), drifting clouds (0.15x + time drift).
   - Mario walks continuously at ~30 fps cycling `mario_run_1` and `mario_run_2`.
2. **Sequential Block Punching on Time Change:**
   - Minute change only: Mario walks under the minute block, leaps up (`mario_jump` with raised hand), punches and bumps only the minute block to update its digit, lands, and walks back to center.
   - Hour and minute change: Mario walks under the hour block, jumps and punches it (only hour block bumps and updates digit), lands, walks across under minute block, jumps and punches it (only minute block bumps and updates digit), lands, and returns to center.
3. **Clean Charging Information:**
   - Stripped track runner, inner wall, and 100 extra collection coins / Goombas for Mario theme in `ArcadeView`.
   - Directly displays the lightning bolt, "CHARGING", battery level percentage (e.g. `41%`), and ETA (e.g. `until 95%`) in the top-right corner framed by outer brick wall.

## no-branch (standby clock v12: borderless pixel Mario, obstacles, icons on the bricks)
Adds to v11 (2026-10-30). Supersedes v11's runner sprites and the brick frame.

1. **Frame.** Mario theme: no outer wall. `ArcadeView` draws only the charging info, now in the top right corner in dark grey (`Theme.marioInk`) with the Material `bolt` icon. The date sits in the top left corner in the same ink. No 64 dp content inset.
2. **Rendering.** Each frame `MarioSceneView` draws sky, clouds, hills, bushes, bricks, ? blocks, obstacles and Mario into a low-resolution bitmap (one pixel per Mario pixel), scales it up unfiltered, then draws the dark grey digits at full resolution on top.
3. **Walking.** About every 33 ms `step()` runs:
   - When no punch is running, the world scrolls 0.2 dp/ms.
   - Spawns a pipe or Goomba just off the right edge every 0.5–1 screen widths, moves Goombas left, and drops obstacles that are off screen or were flattened over 0.5 s ago.
   - Applies gravity. Mario stands on a pipe he is over.
   - Auto-jumps a pipe ahead (16 px high), or hops (12 px) so he lands on a Goomba. Landing on a Goomba flattens it and bounces him.
   - The walk frame (1-2-3-2) changes every 4 Mario pixels walked.
4. **Block punch.** Same sequence as v11, but the world holds still while Mario walks to the block on screen. Obstacles are still jumped or stomped on the way.
5. **Icons.** After the scene is laid out, and on every power change, `ClockActivity.positionIcons()` centres the 20 dp icons on the first brick row and puts the card above them.

Failure paths: if the notification listener is unbound (after every reinstall on One UI), there are no icons. Rebind from the "Standby Clock stopped" prompt, or with `cmd notification allow_listener`.

## no-branch (standby clock v13: punch queue, tap-to-count, idle revert)
Adds to v12 (2026-10-30). Supersedes v11/v12's "Sequential Block Punching" and the left/right-half tap test.

1. **Punch queue.** `MarioSceneView` keeps a queue of punches, each a block and the value it shows after the hit. `nextPunch()` works through it:
   - Mario walks under the block (no walk if he's already there), jumps and bumps it, and the value changes at the apex.
   - When the queue is empty, he walks back to the centre. If the real time has moved on and no revert is pending, he punches that in first.
2. **Tap.** ACTION_UP inside a block (`blockAt`) queues +1 on that block, counted from its last queued value, and restarts a 5 s revert timer. `ClockActivity` keeps these touches away from the double-tap detector, so tapping blocks never closes the clock.
3. **Revert.** 5 s after the last tap, `reconcile()` queues one step per punch from each block's last queued value to the real time, hour first, the shortest way round.
4. **Minute tick.** Skipped while a revert is pending or a punch is running. Otherwise it calls `reconcile()`, which normally means one minute punch, plus an hour punch at the top of the hour.
5. **Detach.** The revert timer and queue are cleared. Cancelled animations do not chain into the next punch.
6. **Scale.** One Mario pixel is `height × 0.18 / 16`. Pipes are 10×6 and Goombas 10×8. Jumps are 10 px, capped below the blocks.

Test broadcasts: `TEST_MARIO_HOUR` / `TEST_MARIO_MINUTE` act as one tap on that block. `TEST_MARIO_BOTH` taps both.

## no-branch (standby clock v14: power-ups, full enemy and obstacle set)
Adds to v13 (2026-10-30). Supersedes v12/v13's "pipes and Goombas only" course and the 0.18 scale.

1. **Each frame (`step`)**
   - **Scrolling:** the world scrolls unless a punch runs, Mario is waiting at a Piranha Plant, or he is growing or shrinking.
   - **Spawning:** a weighted-random obstacle appears just off the right edge every 0.45–0.9 screen widths.
   - **Movement:** Goombas and Koopas walk left, shells slide left fast, and donut blocks fall once 0.5 s have passed since Mario stood on them. Flattened, knocked-out, fallen and off-screen obstacles are removed.
   - **Items and fireballs** move (below).
   - **Wear-off:** after 3 minutes the power level drops one step.
   - **Physics:**
     - Gravity applies. Mario stands on the tallest solid obstacle under him, and a note block springs him on landing.
     - On contact, an enemy is knocked out by the star, stomped when Mario is falling, and otherwise hurts him. Spikes and an up Piranha Plant hurt him; with the star the plant is removed.
     - On the ground, Fire Mario fires at a target ahead. Mario waits at a Piranha Plant that is up, and jumps the next obstacle (enemies timed to land on them), capped below the ? blocks.
2. **Items:** the first punch that lands with no item out spawns one: mushroom when small, flower when Super, star or 1-Up when Fire.
   - It rises out of the block over 0.5 s (drawn behind it), then hops sideways away from the centre and falls.
   - On the ground it slides towards Mario.
   - Touching Mario applies it. Off screen, or after 20 s, it is gone.
3. **Fireballs** fly 0.12 px/ms and bounce on the ground. They knock out the first enemy or plant they touch and vanish on any other obstacle.
4. **Hurt:** Mario drops to small, flashes for 2 s with no further hits, and bounces.
5. **Drawing order:** sky, clouds, smiling cloud, hills, bushes, bricks, items, ? blocks, Piranha Plants (clipped to above their pipe), obstacles, fireballs, Mario (palette swaps for Fire and star; flicker while changing size), "1UP".
6. **Charging info (Mario):** 1.4× text and Material bolt in the top right corner.

Failure paths: none new. Everything runs inside the view's frame loop and stops when the view is hidden or detached.

