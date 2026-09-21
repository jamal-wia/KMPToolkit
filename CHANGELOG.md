# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html) — with one addition: **before
1.0.0**, a breaking API change is called out explicitly under its own `Breaking` heading rather than
silently folded into `Changed`, since minor version bumps are not yet a compatibility guarantee.

## [Unreleased]

### Added

- `kmptoolkit-video-player` — a headless `VideoPlayer` for shared code, with the contract of
  `kmptoolkit-audio-player` (`prepare` suspends and never throws on failure, a newer `prepare`
  replaces an older one, transport calls outside a playable state are ignored, `release` is
  idempotent and final) plus what a video needs: `volumeFlow`/`isMutedFlow`, `RepeatMode`,
  `isBufferingFlow`, `bufferedPositionFlow` and `videoSizeFlow`. Sources are `Asset`, `File` and
  `Remote` with optional HTTP headers. One state machine in common code drives a
  `VideoPlaybackEngine`: Media3 ExoPlayer (with HLS) on Android and `AVPlayer` on iOS. Also publishes
  `jvm` so shared UI compiles for desktop; the desktop engine is a separate artifact. Media3 merges
  `ACCESS_NETWORK_STATE` and `WAKE_LOCK` into the consuming app's manifest; `INTERNET` stays the
  app's to declare.
- `kmptoolkit-video-player-testing` — `FakeVideoPlaybackEngine`, a scriptable engine for testing
  code that consumes `VideoPlayer` without a device or a video file.
- `kmptoolkit-video-player-compose` — `VideoPlayerSurface` (scale modes `Fit`/`Fill`/`Crop`,
  keep-screen-on while playing) on Android, iOS and desktop, and `VideoPlayer` with ready-made
  controls — tap to toggle, auto-hide, pause when the host goes to the background — whose every part
  can be replaced: a `controls` slot over `VideoControlsScope` (used freely; implementing it is
  opt-in, `@InternalForInheritanceVideoControlsApi`, as it may grow), public building blocks
  (`PlayPauseButton`, `VideoSeekBar`, `VideoTimeText`, `MuteButton`, `SpeedButton`,
  `FullscreenButton`, …), and plain `VideoControlsColors` / `VideoControlsDimensions` /
  `VideoControlsLabels` objects. No user-facing text: accessibility labels come from the app.
  `rememberVideoPlayer` creates, prepares and releases a player; `LocalVideoPlayerFactory` lets a
  desktop app choose its engine once at the root.
- `kmptoolkit-video-player-vlcj` — a JVM-only desktop engine on VLCJ: any format VLC plays, frames
  rendered into memory for the Compose surface. VLCJ is GPL-3.0 and needs VLC 3.x installed (or
  bundled) for the JVM's CPU architecture; `isVlcAvailable()` checks without crashing.
- `kmptoolkit-video-player-javafx` — a JVM-only desktop engine on JavaFX Media: nothing to install
  beyond the OpenJFX jars the app adds per OS (GPL-2.0 + Classpath Exception), fewer formats, and a
  higher CPU cost because frames are captured by off-screen snapshots.
- `kmptoolkit.library.jvm` — a build convention for JVM-only modules.

## [1.6.0] - 2026-09-16

### Added

- `kmptoolkit-hijri` — `HijriDate` and `LocalDate.toHijriDate()`, converting a civil date to the
  Umm al-Qura calendar. Android goes through `android.icu.util.IslamicCalendar` with the
  calculation type pinned to `ISLAMIC_UMALQURA` rather than left to the locale; iOS goes through
  `NSCalendar(NSCalendarIdentifierIslamicUmmAlQura)` rather than `Calendar.current`, so the
  answer does not follow the device's calendar setting. Both read in UTC, which is what keeps
  the conversion date-to-date. Desktop goes through the JDK's `java.time.chrono.HijrahDate`,
  which ships as Umm al-Qura and nothing else, so `jvm` is published too and a screen shared
  with a desktop build compiles. One contract object in `commonTest` is run by all three
  platform test classes, so the three readings of the tables are checked against each other.
  No test double is published — the function is pure.

## [1.5.0] - 2026-09-13

1.4.0 was never published; its changes ship in this release and are listed here.

### Added

- `kmptoolkit-hardware-keys`, a new Compose module: `DialogWindowHardwareKeyEffect()` keeps the app's
  hardware-key policy in force inside Compose dialog windows, bottom sheets and focusable popups on
  Android, where an Activity's `onKeyDown` never sees keys, and `DialogWindowHardwareKeyPolicy`
  registers that policy once for the process. A no-op on iOS; also publishes `jvm`, so shared dialog
  code compiles for desktop.
- `kmptoolkit-storage`: typed accessors — `getInt` / `getLong` / `getBoolean`, their `putInt` /
  `putLong` / `putBoolean` counterparts, and `getStringOr` / `getIntOr` / `getLongOr` / `getBooleanOr`
  with a default. One encoding for every caller (decimal strings, `"true"`/`"false"`); a stored
  value that does not parse is a `GET` failure rather than an absent key.
