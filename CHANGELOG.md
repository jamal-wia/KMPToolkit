# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html) — with one addition: **before
1.0.0**, a breaking API change is called out explicitly under its own `Breaking` heading rather than
silently folded into `Changed`, since minor version bumps are not yet a compatibility guarantee.

## [Unreleased]

### Changed

- Every module now publishes exactly two Apple targets, `iosArm64` and `iosSimulatorArm64`. The
  legacy Intel-simulator target `iosX64` is no longer declared anywhere: it is superseded by
  `iosSimulatorArm64` on Apple-silicon Macs, Compose Multiplatform 1.11+ publishes no artifact for
  it, and dropping it cuts roughly a fifth off the suite's published-file count — which matters
  against Maven Central's per-namespace monthly limits (see `RELEASING.md`). No public declaration
  changed: the `.klib.api` diffs are confined to their `Targets:` header line. Consumers building
  for the Intel iOS simulator cannot use this suite.

## [1.0.0] - 2026-08-23

### Added

- `kmptoolkit-coroutines` — `AppDispatchers` dispatcher seam and its `DefaultAppDispatchers`
  production implementation.
- `kmptoolkit-coroutines-testing` — `TestAppDispatchers` double, published separately so
  `kotlinx-coroutines-test` stays off consumers' runtime classpath.
- `kmptoolkit-logging` — `Logger` / `LogLevel` / `LogSink` / `LoggerFactory`. No global logger
  state and no third-party logging dependency; bridge to Kermit, Timber or a crash reporter by
  writing a `LogSink`.
- `kmptoolkit-haptics` and `kmptoolkit-haptics-testing` — `HapticFeedback` reporting a typed
  `HapticResult` rather than throwing or failing silently. Vibrations are attributed as
  `USAGE_TOUCH`, so the user's system haptics settings actually govern them.
- `kmptoolkit-flashlight` and `kmptoolkit-flashlight-testing` — a `Flashlight` interface that
  blinks the camera torch in one of two `FlashPattern` rhythms, built as a deliberate mirror of
  `kmptoolkit-haptics`: same best-effort contract, same shipped test fake. Needs no permission on
  either platform — `CameraManager.setTorchMode` on Android requires none at all, and iOS only
  gates a capture session behind camera permission, which this module never opens — and every call
  degrades to a silent no-op on hardware with no flash unit rather than throwing.
- `kmptoolkit-audio-player` and `kmptoolkit-audio-player-testing` — `AudioPlayer` over a pluggable
  `PlaybackEngine` SPI, so the state machine lives in common code and is testable without a device.
  `MediaPlayer` and `AVPlayer` engines ship with it.
- `kmptoolkit-audio-recorder` and `kmptoolkit-audio-recorder-testing` — `AudioRecorder` with an
  8×6 transition table where every illegal call is inert and typed rather than thrown. Operations
  that can touch the filesystem suspend; operations that only move recorder state do not.
- `kmptoolkit-scheduler` and `kmptoolkit-scheduler-testing` — exact-time one-shot local alarms.
  A missing `SCHEDULE_EXACT_ALARM` downgrades to an inexact alarm and says so through
  `AlarmScheduleResult.Inexact` instead of failing or downgrading silently.
- `kmptoolkit-storage` and `kmptoolkit-storage-testing` — plain and encrypted key-value storage
  plus `DeviceIdProvider`. Encryption uses AndroidKeyStore and `Cipher` directly rather than Tink,
  which keeps roughly a megabyte out of every consumer. The iOS Keychain queries are built with
  `CFDictionaryCreateMutable`, not a bridged Kotlin `Map`, which iOS 26 rejects with `errSecParam`.
- `kmptoolkit-platform` and `kmptoolkit-platform-testing` — connectivity, device info, reduced
  motion, URL opener, file picker, screen wake lock, and a crash log store. Connectivity reports a
  tri-state so a missing `ACCESS_NETWORK_STATE` is never mistaken for being offline.
- `kmptoolkit-logging-overlay` — an on-screen log overlay wired through `kmptoolkit-logging`'s
  `LogSink`. Debug builds only: bounded buffer, no PII filtering, gating is the consumer's job.
- `kmptoolkit-permission` and `kmptoolkit-permission-testing` — a headless rationale → request →
  settings flow. The catalog is a closed enum of the three permissions whose mapping is exercised
  on both platforms; location and photo library are excluded because iOS models them in ways the
  status type cannot honestly express.
- `kmptoolkit-biometric` and `kmptoolkit-biometric-testing` — a biometric gate returning typed
  outcomes that distinguish no-hardware, not-enrolled, transient lockout and permanent lockout.
  Prompt copy has no defaults, so the library's words cannot ship by accident.
- `kmptoolkit-settings` — font scale, theme mode and app language over `kmptoolkit-storage`,
  exposed as `StateFlow`s. The font scale is a validated multiplier rather than a fixed set of
  named steps and the language is a canonicalised BCP 47 `LanguageTag` rather than an enum, so
  neither the donor app's typography scale nor its language list ships to anyone. A failed write
  leaves the flow untouched and returns a typed error instead of showing a choice that reverts at
  the next launch, and everything that could not be read is reported in `SettingsLoad.problems`.
  `LanguageApplier` applies the language through the framework `LocaleManager` on Android 13+, the
  process locale defaults below it, and `AppleLanguages` on iOS — without an `androidx.appcompat`
  dependency.
