<!-- Favicon / badges: fill in the Maven Central badge URL once the first module is released. -->

# KMPToolkit

A collection of small, independent Kotlin Multiplatform libraries (Android + iOS) — coroutines
dispatcher seams, haptics, audio record/playback, local scheduling, secure key-value storage,
platform utilities, permissions, notifications, session management, settings, and a
database-agnostic transactional outbox. Each module is published and versioned separately: take
only what you need.

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
| `kmptoolkit-coroutines` | Testable dispatcher seam (`AppDispatchers`) | — | Available | [docs](docs/kmptoolkit-coroutines/01-overview.md) |
| `kmptoolkit-coroutines-testing` | `TestAppDispatchers` double, for `testImplementation` | `coroutines` | Available | [docs](docs/kmptoolkit-coroutines/01-overview.md) |
| `kmptoolkit-logging` | Minimal tag/level logging interface + pluggable sinks, zero dependencies | — | Available | [docs](docs/kmptoolkit-logging/01-overview.md) |
| `kmptoolkit-haptics` | Haptic feedback, with the outcome reported rather than thrown | — | Available | [docs](docs/kmptoolkit-haptics/01-overview.md) |
| `kmptoolkit-haptics-testing` | `RecordingHapticFeedback` double, for `testImplementation` | `haptics` | Available | [docs](docs/kmptoolkit-haptics/06-testing.md) |
| `kmptoolkit-audio-player` | Audio playback (`MediaPlayer` / `AVPlayer`) behind a pluggable engine | — | Available | [docs](docs/kmptoolkit-audio-player/01-overview.md) |
| `kmptoolkit-audio-player-testing` | `FakePlaybackEngine`, for `testImplementation` | `audio-player` | Available | [docs](docs/kmptoolkit-audio-player/06-testing.md) |
| `kmptoolkit-audio-recorder` | Audio recording (`MediaRecorder` / `AVAudioRecorder`), typed errors instead of throws | — | Available | [docs](docs/kmptoolkit-audio-recorder/01-overview.md) |
| `kmptoolkit-audio-recorder-testing` | `FakeAudioRecorder`, for `testImplementation` | `audio-recorder` | Available | [docs](docs/kmptoolkit-audio-recorder/06-testing.md) |
| `kmptoolkit-scheduler` | Exact-time one-shot local alarms | — | Available | [docs](docs/kmptoolkit-scheduler/01-overview.md) |
| `kmptoolkit-scheduler-testing` | `RecordingAlarmScheduler` double, for `testImplementation` | `scheduler` | Available | [docs](docs/kmptoolkit-scheduler/06-testing.md) |
| `kmptoolkit-storage` | Key-value storage, plain and encrypted | — | Planned | — |
| `kmptoolkit-platform` | Connectivity, device info, file picker, wake lock, crash handler | `logging` | Planned | — |
| `kmptoolkit-biometric` | Biometric authentication gate | `platform` | Planned | — |
| `kmptoolkit-permission` | Runtime permission request flow | `platform`, `storage` | Planned | — |
| `kmptoolkit-notification` | Local notifications, channels, actions | `platform`, `permission` | Planned | — |
| `kmptoolkit-session` | Session lifecycle manager | `coroutines`, `logging` | Planned | — |
| `kmptoolkit-settings` | Font scale, theme mode, app language | `storage` | Planned | — |
| `kmptoolkit-outbox` | Transactional outbox engine (storage-agnostic) | `coroutines`, `logging` | Planned | — |
| `kmptoolkit-outbox-sqldelight` | SQLDelight-backed outbox store | `outbox` | Planned | — |
| `kmptoolkit-systembars` | Compose system bars controller | `platform` | Planned | — |
| `kmptoolkit-logging-overlay` | Compose in-app log overlay | `logging` | Planned | — |

"Planned" modules are tracked in `CHANGELOG.md` and land module-by-module — see
[`docs/README.md`](docs/README.md) for the full documentation index once a module is available.

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