- `kmptoolkit-storage` and `kmptoolkit-storage-testing` also publish `jvm`, so shared code taking a
  `KeyValueStorage` compiles for desktop. `createKeyValueStorage(directory, config)` is a plain store
  in one properties file with atomic writes; there is no secure store on desktop.
- `kmptoolkit-permission`: `Permission.LOCATION`, `LOCATION_BACKGROUND`, `MEDIA_AUDIO` and
  `BLUETOOTH_CONNECT`, each with a stated fold onto `PermissionStatus` on both platforms. Location is
  requested as fine and coarse in one dialog, through a new multi-permission
  `PermissionRequestHost.launch(List, ...)` (default body: a one-element list goes to the single
  `launch`, anything larger is not launched). **An exhaustive `when` over `Permission` needs the new
  entries**; the change is binary-compatible. iOS apps linking the module link CoreLocation,
  CoreBluetooth and MediaPlayer — see `05-platform-notes.md` for the purpose strings.
- `kmptoolkit-permission`: `PermissionHandler.observe(permission)` — the status now and on every
  change, re-read on activity resume (Android), on becoming active (iOS) and after a request. The
  member has a default body emitting the current status once. `RecordingPermissionHandler` emits on
  every scripted change.
- `kmptoolkit-biometric`: `BiometricGateOptions` and a `createBiometricGate(context, config, options)`
  overload (and an iOS `createBiometricGate(config, options)`, where the options change nothing) —
  the weak sensor tier (`BiometricStrength.WEAK`, e.g. camera face unlock), one sensor attempt per
  call (`singleAttempt`: the first non-match ends the prompt with `Rejected`), and an enrolment
  throttle. New `BiometricGate` members with default bodies: `authenticate(prompt,
  requireExplicitConfirmation)` for a per-call confirming tap, and `launchEnrollment()`, which on
  Android opens the enrolment wizard or — for a user already enrolled, for whom the wizard closes
  itself — the biometrics management screen. `ScriptedBiometricGate` records both.
- `kmptoolkit-uploader`: `UploadHandler`, an `UploaderHandler` whose delivery is a multipart upload.
  `prepareUpload` runs at hand-off and again when the platform starts the upload, `classify` turns the
  raw outcome into a settlement with the payload at hand, and `onUploadProgress` / `onDelivered` /
  `onSettled` hooks run isolated. `UploadGateway` is how a transport prepares, reports progress and
  settles such an item. `UploadTransport.launch(itemId, isRehandOff, request)` has a default body.
- `kmptoolkit-uploader` (Android): `createWorkManagerUploadHandlerTransport` and `UploadHandlerWorker` —
  the WorkManager job stores only the item id, so no request or `Authorization` header is persisted in
  WorkManager's database, a re-run of a settled item uploads nothing, and progress is reported. The
  worker is open so an app can keep an earlier worker's class name for jobs already queued on devices.
- `kmptoolkit-uploader` (iOS): `createBackgroundUploadTransport` — one background `NSURLSession` per
  item that keeps uploading after the app is killed — and `BackgroundUploadRelaunch.handleEvents` for
  the app delegate's `handleEventsForBackgroundURLSession`.
- `kmptoolkit-uploader-testing`: `RecordingUploadTransport`.
- `kmptoolkit-audio-player`: `AudioPlayer.unload()` frees the loaded source's native handle but keeps
  the player usable, for a player that outlives the screens borrowing it — `release()` stays the
  permanent teardown. The member has a default body calling `stop()`, so the change is
  binary-compatible and an existing implementation keeps compiling — unless it already declares a
  `fun unload()` of its own, which then needs `override`. A decorator should forward it.
- `kmptoolkit-scheduler` (Android): a `createAlarmScheduler(context, handlerProvider, config)`
  overload. Handlers are looked up when an alarm fires instead of being handed over at creation, so a
  handler that needs the scheduler itself no longer forms a construction cycle in a DI container.
- `kmptoolkit-notification`: `NotificationOptions` and a `Notifier.post(id, notification, options)`
  overload — `alertOnce` (turn it off for a reminder that must sound on every re-post), a
  `dismissAction` sent when the notification is swiped away, the media layout's collapsed button row,
  and per-action icons. Android only; iOS ignores them. The new member has a default body calling the
  two-argument `post`, so existing implementations keep compiling (one that already declares a
  `post(String, LocalNotification, NotificationOptions)` of its own needs `override`); a decorator
  must forward it.
  `RecordingNotifier` records the options on `PostedNotification.options`.
- `kmptoolkit-notification` (Android): `notificationIdOf(id)`, `buildForegroundNotification(...)` and
  `NotificationChannels.ensure` / `delete`. A foreground service starts with a notification rendered
  exactly as `post` renders it — same buttons, same dismissal, same tap target — and later posts under
  the same id update it. Channels can be created at startup and retired. The module now depends on
  `androidx.media` (implementation scope) for the media layout.
