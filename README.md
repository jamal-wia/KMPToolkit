<!-- Favicon / badges: fill in the Maven Central badge URL once the first module is released. -->

# KMPToolkit

A collection of small, independent Kotlin Multiplatform libraries (Android + iOS) — coroutines
dispatcher seams, haptics, audio record/playback, local scheduling, secure key-value storage,
platform utilities, permissions, notifications, session management, settings, raw accelerometer
readings, and a database-agnostic transactional outbox. Each module is published as its own
artifact — take only what you need — and the whole suite is released together on a single version,
so two KMPToolkit artifacts on one classpath can never come from different releases.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.jamal-wia/kmptoolkit-bom)](https://central.sonatype.com/namespace/io.github.jamal-wia)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
![Kotlin Multiplatform](https://img.shields.io/badge/kotlin-multiplatform-7F52FF)
![Android](https://img.shields.io/badge/platform-android-3DDC84)
![iOS](https://img.shields.io/badge/platform-ios-black)

## Why small modules instead of one library

Every artifact solves exactly one problem and depends on as few others as possible. You add
`kmptoolkit-haptics` without pulling in an outbox engine, a notification system, or Compose. See
[`docs/01-architecture.md`](docs/01-architecture.md) for the principles every module follows (no
bundled DI framework, no hardcoded consumer identifiers, no user-facing text) and why.

## Modules

| Artifact | What it solves | Depends on | Status | Docs |
|---|---|---|---|---|
| `kmptoolkit-bom` | Pins every artifact below to one version — import this first | — | Available | [install](#installation) |
| `kmptoolkit-coroutines` | Testable dispatcher seam (`AppDispatchers`) | — | Available | [docs](docs/kmptoolkit-coroutines/01-overview.md) |
| `kmptoolkit-coroutines-testing` | `TestAppDispatchers` double, for `testImplementation` | `coroutines` | Available | [docs](docs/kmptoolkit-coroutines/06-testing.md) |
| `kmptoolkit-logging` | Minimal tag/level logging interface + pluggable sinks, zero dependencies | — | Available | [docs](docs/kmptoolkit-logging/01-overview.md) |
| `kmptoolkit-haptics` | Haptic feedback, with the outcome reported rather than thrown | — | Available | [docs](docs/kmptoolkit-haptics/01-overview.md) |
| `kmptoolkit-haptics-testing` | `RecordingHapticFeedback` double, for `testImplementation` | `haptics` | Available | [docs](docs/kmptoolkit-haptics/06-testing.md) |
| `kmptoolkit-flashlight` | Camera-torch blink cue, with the outcome degraded to a no-op rather than thrown | — | Available | [docs](docs/kmptoolkit-flashlight/01-overview.md) |
| `kmptoolkit-flashlight-testing` | `RecordingFlashlight` double, for `testImplementation` | `flashlight` | Available | [docs](docs/kmptoolkit-flashlight/06-testing.md) |
| `kmptoolkit-audio-player` | Audio playback (`MediaPlayer` / `AVPlayer`) behind a pluggable engine | — | Available | [docs](docs/kmptoolkit-audio-player/01-overview.md) |
| `kmptoolkit-audio-player-testing` | `FakePlaybackEngine`, for `testImplementation` | `audio-player` | Available | [docs](docs/kmptoolkit-audio-player/06-testing.md) |
| `kmptoolkit-audio-recorder` | Audio recording (`MediaRecorder` / `AVAudioRecorder`), typed errors instead of throws | — | Available | [docs](docs/kmptoolkit-audio-recorder/01-overview.md) |
| `kmptoolkit-audio-recorder-testing` | `FakeAudioRecorder`, for `testImplementation` | `audio-recorder` | Available | [docs](docs/kmptoolkit-audio-recorder/06-testing.md) |
| `kmptoolkit-scheduler` | Exact-time one-shot local alarms | — | Available | [docs](docs/kmptoolkit-scheduler/01-overview.md) |
| `kmptoolkit-scheduler-testing` | `RecordingAlarmScheduler` double, for `testImplementation` | `scheduler` | Available | [docs](docs/kmptoolkit-scheduler/06-testing.md) |
| `kmptoolkit-storage` | Key-value storage, plain and encrypted, plus a stable device id | — | Available | [docs](docs/kmptoolkit-storage/01-overview.md) |
| `kmptoolkit-storage-testing` | `InMemoryKeyValueStorage`, for `testImplementation` | `storage` | Available | [docs](docs/kmptoolkit-storage/06-testing.md) |
| `kmptoolkit-platform` | Connectivity, device info, file picker, wake lock, crash log | `logging` | Available | [docs](docs/kmptoolkit-platform/01-overview.md) |
| `kmptoolkit-platform-testing` | Fakes for the platform seams, for `testImplementation` | `platform` | Available | [docs](docs/kmptoolkit-platform/06-testing.md) |
| `kmptoolkit-biometric` | Biometric gate with typed outcomes; prompt copy is yours | `platform` | Available | [docs](docs/kmptoolkit-biometric/01-overview.md) |
| `kmptoolkit-biometric-testing` | `ScriptedBiometricGate`, for `testImplementation` | `biometric` | Available | [docs](docs/kmptoolkit-biometric/06-testing.md) |
| `kmptoolkit-permission` | Runtime permission request flow | `platform`, `storage` | Available | [docs](docs/kmptoolkit-permission/01-overview.md) |
| `kmptoolkit-permission-testing` | `RecordingPermissionHandler`, for `testImplementation` | `permission` | Available | [docs](docs/kmptoolkit-permission/06-testing.md) |
| `kmptoolkit-notification` | Local notifications, channels, actions | `permission` | Available | [docs](docs/kmptoolkit-notification/01-overview.md) |
| `kmptoolkit-notification-testing` | `RecordingNotifier`, for `testImplementation` | `notification` | Available | [docs](docs/kmptoolkit-notification/06-testing.md) |
| `kmptoolkit-session` | Session lifecycle and teardown fan-out | `coroutines`, `logging` | Available | [docs](docs/kmptoolkit-session/01-overview.md) |
| `kmptoolkit-session-testing` | Recording cleaner and revoker, for `testImplementation` | `session` | Available | [docs](docs/kmptoolkit-session/06-testing.md) |
| `kmptoolkit-settings` | Font scale, theme mode, app language | `storage` | Available | [docs](docs/kmptoolkit-settings/01-overview.md) |
| `kmptoolkit-outbox` | Transactional outbox engine (storage-agnostic) | `logging` | Available | [docs](docs/kmptoolkit-outbox/01-overview.md) |
| `kmptoolkit-outbox-testing` | `InMemoryOutboxStore`, `OutboxStoreContract`, `FakeOutbox`, for `testImplementation` | `outbox` | Available | [docs](docs/kmptoolkit-outbox/06-testing.md) |
| `kmptoolkit-outbox-sqldelight` | SQLDelight-backed `OutboxStore`, the reference SPI implementation | `outbox` | Available | [docs](docs/kmptoolkit-outbox-sqldelight/01-overview.md) |
| `kmptoolkit-location` | Device geographic position: one-shot fix, continuous updates, service-enabled check | `logging` | Available | [docs](docs/kmptoolkit-location/01-overview.md) |
| `kmptoolkit-location-testing` | `FakeLocationProvider`, for `testImplementation` | `location` | Available | [docs](docs/kmptoolkit-location/06-testing.md) |
| `kmptoolkit-proximity` | Proximity sensor (`ProximitySensor` + `ProximityRule`) | — | Available | [docs](docs/kmptoolkit-proximity/01-overview.md) |
| `kmptoolkit-proximity-testing` | `FakeProximitySensor` double, for `testImplementation` | `proximity` | Available | [docs](docs/kmptoolkit-proximity/06-testing.md) |
| `kmptoolkit-downloader` | Resumable background-download engine (transfer-agnostic) | `logging` | Available | [docs](docs/kmptoolkit-downloader/01-overview.md) |
| `kmptoolkit-downloader-testing` | `FakeDownloader`, `FakeDownloaderStorage`, for `testImplementation` | `downloader` | Available | [docs](docs/kmptoolkit-downloader/06-testing.md) |
| `kmptoolkit-systembars` | Compose system-bar control with per-axis ownership | `platform` | Available | [docs](docs/kmptoolkit-systembars/01-overview.md) |
| `kmptoolkit-logging-overlay` | Compose in-app log overlay (debug builds only) | `logging` | Available | [docs](docs/kmptoolkit-logging-overlay/01-overview.md) |
| `kmptoolkit-accelerometer` | Raw accelerometer readings as a cold `Flow`, m/s² on both platforms | — | Available | [docs](docs/kmptoolkit-accelerometer/01-overview.md) |
| `kmptoolkit-accelerometer-testing` | `ScriptedAccelerometer` double, for `testImplementation` | `accelerometer` | Available | [docs](docs/kmptoolkit-accelerometer/06-testing.md) |

See [`docs/README.md`](docs/README.md) for the full documentation index and the recommended reading
order.

## Installation

Add the BOM to align every module on one version, then pull in only what you use:

```kotlin
dependencies {
    implementation(platform("io.github.jamal-wia:kmptoolkit-bom:<version>"))
    implementation("io.github.jamal-wia:kmptoolkit-coroutines")
}
```

> KMP module resolution needs `platform()` at every source set that declares the dependency, not
> just `commonMain` — see [KT-58759](https://youtrack.jetbrains.com/issue/KT-58759) if a version
> fails to resolve on a specific target.

## Quick start

```kotlin
class UserRepository(private val dispatchers: AppDispatchers) {
    suspend fun loadUsers(): List<User> = withContext(dispatchers.io) {
        // ...
    }
}

// production
val repository = UserRepository(DefaultAppDispatchers())
// tests — no real threads, no Dispatchers.setMain()
// TestAppDispatchers comes from kmptoolkit-coroutines-testing, added as testImplementation
val repository = UserRepository(TestAppDispatchers())
```

See [`docs/00-getting-started.md`](docs/00-getting-started.md) for a from-scratch walkthrough, and
each module's own `docs/<module>/02-getting-started.md` for a runnable example.

## Read next

- [`docs/README.md`](docs/README.md) — full documentation index and recommended reading order
- [`docs/01-architecture.md`](docs/01-architecture.md) — cross-cutting principles and why they exist
- [`CHANGELOG.md`](CHANGELOG.md) — release history
- [`CONTRIBUTING.md`](CONTRIBUTING.md) — how to contribute
- [`LICENSE`](LICENSE) — MIT
