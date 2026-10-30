# Decisions

## Prototype scaffold: plain Views, no Compose, versions unverified
- **Date:** 2026-10-29  **Branch:** none (not a git repository)
- **Decision:** `standby-clock/` starts as a plain-Views Kotlin app (AGP 8.13.0, Gradle 8.13, Kotlin 2.1.21, compileSdk/targetSdk 36, minSdk 28, package `com.standby.clock`). It contains a capability report and a foreground service that logs wake-up sensor events (PRD US-001, feasibility gate 2).
- **Why:** The first goal is to prove sensor delivery while unplugged and asleep. Compose adds plugin and version coupling that does not help that test. Gradle and AGP versions were not verifiable here (no Gradle on the machine, web search inconclusive); they may need bumping on first sync. Only SDK platform `android-37.0` was found installed, so Studio may need to download platform 36.
- **Rejected:** Compose now (deferred until the clock UI); hand-writing the Gradle wrapper jar (binary, Studio regenerates it from the properties file).