- `kmptoolkit-location` (iOS): `LocationProvider.withSystemServicesPrompt()`. Its
  `promptToEnableService()` asks iOS to show its own "Turn On Location Services" alert and answers
  `PROMPTED`. `FakeLocationProvider` gains `servicePromptAnswer` and `promptCount`.
- `kmptoolkit-permission` (Android): a `createPermissionHandler(context, host, storage, activityAccess,
  ...)` overload, for an app that already owns an `ActivityAccess`. `kmptoolkit-activity` becomes an
  `api` dependency accordingly.
- Documentation: `kmptoolkit-location` explains why it stays off Google Play Services and gives a
  complete recipe for a `FusedLocationProviderClient` decorator, including the in-place "turn on
  location" dialog.

### Fixed

- `kmptoolkit-uploader` (iOS): `BackgroundTaskWakeScheduler.handleWake` calls `onDone` even when the
  drain throws, so iOS always hears that the background task finished.
- `kmptoolkit-permission` (Android): on Android 11+, backing out of the **first** permission dialog —
  back, or a tap outside it — returned `PermanentlyDenied`, and every later `request` skipped the
  dialog, although Android would still show it. The handler now also remembers whether the user ever
  refused through the dialog (a second key, `<prefix>.rationale.<PERMISSION>`), so a dismissal reads
  `NotDetermined`. A request whose answer arrives before the activity is resumed waits for it before
  reading the rationale, and no permanent verdict is drawn when there is no activity to ask.
  Behaviour changes that follow from it:
  - The asked flag is now written as `dialog-shown`. A flag an earlier version wrote (`true`) keeps
    being read as those versions read it, so a permission already recorded as permanently denied
    stays so; the fix applies once the user grants it, or on a fresh install.
  - A permission the user refused before the app ever asked — in system settings — or one missing
    from the manifest now reads `NotDetermined`, and `request` returns at once: without a dialog
    Android reports no rationale, exactly as for a dismissal.
  - `check` now writes the refusal flag the first time Android reports a rationale, once per
    permission.
- `kmptoolkit-audio-player`: a `prepare` that arrived while another was still loading broke both — on
  Android the first call never returned, on iOS it failed with an error that overwrote the second
  one's state. Loads are now serialized: the newer one cancels the older, waits for it to unwind, and
  owns the outcome; the replaced call returns normally.
- `kmptoolkit-audio-player` (iOS): the playback speed was not applied when playback started or
  resumed, because the rate was pushed only while `AVPlayer` already reported itself playing.
- `kmptoolkit-audio-player`: `play()` from `Completed` starts over from the beginning on both
  platforms, as `MediaPlayer` already did; `AVPlayer` used to sit at the end in a `Playing` state. The
  documentation said it resumed at the end.
- `kmptoolkit-audio-player` (Android): a load cancelled while `MediaPlayer` was still attaching its
  source leaked the handle, and an error reported right after "prepared" could resume the load twice.
- `kmptoolkit-audio-recorder`: cancelling `prepare` while its storage checks were pending left the
  recorder in `Preparing`, from which nothing — `prepare` included — was legal again. A `stop` or
  `cancel` whose caller was cancelled mid-way left the state `Recording` over a stopped engine; both
  now run to completion; the caller observes its cancellation afterwards.
- `kmptoolkit-scheduler` (Android): `schedule` threw when `AlarmManager` refused an alarm — Android 12+
  caps an app at 500 — despite promising never to throw for a platform refusal. It now returns
  `Failed(PlatformError)`.
- `kmptoolkit-notification`: a progress update whose title, body or buttons changed was coalesced
  like any other frame in the same bucket, leaving stale text on screen until the bar crossed into
  the next one. The bucket now holds back only frames that differ in nothing but the percentage; the
  rate limit (`minProgressInterval`) still applies to every determinate frame.
- `kmptoolkit-notification` (Android): below API 33 a post consulted the `PermissionHandler` for
  `POST_NOTIFICATIONS`, which does not exist there, so a handler answering "denied" silenced every
  notification on older devices. The check is skipped below 33.
- `kmptoolkit-location` (iOS): `openLocationSettings()` reached `UIApplication` on the calling thread;
  it now hops to the main thread, as the "any thread" contract promises.
- `kmptoolkit-location` (iOS): `observeLocation()` held its `CLLocationManager` delegate only weakly,
  so after a garbage collection updates could stop while the flow stayed open.
- `kmptoolkit-activity` (Android): a `withActivity` on another thread that found a finishing activity
  could clear an activity that resumed at the same moment.
- `kmptoolkit-scheduler` (Android): a handler provider throwing a checked exception crashed the process
  instead of dropping the alarm, as documented.
- Documentation: `kmptoolkit-activity` said an untracked activity resuming over a tracked one left the
  tracked activity reachable underneath. The tracked activity is paused while covered, so
  `withActivity` answers `null` until it resumes. `kmptoolkit-notification` said the iOS notifier
  overrides the three-argument `post`; it keeps the default.
