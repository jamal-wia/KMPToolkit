# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html) — with one addition: **before
1.0.0**, a breaking API change is called out explicitly under its own `Breaking` heading rather than
silently folded into `Changed`, since minor version bumps are not yet a compatibility guarantee.

## [Unreleased]

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
- Repository infrastructure: composite `build-logic` with `kmptoolkit.library` /
  `kmptoolkit.compose` / `kmptoolkit.publish` / `kmptoolkit.androidtest` convention plugins,
  version catalog, Maven Central publishing via the vanniktech plugin, `explicitApi()` +
  ABI validation, CI publish workflow.
