# kmptoolkit-platform — Overview

Eight small platform seams that shared Kotlin code keeps needing and cannot express by itself.
Each one is an interface in `commonMain` with a factory per platform, and each one reports failure
as a typed value instead of an exception.

| Seam | Interface | What it answers or does |
| --- | --- | --- |
| Connectivity | `ConnectivityObserver` | is the device on a usable network, live |
| Device facts | `DeviceInfo`, `FormFactor` | OS name/version, model, phone or tablet, device region |
| Reduced motion | `ReducedMotionProbe` | did the user ask for less animation |
| Links | `UrlOpener` | hand a URL to whatever app claims it |
| Files | `FilePicker`, `PickedFile` | let the user choose a file, get bytes back |
| Screen | `ScreenWakeLock` | keep the display awake while a feature owns it |
| Crashes | `CrashLogStore`, `installCrashHandler` | record an uncaught exception, read it next launch |
| Build | `isPlatformDebugBuild`, `platformBuildVariant` | which build of *this library* is running |
| Android activities | `ActivityAccess` | reach the resumed `Activity` without leaking it |

```kotlin
class SyncEngine(
    private val connectivity: ConnectivityObserver,
    private val wakeLock: ScreenWakeLock,
) {
    suspend fun run() {
        if (connectivity.status.value == ConnectivityStatus.OFFLINE) return
        wakeLock.setKeepScreenOn(true)
        try {
            transfer()
        } finally {
            wakeLock.setKeepScreenOn(false)
        }
    }
}
```

Nothing at that call site knows about `ConnectivityManager.NetworkCallback`, `nw_path_monitor`,
`FLAG_KEEP_SCREEN_ON`, or `UIApplication.isIdleTimerDisabled`.

## The problem it solves

Every one of these is individually too small to be a library and collectively too annoying to keep
rewriting. They also share one failure mode: the platform API throws, or silently does nothing,
under conditions the shared caller cannot see.

- **Connectivity** is a callback API on Android and a path monitor on iOS, and on Android it throws
  `SecurityException` from a constructor when the app forgot `ACCESS_NETWORK_STATE`.
- **Device model** is three `Build` fields that need composing on Android and a `uname` call on
  iOS, where the obvious property (`UIDevice.name`) is personal data the OS now redacts.
- **Reduced motion** is a first-class accessibility API on iOS and an inference from an animation
  scale on Android.
- **Opening a URL** is an `Intent` that throws `ActivityNotFoundException` on Android and a
  `canOpenURL` dance on iOS that quietly fails for undeclared custom schemes.
- **Picking a file** is an activity-result contract that must be registered before the activity
  resumes on Android, and a view controller with an ARC-fragile delegate on iOS.
- **Keeping the screen on** is a window flag that a rotation silently drops on Android, and an
  application property that must be written on the main thread on iOS.
- **Recording a crash** has to happen synchronously inside a dying process, where coroutines, DI
  containers and most allocation are no longer things you can rely on.

## What this is **not**

This module is a grab-bag by nature, so its boundary needs stating precisely.

- **Not a permission library.** It declares no Android permission ([a repository-wide
  rule](../01-architecture.md#android-manifests)), never prompts for one, and has no permission
  API. Where a seam needs one it degrades to a typed value — see
  [`05-platform-notes.md`](05-platform-notes.md).
- **Not a network layer, and not a reachability oracle.** `ConnectivityObserver` reports what the
  OS believes about interfaces. It does not ping your server, does not know about metered,
  roaming, VPN or "expensive" links, and `ONLINE` does not mean your next request will succeed.
- **Not device fingerprinting.** `DeviceInfo` exposes no advertising id, no vendor id, no generated
  install id, no serial. Those carry privacy and store-policy obligations that belong to your app.
- **Not a layout system.** `FormFactor` is a hardware fact for analytics and diagnostics. Branch
  your UI on window size classes, not on this.
- **Not a crash reporter.** `installCrashHandler` writes a local file. It uploads nothing, has no
  dashboard, does not catch native (NDK) crashes, ANRs, low-memory kills, or — on iOS —
  Objective-C exceptions, Swift traps or signals. Use it alongside a real reporter, not instead of
  one.
- **Not a downloader or an uploader.** `FilePicker` returns bytes in memory, capped. There is no
  streaming variant and no background transfer.
- **Not an in-app browser.** `UrlOpener` is a one-way handoff; control leaves your app and nothing
  reports what the user did next.
- **Not a CPU wake lock.** `ScreenWakeLock` suppresses the display idle timer in the foreground.
  It does nothing for background work, which is exactly why it needs no `WAKE_LOCK` permission.
- **Not a reliable read of *your app's* build type.** `isPlatformDebugBuild` describes the library
  binary. Consumed as a published artifact, it is `false` in your debug build. See
  [`05-platform-notes.md`](05-platform-notes.md#build-variant-reporting).
- **Not Compose, and not tied to any UI framework.** Plain Kotlin, no Compose dependency.
- **Not a DI module.** Interfaces plus factory functions; wire them however you like
  ([`../01-architecture.md`](../01-architecture.md#no-dependency-injection-framework)).
- **Not JVM or desktop.** Android and iOS targets only.

### Deliberately left in the donor

This module was ported from a larger `core/platform` module in a private app. These were left
behind on purpose:

- **A workout video player** built on Media3 ExoPlayer. It is product code, and it was the only
  reason that module depended on Media3. No Media3 appears here.
- **System bars control** — status/navigation bar colour, icon style, luminance probing. It is
  Compose-shaped and ships separately as `kmptoolkit-systembars`.
- **A logger and a debug log overlay.** Logging is `kmptoolkit-logging`, which this module depends
  on; the overlay is a separate Compose artifact.
- **The JVM/desktop source set** — a Swing file chooser, an AWT URL opener, a polling connectivity
  probe. This repository targets Android and iOS only.
- **`isDesktopFormFactor`**, whose only meaning was "am I the desktop admin app". Replaced by
  `FormFactor`, which says something on the platforms that remain.
- **Two process-wide mutable singletons** — a `CrashFileWriter.instance` and a file-picker bridge
  holding an activity-result launcher. Both are now explicit parameters; see
  [`03-guide.md`](03-guide.md).

## Where to go next

- [`02-getting-started.md`](02-getting-started.md) — dependency, and constructing each seam.
- [`03-guide.md`](03-guide.md) — the seams in practice, including the Android file-picker host and
  the crash-on-previous-launch flow.
- [`04-api-reference.md`](04-api-reference.md) — every public declaration.
- [`05-platform-notes.md`](05-platform-notes.md) — **required reading**: permissions your app must
  declare, and how each seam behaves without them.
- [`06-testing.md`](06-testing.md) — the fixtures in `kmptoolkit-platform-testing`.