- Documentation: `kmptoolkit-biometric` said `ComponentActivity` can host the prompt. It is
  `FragmentActivity`'s superclass — and a Compose activity's default base — so `authenticate` from it
  returns `NoPromptHost`. `kmptoolkit-audio-player` said the playback audio session keeps audio going
  at screen lock; that needs the `audio` background mode.

## [1.3.0] - 2026-09-13

### Added

- `kmptoolkit-haptics`: `HapticFeedback.isAvailable` answers whether the device can play haptics at
  all **without** playing anything — `perform` only reports `UNAVAILABLE` after it has already
  tried, which is too late for a decision such as "does this attention cue have any way to reach the
  user". Android reads `hasVibrator()`, iOS answers `true` (UIKit cannot tell), `noOpHapticFeedback()`
  answers `false`. The member has a default getter returning `true`, so the change is
  binary-compatible and an existing implementation keeps compiling — unless it already declares a
  property named `isAvailable`, which then needs `override`. A decorator should forward it. `RecordingHapticFeedback` gains a matching
  `isAvailable` property and a `(result, isAvailable)` constructor.
- `kmptoolkit-haptics` (Android): `HapticAttribution` and a `createHapticFeedback(context,
  attribution)` overload. `TOUCH` is what every instance did until now and remains the default;
  `NONE` plays the unattributed `vibrate(effect)`, which the user's touch-feedback switch does not
  silence — for a vibration that is not a reaction to a touch, and for an app adopting the module
  whose vibrations were unattributed before. The single-argument factory is unchanged. Purely
  additive.
- Documentation: `kmptoolkit-downloader` explains how an app that already stored downloads in the
  same layout under its own directory name keeps them — `DownloaderStorageConfig(baseDirectoryName =
  ...)` — and what happens to its users if it does not. Pinned by new Robolectric tests; no code
  change.

### Fixed

- `kmptoolkit-flashlight`: the promise that `Flashlight` is safe to call from any thread did not
  hold. The running blink job lived in a plain field, so two `start`s racing from different threads
  could each install a loop, leaving one blinking that no `stop` could reach — a torch that never
  goes dark. A new pattern's first flash could also be cut short by the previous loop's final "off",
  after either a replacing `start` or a `stop` just before it. Both platforms now share one blink loop
  that swaps the job atomically and makes every job wait for the one it replaced to finish; nothing
  in the public API changes. On iOS the torch is also looked up once per `start` instead of on every
  switch.
- `kmptoolkit-accelerometer` (iOS): two concurrent collections of one instance broke each other.
  `CMMotionManager` has a single update handler, so the second collection replaced the first one's,
  and whichever ended first called `stopAccelerometerUpdates` and silenced the other. An instance now
  runs one update stream that starts with the first collection, stops with the last, and delivers
  every sample to all of them — which is what Android already did.
- `kmptoolkit-accelerometer` (Android): a `samplingInterval` below 4 µs reached
  `SensorManager.registerListener` as `0`–`3`, which the platform reads as its `SENSOR_DELAY_*`
  constants — 3 µs meant `SENSOR_DELAY_NORMAL`, 200 ms — and one longer than `Int.MAX_VALUE` µs
  overflowed into a negative period. Short and non-positive intervals now request
  `SENSOR_DELAY_FASTEST`, and long ones are capped.
- Documentation: `kmptoolkit-accelerometer` and `kmptoolkit-proximity` said sensor callbacks arrive
  on the collecting thread's looper. With no `Handler` passed, `SensorManager` delivers them on the
  main looper. `kmptoolkit-proximity` also said an absent sensor's `observe()` never completes; the
  always-absent iOS implementation completes at once, which is now stated.

## [1.2.0] - 2026-09-13

### Added

- `kmptoolkit-language` and `kmptoolkit-language-compose` now also publish a **`jvm` (desktop)
  target**. `AppLanguage` is a type consumers put in the public API of their shared modules — a
  settings repository, a language picker, a screen's state — and a module compiled for desktop could
  not mention it at all while it resolved on only Android and iOS. Unlike the system-bars desktop
  target this is not a no-op: `applyLanguageGlobally` sets the JVM default locale for every category,
  `AppLanguage.System` restores the operating system's language, and `getSystemLanguageCode` reads it
  from the `user.*` system properties, which `Locale.setDefault` does not rewrite. `AppLocale`
  re-pins the default if something else moved it and keys the composition on the language code,
  since a desktop string resource is resolved only when its call composes. The desktop-target
  exception and its bar are restated in `docs/01-architecture.md` § "Desktop targets". Purely
  additive.

### Fixed