- `kmptoolkit-systembars` — Compose system-bar control built as a base configuration plus a stack
  of per-axis override layers, so leaving a screen removes exactly that screen's contribution
  instead of restoring a snapshot that may since have gone stale.
- `kmptoolkit-notification` and `kmptoolkit-notification-testing` — local notifications. A
  `NotificationChannelSpec` keeps Android's concept and documents per field what iOS actually does
  with it, rather than pretending a `UNNotificationCategory` is a channel: a category declares
  action buttons and has no name, sound, importance, or mute switch. Posting returns the first
  reason a user would not have seen the notification.
- `kmptoolkit-session` and `kmptoolkit-session-testing` — session state plus a `SessionCleaner`
  fan-out that runs every cleaner even when others fail, reports failures instead of swallowing
  them, and ends the session regardless. A failing `SessionRevoker` never blocks local teardown,
  so signing out works offline.
- `kmptoolkit-bom` — pins every artifact to one version. Its constraint list is derived from the
  projects that exist rather than maintained by hand.
- `kmptoolkit-outbox` and `kmptoolkit-outbox-testing` — a transactional outbox: a durable effect
  queue with per-handler retry policies, strict FIFO ordering channels, constraint gating, and
  detached delivery under a self-expiring lease. **Storage is an SPI, not a dependency** — the
  module ships no database at all, which is the one thing that made the donor's otherwise
  well-tested engine unreusable. `kmptoolkit-outbox-testing` ships a complete `InMemoryOutboxStore`
  and `OutboxStoreContract`, a runnable check of every invariant a store must hold, so a custom
  store can prove itself rather than be reviewed.
- `kmptoolkit-outbox-sqldelight` — the reference implementation of the outbox storage SPI, over
  SQLite. Ordering uses an `AUTOINCREMENT` sequence rather than a timestamp, so two enqueues in the
  same millisecond keep their order and a rowid freed by a delivered item is never reused beneath a
  waiting one. It passes all 30 checks of `OutboxStoreContract` unmodified on both platforms.
- `kmptoolkit-accelerometer` and `kmptoolkit-accelerometer-testing` — raw accelerometer readings
  as a cold `Flow<AccelerometerSample>`, registering the platform sensor on collection and
  releasing it on cancellation rather than on an explicit `close()`. Both platform factories take
  a `samplingInterval: Duration` (200 ms default on each) instead of a hardcoded rate, and iOS
  scales Core Motion's readings from g to m/s² so both platforms report the same unit.
  `kmptoolkit-accelerometer-testing` ships `ScriptedAccelerometer`, which replays a canned sample
  list per collector and counts registrations so a test can prove a collector was actually
  released.
- `kmptoolkit-location` and `kmptoolkit-location-testing` — platform-agnostic access to the
  device's geographic position: a one-shot suspend fun for a single fix, a `Flow` for continuous
  updates, and a suspend check for the device-wide location service toggle, independent of the
  app's permission. Raw coordinates only — no caching, no permission UI. The Android side is plain
  `android.location.LocationManager` rather than Play Services' `FusedLocationProviderClient`, the
  same reasoning that keeps `kmptoolkit-storage` off Tink: no other module here depends on Play
  Services, and the fix-quality gain was not worth becoming the first one that does. Both
  platforms cap a single-fix request with a timeout, so a device with no signal cannot suspend
  `getCurrentLocation` forever.
- `kmptoolkit-proximity` and `kmptoolkit-proximity-testing` — a proximity sensor seam:
  `ProximitySensor` reports a cold, event-driven `Flow<Boolean>`, and `ProximityRule.isNear` folds
  a raw distance into it by comparing against the smaller of a five-centimetre threshold and the
  sensor's own maximum range, since most units are binary and answer only `0` or their own
  maximum. iOS has no proximity API to build on at all — Core Motion exposes none, and
  `UIDevice`'s own monitoring is iPhone-only and tied to blanking the screen — so there
  `isAvailable` is permanently `false` rather than a gap this module could close later.
- `kmptoolkit-downloader` and `kmptoolkit-downloader-testing` — a resumable background-download
  engine for the assets an app can't ship inside its binary. **The platform transfer itself is an
  SPI, not a dependency** — the module ships no HTTP client, only storage (Android and iOS) and the
  retry/commit state machine around it. Nothing about an in-flight download is persisted except a
  stall counter: "is a transfer running" is answered by the transfer SPI, "is it done" by the file
  on disk, so a process crash needs no recovery pass — the next `ensureAvailable` call re-derives
  everything from those two questions and resumes from whatever temp file survived. A resource is
  also verified, not merely present, before it counts as committed: a `ZipArchive` unit proves
  itself by an extraction marker, and a `SqliteDatabase` unit can carry its own expected row count
  for the engine to check the file against. `kmptoolkit-downloader-testing` ships `FakeDownloader`
  and `FakeDownloaderStorage`.
- Repository infrastructure: composite `build-logic` with `kmptoolkit.library` /
  `kmptoolkit.compose` / `kmptoolkit.publish` / `kmptoolkit.androidtest` convention plugins,
  version catalog, Maven Central publishing via the vanniktech plugin, `explicitApi()` +
  ABI validation, CI publish workflow.
