<p align="center">
  <img src="assets/icon.svg" alt="Standby Clock app icon" width="220" height="220">
</p>

<h1 align="center">Standby Clock</h1>

<p align="center">
  An Android lock-screen clock that appears when the phone is turned sideways, inspired by iOS StandBy.
</p>

## What it does

Turn the phone to landscape while the lock screen is up and Standby Clock takes over the screen with a large clock. The app is built around a notification listener, so it runs with no persistent notification.

- **Clock face.** Big overlapping Pac-Man-yellow Nunito Black digits with pellet-coloured colon dots, the date, and a battery indicator. Digits roll when the minute changes.
- **Notification icons.** One app icon per app with a clearable notification, shown along the bottom. Tap an icon to see the latest notification as a card. Tap the card to unlock and open the notification. Swipe the card sideways to clear all of that app's notifications.
- **Charging screen.** While plugged in, the Pac-Man theme shows the original 244-consumable maze and four-ghost cast beside the clock. An autopilot plays the level-1 rules; battery percentage limits how many pellets it may consume. Sprites include frightened ghosts, returning eyes, cherries, scores and lives.
- **Super Mario theme.** The main screen's Theme cards switch between Pac-Man and Super Mario. The Mario face is a scrolling pixel-art course: the time sits in two ? blocks that Mario punches when the minute changes, and tapping a block counts it up until Mario punches the real time back 5 seconds later. Notification icons sit on the ground bricks, and while charging the battery level shows in the top right corner.
- **Level complete.** When the phone stops charging while plugged in (full, or its own charge limit), the Mario scene ends the level: Mario jumps at a flagpole, slides down, walks into a castle, and fireworks go off. On the Pac-Man theme charging completion unlocks the remaining pellets; after the autopilot clears them, the maze flashes white and blue. A stall assist lets the autopilot finish. This is a battery-controlled clock adaptation, not a complete arcade emulator. The Preview: charging complete button on the main screen plays the chosen theme's ending on demand.
- **On battery.** A simple clock with date, digits, battery and icons. Turning the phone sideways while locked wakes the screen, and the screen then times out normally.
- **Dismiss.** Double tap closes the clock and shows the lock screen. It returns after 5 seconds, or on the next screen wake. Unlocking closes it immediately.

The trigger is the wake-up `device_orientation` sensor, so it works with the screen off and ignores the Auto-rotate setting.

## Requirements

- Android 9 (API 28) or newer. Target and compile SDK 36.
- JDK 21 for Gradle. The wrapper's daemon-JVM criteria select it, whichever JDK launches Gradle.
- A phone that exposes the wake-up `device_orientation` sensor. It has been tested on a Samsung SM-M346B (Galaxy M34).

## Build and install

The Android project lives in `standby-clock/`.

```
cd "standby-clock"
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Use `./gradlew installDebug` to install it on a connected device.

## Setup on the phone

Open Standby Clock. The Setup card lists the steps in order; only the next missing one is active, with its own button (the yellow **Continue setup** button does the same):

1. Notification access: enable Standby Clock in the settings screen that opens.
2. Full-screen notifications (Android 14 and newer): allow it for the app.
3. Notification permission (Android 13 and newer): allow the prompt.
4. Device admin: activate it. It only allows locking the screen, so a charging phone that times out sideways shows the clock straight away.
5. Start: the listener binds and the status turns **Running**.

On a fresh install that is up to five taps, returning to the app each time. Once set up, the status card shows **Running** and its button becomes **Stop**, which unbinds the listener. The Theme cards switch between Pac-Man and Super Mario. **Preview** shows the clock on an unlocked phone. **Share log** and **Clear log** export and wipe the event log.

If One UI drops the listener and won't re-bind it, a "Standby Clock stopped" notification opens the notification-access switch; turning it off and on restarts standby.

## Project layout

| Path | Contents |
| --- | --- |
| `standby-clock/app/src/main/java/com/standby/clock/` | `MainActivity`, `ClockActivity`, `SensorService`, `BindWatchdog`, `LockAdmin`, `Theme`, `StandbyClockView`, `ArcadeView`, `PacmanGame`, `MarioSceneView`, `MarioSprites`, `BatteryView`, `GenieView`, `EventLog` |
| `standby-clock/docs/Decisions.md` | Why each design choice was made, with rejected alternatives |
| `standby-clock/docs/Flows.md` | Step-by-step behaviour of each version, with failure paths |
| `standby-clock/docs/sprites/` | The Mario PNGs the pixel grids were sampled from (not packaged) |
| `standby-clock/scripts/standby-scenarios.sh` | adb script that checks charging and battery standby on a connected phone |

## Fonts

Nunito (clock digits) and Press Start 2P (arcade text) are bundled in `res/font`.

## License

[MIT](LICENSE)