- `kmptoolkit-language-compose`: on Android, `AppLocale` provided `LocalContext` as the bare result of
  `createConfigurationContext`. On an activity that delegates to the underlying `ContextImpl`, so the
  context content saw was not the activity or a wrapper around it, and carried the device-default
  theme instead of the activity's: `LocalActivity` and every find-the-activity walk returned `null`
  under `AppLocale`, and an Android View hosted in Compose (a video player, a map) was inflated under
  the wrong theme. It is now the host context wrapped, with only its resources localized — the
  activity stays at the end of the chain and its theme stays in effect.
- Documentation: Android controllers must be created before the first activity resumes, and the
  docs never said so. The activity tracker learns which activity is current only from
  `onActivityResumed`, and Android offers no public way to ask for one that resumed before
  registration — so a controller created later, such as a lazy DI singleton first resolved by the
  theme during composition (which on Android runs after `onResume`), could not style the window on
  screen until the next resume. The contract is now stated on `createSystemBarsController`,
  `createScreenWakeLockController` and `createActivityAccess`, and in both getting-started guides;
  `ControllerCreationOrderTest` pins it, including the bounded recovery on the next resume.
- Documentation: the Android platform notes recommended the no-argument `enableEdgeToEdge()`, which
  installs a translucent navigation-bar scrim on API 26–28 and turns contrast enforcement back on for
  API 29+. Because this module deliberately never calls `enableEdgeToEdge`, following the docs left a
  faint band behind the navigation bar on three-button devices. The notes now give the two lines that
  avoid it.
- Documentation: the iOS platform notes now cover SwiftUI apps, whose window root is a hosting
  controller that never asks an embedded Compose view controller about the status bar. They show
  making the host the root from a `UIWindowSceneDelegate`, and warn off the app-delegate-owned window,
  which leaves the UIScene lifecycle and builds the UI on background launches. The note on activity
  recreation, which claimed a replay to an activity resumed before the controller existed, now agrees
  with the creation-order contract, as does the matching KDoc in `kmptoolkit-activity`.
- KDoc: `applyLanguageGlobally` said `AppLanguageHolder` applies only on a `setLanguage` that changes
  the language; it applies on every call. `StatusBarLuminanceProbe` pointed screens at
  `setStatusBarStyle` / `setNavigationBarStyle`, which do not exist in this API.

## [1.1.0] - 2026-09-12

### Added

- `kmptoolkit-systembars`: `IosSystemBarsController.prefersHomeIndicatorAutoHidden`, to be returned
  from your host's override of the same name. iOS has no navigation bar, so
  `SystemBarsVisibility.isNavigationBarVisible` was inert there; it now drives the home indicator,
  which is the nearest thing a cross-platform "hide the bottom bar" claim can mean on that platform —
  `SystemBarsVisibility.Immersive` hides both bars on Android and the status bar plus the home
  indicator on iOS. The controller invalidates it alongside the status bar on every configuration
  change. Added as a **default interface member**, so no existing implementation of
  `IosSystemBarsController` needs to change. Purely additive.
- New `kmptoolkit-activity` module: `ActivityAccess`, `ActivitySubscription` and
  `createActivityAccess(application, isTracked)` — scoped access to the resumed Android activity,
  with no getter to leak through and a weak reference cleared by the framework's own lifecycle
  callbacks. `kmptoolkit-systembars` and `kmptoolkit-permission` each carried a private copy of this
  code; both now depend on the one artifact, which changes neither module's public API.

  It is the suite's first **Android-only** artifact. Not because iOS support is unfinished, but
  because UIKit has no counterpart to an `Activity` — an iOS `actual` here could only be an
  interface that compiles and does nothing. Depend on it from `androidMain`. The bar a second
  Android-only module would have to clear is in `docs/01-architecture.md` § "One module is
  Android-only".
- `kmptoolkit-systembars`: `createSystemBarsController(activityAccess, initialConfig)` and
  `createScreenWakeLockController(activityAccess)`, so a consumer can say *which* activities the
  controller is allowed to act on. The `Context` overloads track every activity in the process,
  which is right when the bars belong to whichever window the user is looking at — and wrong as soon
  as the process hosts a window the app does not own the appearance of. An in-process picker or
  sign-in activity would otherwise be handed the configuration the app last set, including a
  fullscreen one a screen underneath had claimed, and a keep-screen-awake flag would follow the user
  into it. `createActivityAccess(application) { it is MainActivity }` fixes both. Purely additive;
  the `Context` overloads are unchanged and still track everything.
- `kmptoolkit-language`: `AppLanguage(code)` and `isRightToLeft(code)`, deriving reading direction
  from the writing system instead of making every consumer state it. Which languages an app offers
  is a product decision this module stays out of; which direction Arabic reads in is not, and
  `isLtr = true` typed next to `"fa"` lays Persian out left-to-right with nothing failing. An
  explicit script subtag outranks the language (`az-Arab` is right-to-left, `az` is not), and an
  unrecognised tag is reported left-to-right — the safe direction to be wrong in. The two-argument
  constructor still works and still wins. Purely additive.
