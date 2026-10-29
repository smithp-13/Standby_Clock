<p align="center">
  <img src="assets/icon.svg" alt="Standby Clock app icon" width="120" height="120">
</p>

<h1 align="center">Standby Clock</h1>

<p align="center">
  An Android lock-screen clock that appears when the phone is turned sideways, inspired by iOS StandBy.
</p>

## What it does

Turn the phone to landscape while the lock screen is up and Standby Clock takes over the screen with a large clock. The app is built around a notification listener, so it runs with no persistent notification.

- **Clock face.** Big overlapping coral and pink Nunito Black digits, the date, and a battery indicator. Digits roll when the minute changes.
- **Notification icons.** One app icon per app with a clearable notification, shown along the bottom. Tap an icon to see the latest notification as a card. Tap the card to unlock and open the notification. Swipe the card sideways to clear all of that app's notifications.
- **Charging screen.** While plugged in, the clock switches to an arcade-style frame: a muncher eats one dot for each percent of battery, with a ghost at 25, 50, 75 and 100 percent.
- **On battery.** A simple clock with date, digits, battery and icons. Turning the phone sideways while locked wakes the screen, and the screen then times out normally.
- **Dismiss.** Double tap closes the clock and shows the lock screen. It returns after 10 seconds, or on the next screen wake. Unlocking closes it immediately.

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

Open Standby Clock and tap **Start**. Each tap handles the first thing still missing:

1. Notification access: enable Standby Clock in the settings screen that opens.
2. Full-screen notifications (Android 14 and newer): allow it for the app.
3. Notification permission (Android 13 and newer): allow the prompt.
4. Start: the listener binds and standby is running.

On a fresh install that is up to four taps, returning to the app each time. **Stop** unbinds the listener. **Preview** shows the clock on an unlocked phone. **Share log** and **Clear log** export and wipe the event log.

## Project layout

| Path | Contents |
| --- | --- |
| `standby-clock/app/src/main/java/com/standby/clock/` | `MainActivity`, `ClockActivity`, `SensorService`, `StandbyClockView`, `ArcadeView`, `BatteryView`, `GenieView`, `EventLog` |
| `standby-clock/docs/Decisions.md` | Why each design choice was made, with rejected alternatives |
| `standby-clock/docs/Flows.md` | Step-by-step behaviour of each version, with failure paths |

## Fonts

Nunito (clock digits) and Press Start 2P (arcade text) are bundled in `res/font`.

## License

[MIT](LICENSE)