- `kmptoolkit-systembars`: `createHeadlessSystemBarsController()`, a controller with the full layer
  model and no window behind it, in the **main** artifact rather than the testing one. `@Preview`
  functions compile into production source, so a screen that resolves a controller — or uses
  `SystemBarsEffect` — cannot reach `RecordingSystemBarsController` from a `testImplementation`
  artifact, and every consumer with previews was left hand-rolling the same empty
  `SystemBarsController`. It is headless rather than inert: overrides stack and release exactly as
  on a device, so a preview reading `config` back sees a real answer. It is also what the `jvm`
  target's `createSystemBarsController()` returns. Purely additive.
- `kmptoolkit-systembars-testing` now also publishes a **`jvm` target**, matching
  `kmptoolkit-systembars`. The fixtures are pure Kotlin with no platform code; without this, the
  shared phone-and-desktop UI tree the desktop exception exists for could not resolve the double
  from `commonTest`. Purely additive.
- `kmptoolkit-systembars-testing`: `RecordingSystemBarsController`, a `SystemBarsController` double
  that layers overrides exactly as the real controller does and records every configuration that
  would have reached a window. `activeOverrideCount` exists so a teardown test can assert a screen
  left no layer behind. Not thread-safe, deliberately — see
  `docs/kmptoolkit-systembars/06-testing.md`.
- `kmptoolkit-systembars` now also publishes a **`jvm` (desktop) target** — the only module in the
  suite that does. The module's premise is that a screen states what it wants from the bars without
  knowing where it runs, so a Compose Multiplatform app sharing one UI tree between phone and
  desktop could not compile that tree at all while the types resolved on only two of its three
  targets. Desktop has no system bars, so every platform call in `jvmMain` is a no-op
  (`createSystemBarsController()` and `createScreenWakeLockController()` are the JVM factories);
  the layer stack — per-axis ownership, restore-by-removal, no lost update under concurrency — is
  `commonMain` and behaves identically, so `config` always holds what the shared tree asked for.
  Purely additive: no existing target, artifact or symbol changes. See
  `docs/01-architecture.md` § "One module publishes a desktop target".

- `kmptoolkit-systembars`: `ScreenWakeLockController`, a keep-screen-awake primitive
  (`Window.FLAG_KEEP_SCREEN_ON` / `UIApplication.idleTimerDisabled`) unrelated to the bars, created
  with `createScreenWakeLockController(context)` (Android) / `createScreenWakeLockController()`
  (iOS). Purely additive — `SystemBarsController` and every other existing symbol is unchanged.
- `kmptoolkit-systembars-testing`: new module holding `RecordingScreenWakeLockController`, a
  `ScreenWakeLockController` double for `testImplementation`.
- `kmptoolkit-systembars`: `AutoSystemBarsIconStyle`, an opt-in composable that samples the pixels
  drawn under both bars and derives a contrasting icon style for each, and
  `StatusBarLuminanceProbe` / `createStatusBarLuminanceProbe()` for triggering an on-demand
  re-sample. Ported from Tahfeez's `AutoSystemBarsIconStyle`, adapted to publish its derived style
  as one `SystemBarsOverride` this composable pushes and updates in place (this module's existing
  layered-override model) rather than writing a flat controller's icon-style setters directly, so a
  screen's own `SystemBarsEffect` still wins any axis it explicitly claims. Requires a new
  `androidx.lifecycle:lifecycle-runtime-compose` dependency in `commonMain`, needed to pause
  sampling while the host is backgrounded. Purely additive — every existing symbol is unchanged.
- New `kmptoolkit-language` module: `AppLanguageHolder`, `AppLanguage` (a BCP-47 code and a reading
  direction — no fixed language list, no display names), `AppLanguageCatalog` /
  `createAppLanguageCatalog`, `applyLanguageGlobally()` / `getSystemLanguageCode()`, and Android's
  `localizedContext()` and `LocalizedApplicationResources`. Ported from Tahfeez's `core/language`,
  generalized to carry no fixed language list or user-facing text and to depend on no storage or DI
  framework of its own.

  `AppLanguageCatalog` is the module's answer to the questions that need the *set* of supported
  languages to answer, without the library knowing how many an app has: which language to serve a
  device whose own language you do not offer (whole tag first, then primary subtag, so `pt-BR` finds
  your `pt`), what string to persist for a selection, and what a previously persisted string means
  today. You build one from your own list; display names and flags stay with you. `systemId` — the
  persisted identity of "follow the device" — is a defaulted parameter rather than a constant, so an
  app with existing values on disk can keep them.

  `LocalizedApplicationResources` (Android) is the piece most easily missed: Android rebuilds the
  process-global default locale from the *Application's* resources on every configuration delivery,
  so without it the app silently reverts to the device language on any change that does not recreate
  the activity — a 180° rotation, a window resize inside the same size bucket, an external display.
  Install it by overriding `Application.getResources()`; see
  `docs/kmptoolkit-language/05-platform-notes.md`.

  `AppLanguageHolder.setLanguage` applies the platform locale on **every** call, including one
  passing the language already in effect. The platform default is shared state the OS itself
  rewrites, so re-asserting it is the point; `languageFlow` and `onLanguageChanged` remain
  change-only.
- New `kmptoolkit-language-compose` module: `AppLocale` and `Modifier.mirrorOnRtl()` /
  `mirrorOnLtr()`. Split from `kmptoolkit-language` so the base module stays plain Kotlin.

  `AppLocale(language, resolvedLanguage, content)` takes the selection *and* the resolved
  language: the first is what the platform locale is pinned to — passing `AppLanguage.System` pins
  the device's language rather than whatever it is set to today — and only `isLtr` is read from the
  second. `resolvedLanguage` has no default on purpose: defaulting it to `language` would let
  `AppLocale(selected) { … }` compile and then read `AppLanguage.System.isLtr`, which is a
  placeholder, laying an Arabic device on "follow system" out left-to-right with nothing to show for
  it. Its platform halves differ, deliberately: Android re-pins the process default synchronously
  before `content` composes and provides a localized `LocalConfiguration`/`LocalContext`, so a
  language change keeps the subtree's remembered state; iOS keys the composition on the language
  code, which is the only mechanism available there and does not. See
  `docs/kmptoolkit-language-compose/05-platform-notes.md`.
- `kmptoolkit-permission`: `SpecialPermission` and `SpecialPermissionHandler`, for Android's
  "special access" grants that have no in-app request dialog — `EXACT_ALARM`, `OVERLAY`,
  `WRITE_SETTINGS`, `ALL_FILES_ACCESS`, `USAGE_STATS_ACCESS`, `IGNORE_BATTERY_OPTIMIZATIONS`,
  `NOTIFICATION_LISTENER_ACCESS`, `DO_NOT_DISTURB_ACCESS`. Created with
  `createSpecialPermissionHandler(context, logger)` (Android) / `createSpecialPermissionHandler()`
  (iOS); every entry is always granted, with nothing to open, on iOS. Ported from Tahfeez's
  `core/permission`, adapted to this module's factory-function convention (no Koin). Purely
  additive — `PermissionHandler` and every other existing symbol is unchanged.
- `kmptoolkit-permission-testing`: `RecordingSpecialPermissionHandler`, a `SpecialPermissionHandler`
  double for `testImplementation`.
- `kmptoolkit-location`: `LocationServicePrompt` and `LocationProvider.promptToEnableService()` (a
  **default interface method**, so no existing `LocationProvider` implementation — including a
  consumer's own — needs to change). Both factory-built providers only ever report `ALREADY_ON` /
  `UNSUPPORTED`: an in-place resolution dialog needs Google Play Services on Android, which this
  module deliberately does not depend on, and iOS exposes no equivalent API at all — see
  `docs/kmptoolkit-location/05-platform-notes.md`. Purely additive.

- `kmptoolkit-uploader`: `UploadTransport`, a ready-made executor for `AttemptResult.Detached`
  covering the common case — a plain multipart HTTP upload that must keep going after the process
  dies. `createWorkManagerUploadTransport` (Android) turns `execute()` into one `WorkManager` job
  per item (unique-keyed, so a re-hand joins rather than duplicates), streams the multipart body
  from disk without buffering it, and reports the outcome to whichever `UploaderEngine` is
  registered through the existing `UploaderEngineRegistry`. `UploadRequest`/`UploadField` are
  encoded into WorkManager's own primitive `Data` by hand — no new serialization dependency.
  `classify: (UploadResult) -> SettleResult` (default: `defaultUploadClassification`) lets you map
  HTTP outcomes onto the queue's vocabulary yourself. No iOS transport yet — a background
  `NSURLSession` needs your app's own `AppDelegate` to forward
  `application(_:handleEventsForBackgroundURLSession:completionHandler:)`, the same OS-mandated
  cooperation the existing iOS wake scheduler already requires for `BGTaskScheduler`; see
  `docs/kmptoolkit-uploader/08-upload-transport.md`. Purely additive — every existing symbol,
  including `AttemptResult.Detached` itself, is unchanged.

### Changed

- `kmptoolkit-systembars`: `DialogWindowSystemBarsEffect` now collects the controller's configuration
  lifecycle-aware (`collectAsStateWithLifecycle`) instead of unconditionally, matching
  `AutoSystemBarsIconStyle` — a backgrounded dialog window is neither read from nor written to, and
  the configuration is re-read on the way back to `STARTED`. No API change.

### Fixed

- `kmptoolkit-systembars`: a released `SystemBarsOverrideHandle` could release or overwrite a layer
  belonging to a *later* override. Override ids were derived from the layers currently on the stack,
  so an empty stack restarted the sequence and a dead handle came back to life aliasing whatever was
  pushed next. Reaching it needed the stack to drain in between — one screen leaving, another
  arriving, and the first then releasing or updating its handle a second time — at which point the
  arriving screen's bars were silently cleared or rewritten. Ids are now part of the same atomic
  state as the stack itself and are never reused, including across `SystemBarsController.release()`.
  `RecordingSystemBarsController` was already correct here and now has the matching parity cases.
- `kmptoolkit-location`: the iOS `LocationProvider` now creates and starts every `CLLocationManager`
  on the main queue. Previously a caller reaching `getCurrentLocation()` / `observeLocation()` from
  `Dispatchers.Default` (a Kotlin/Native worker thread, which has no run loop) could see the manager
  created there too — CoreLocation delivers delegate callbacks on the run loop of the thread that
  created the manager, so on such a thread neither a fix nor a failure would ever arrive, and the
  call would suspend forever rather than time out or fail.

## [1.0.2] - 2026-09-04

No public API change. Re-release of `1.0.1`, whose Maven Central deployment never completed
because the CI signing step failed on a misconfigured repository secret (see `RELEASING.md`
§ Troubleshooting). Every `kmptoolkit-*` artifact and the BOM are published at this version.

## [1.0.1] - 2026-08-29

### Removed

- **Breaking:** `kmptoolkit-coroutines` and `kmptoolkit-coroutines-testing` are no longer part of
  the suite. `AppDispatchers` was a three-field dispatcher-seam interface whose only internal
  consumer, `kmptoolkit-session`, used exactly one of those fields — and `kotlinx-coroutines-test`'s
  own `TestDispatcher` already covers what `TestAppDispatchers` existed to provide, with no custom
  type needed.
- **Breaking:** `kmptoolkit-session` and `kmptoolkit-session-testing` are no longer part of the
  suite. `SessionManager`, `SessionCleaner`, `SessionRevoker`, and the rest of the module's public
  API are gone, along with the `RecordingSessionCleaner`/`RecordingSessionRevoker` test doubles;
  no other module in the suite depended on either artifact.
- **Breaking:** `kmptoolkit-settings` is no longer part of the suite. `AppSettings`, `FontScale`,
  `ThemeMode`, `LanguageTag`, `LanguageApplier`, and the rest of the module's public API are gone.
  It was the one module that prescribed *what settings an app should have* instead of exposing a
  platform capability, and it had no dependents inside the suite.
- **Breaking:** `kmptoolkit-platform` and `kmptoolkit-platform-testing` are no longer part of the
  suite: `ConnectivityObserver`, `DeviceInfo`, `FilePicker`, `ScreenWakeLock`, `CrashLogStore`,
  `UrlOpener`, `ReducedMotionProbe`, and the public `ActivityAccess`/`ActivitySubscription`/
  `createActivityTracker` API are all gone, along with every fake in `kmptoolkit-platform-testing`.
  Nothing in the suite used the capabilities beyond `ActivityAccess`; that one is now a private
  implementation detail duplicated inside each of its three consumers instead — see the `Changed`
  entry below.

### Changed

- **Breaking:** `kmptoolkit-outbox`, `kmptoolkit-outbox-testing`, and `kmptoolkit-outbox-sqldelight`
  are renamed to `kmptoolkit-uploader`, `kmptoolkit-uploader-testing`, and
  `kmptoolkit-uploader-sqldelight`. The package moves from
  `io.github.jamal_wia.kmptoolkit.outbox` to `io.github.jamal_wia.kmptoolkit.uploader`, and every
  public type is renamed to match — `Outbox` to `Uploader`, `OutboxItem` to `UploaderItem`,
  `OutboxEngine` to `UploaderEngine`, `OutboxConfig` to `UploaderConfig`, `OutboxStore` to
  `UploaderStore`, `OutboxHandler` to `UploaderHandler`, `OutboxClock` to `UploaderClock`,
  `OutboxEngineRegistry` to `UploaderEngineRegistry`, `FakeOutbox` to `FakeUploader`,
  `InMemoryOutboxStore` to `InMemoryUploaderStore`, `OutboxStoreContract` to
  `UploaderStoreContract`, `SqlDelightOutboxStore` to `SqlDelightUploaderStore`, and the rest of
  each module's API accordingly. `kmptoolkit-outbox-sqldelight`'s table and index names change from
  `kmptoolkit_outbox_item*` to `kmptoolkit_uploader_item*` — an existing on-disk database's queue
  table is not carried forward automatically; a consumer with data already committed under the old
  name is responsible for migrating it. There is no compatibility alias for the old coordinates,
  package, or type names.
- **Breaking:** `createBiometricGate` and `createPermissionHandler` (Android) no longer take an
  `activityAccess: ActivityAccess` parameter, and `createSystemBarsController` (Android) takes a
  `context: Context` in its place. This follows from removing `kmptoolkit-platform`, above:
  `ActivityAccess` could no longer be a type the three modules share, so each now tracks the
  currently resumed activity with its own private, weakly-held tracker instead of the caller
  building one with `createActivityTracker(application)` and passing it to all three. A consumer
  using all three modules now gets three `Application.ActivityLifecycleCallbacks` registrations
  instead of one, which is harmless but was worth naming.
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
