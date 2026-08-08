# kmptoolkit-platform — Platform notes

What differs behind each seam, which permissions your app must declare, and exactly how each seam
behaves when one is missing.

## Permissions

**This library declares no Android permission at all**, and there is an
[`androidUnitTest` that asserts it](../../kmptoolkit-platform/src/androidUnitTest/kotlin/io/github/jamal_wia/kmptoolkit/platform/LibraryManifestTest.kt)
against a real package manager. That is a repository-wide rule
([`../01-architecture.md`](../01-architecture.md#android-manifests)): a permission in a library
manifest merges into every consuming app silently, showing up in a store listing the library author
never sees and cannot justify.

| Seam | Android permission | Who declares it | Without it |
| --- | --- | --- | --- |
| `ConnectivityObserver` | `ACCESS_NETWORK_STATE` | **you** | `status` stays `UNKNOWN` forever; no throw |
| `ScreenWakeLock` | none — by design | — | n/a |
| `FilePicker` | none | — | n/a |
| `UrlOpener` | none | — | n/a |
| `DeviceInfo`, `ReducedMotionProbe` | none | — | n/a |
| `CrashLogStore` | none | — | n/a |

iOS requires no permission, entitlement or `Info.plist` usage-description string for anything in
this module.

### Android — you must declare `android.permission.ACCESS_NETWORK_STATE`

```xml
<manifest>
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
</manifest>
```

Facts about this one:

- It is a **normal** permission, granted at install time. No runtime prompt, no
  `requestPermissions` call, no dialog. One manifest line is the whole task.
- The user cannot revoke it, so it cannot disappear mid-session. Missing means missing for the
  whole install.

**Behavior when it is missing:** `ConnectivityManager.registerNetworkCallback` throws
`SecurityException`. The observer catches it in its constructor, logs a warning to the `Logger` you
passed (nothing, by default), and leaves `status` at `ConnectivityStatus.UNKNOWN` permanently.
Nothing throws, and nothing is reported as `OFFLINE` — reporting offline for a device that is
online would be worse than admitting ignorance, which is the entire reason `UNKNOWN` exists as a
value rather than being collapsed into a `Boolean`.

### Why the screen wake lock needs no permission

`android.permission.WAKE_LOCK` is what a `PowerManager.WakeLock` requires. This module uses the
window flag `FLAG_KEEP_SCREEN_ON` instead, which needs none, is scoped to a foreground window, and
is released by the framework when that window goes away. The trade-off is deliberate: the window
flag cannot keep the CPU awake in the background, which is a capability a library has no business
adding to a consumer's manifest.

If you genuinely need a background wake lock, that is `PowerManager` in your own Android code, with
your own manifest entry and your own justification.

### Why the file picker needs no storage permission

`ActivityResultContracts.OpenDocument` (Android) and `UIDocumentPickerViewController` (iOS) both
run out of process. The user's selection *is* the grant, scoped to that one file. Neither
`READ_EXTERNAL_STORAGE` nor any `READ_MEDIA_*` permission is involved, on any API level this module
supports.

## Connectivity

| | Android | iOS |
| --- | --- | --- |
| Backed by | `ConnectivityManager.NetworkCallback` | `nw_path_monitor` (Network framework) |
| Granularity | per network; tracks a set | one aggregate path |
| `ONLINE` means | a network with `NET_CAPABILITY_INTERNET` **and** `NET_CAPABILITY_VALIDATED` | path status is `satisfied` |
| Callback thread | a framework handler thread | a background-QoS global dispatch queue |

`NET_CAPABILITY_VALIDATED` is why an unvalidated network — one with an interface that failed the
captive-portal probe — is not reported as online. Without it, an app retries requests against a
hotel Wi-Fi login page.

During a Wi-Fi to cellular handover both networks are briefly up, and Android reports the new one
before losing the old. The observer stays `ONLINE` throughout rather than emitting a spurious
`OFFLINE`, so you do not have to debounce it yourself.

## Device info

| | Android | iOS |
| --- | --- | --- |
| `osVersion` | `Build.VERSION.RELEASE`, falling back to the API level | `UIDevice.systemVersion` |
| `model` | `Build.MANUFACTURER` + `Build.MODEL`, de-duplicated | `uname().machine`, e.g. `iPhone15,2` |
| `formFactor` | `smallestScreenWidthDp >= 600` is a tablet | `userInterfaceIdiom` |
| `currentCountry()` | `Locale.getDefault().country` | `NSLocale.currentLocale.countryCode` |

Two things worth knowing:

- **`osVersion` is not always numeric.** Android preview releases report a codename
  (`"VanillaIceCream"`). Compare it as an opaque token; never parse it.
- **iOS reports the machine identifier, not the device name.** `UIDevice.name` is user-editable
  ("Anna's iPhone"), which makes it personal data, and since iOS 16 the OS redacts it without an
  entitlement. The machine id identifies hardware rather than a person.
- On iOS the simulator reports its **host** architecture (`arm64`, `x86_64`) as the model.

## Reduced motion

| | Android | iOS |
| --- | --- | --- |
| Source | `Settings.Global.ANIMATOR_DURATION_SCALE == 0` | `UIAccessibilityIsReduceMotionEnabled()` |
| Directness | inferred | the actual setting |

Android has no dedicated "reduce motion" flag. The animation-duration scale is what Accessibility →
"Remove animations" writes, and it is also what the developer-options sliders write — so a
developer who set the scale to `0` to speed up testing will be reported as wanting reduced motion.
That is the correct reading of the only signal the platform offers.

A scale of `0.5` or `10` is **not** reduced motion; only exactly zero is. An unwritten setting
reads as `1` (normal), so the probe fails open.

**There is no observe API**, because a common one would be dishonest: Android would need a
`ContentObserver` on a `Settings.Global` URI and iOS a notification-centre observer, with different
delivery timing and different guarantees. Read it when you are about to animate — that is when the
answer matters, and it costs a settings lookup.

## Opening URLs

| | Android | iOS |
| --- | --- | --- |
| Backed by | `Intent.ACTION_VIEW` + `FLAG_ACTIVITY_NEW_TASK` | `canOpenURL` then `openURL` on the main queue |
| `NO_HANDLER` from | `ActivityNotFoundException` | `canOpenURL` returning false |

Both first reject anything that is not an absolute URL, in shared code, so `""`, `"/help"` and
`"example.com"` produce `INVALID_URL` identically on both platforms instead of Android quietly
building a scheme-less `Uri` and iOS returning a non-null `NSURL` for a relative reference.

**iOS: custom schemes need declaring.** `canOpenURL` returns false for a scheme that is not in your
`Info.plist` under `LSApplicationQueriesSchemes`, so `myapp://…` comes back as `NO_HANDLER` until
you add it:

```xml
<key>LSApplicationQueriesSchemes</key>
<array><string>myapp</string></array>
```

`http`, `https`, `mailto` and `tel` need no declaration. On Android the equivalent restriction is
package visibility (API 30+), which likewise does not apply to web schemes.

**Android: the opened app starts in its own task**, because launching from an application context
requires `FLAG_ACTIVITY_NEW_TASK`. That is the right behavior for a handoff and the only one
available without holding an activity.

## File picking

| | Android | iOS |
| --- | --- | --- |
| Chooser | `ActivityResultContracts.OpenDocument`, via your `FilePickerHost` | `UIDocumentPickerViewController` |
| Filter | MIME types, straight through | MIME types mapped to `UTType`; unmappable ones dropped |
| Metadata | `OpenableColumns` on a `ContentResolver` | file attributes + the UTI database |
| `Unavailable` when | your host returns `false` or throws | no key window with a root view controller |

The MIME filter is best-effort on iOS: it matches by uniform type identifier, so a MIME type with
no UTI (or a wildcard subtype) is dropped from the filter and the picker shows more than you asked
for. Always check `mimeTypeHint`, and sniff the bytes when it matters.

The size cap is checked twice — once against the size the platform declares, before opening a
stream, and once against what was actually read, because a content provider may report no size or
report a wrong one.

Android's asymmetry (a `FilePickerHost` you implement; iOS needs nothing) is deliberate: an
`ActivityResultLauncher` must be registered before the activity resumes and dies with it, so a
library that hid one would be holding an `Activity` on a schedule it does not control. See
[`03-guide.md`](03-guide.md#the-android-file-picker-host).

## Screen wake lock

| | Android | iOS |
| --- | --- | --- |
| Backed by | window flag `FLAG_KEEP_SCREEN_ON` | `UIApplication.isIdleTimerDisabled` |
| Needs a window | yes | no |
| Survives recreation | yes — reapplied on every resume | n/a — one process-stable application object |
| Threading | flag written on the UI thread | write dispatched to the main queue |

The Android reapply-on-resume behavior is not a nicety. A configuration change — rotation, theme,
font size, density, locale — destroys the window holding the flag and creates a new one at platform
defaults, so without reapplying, rotating mid-session would silently drop the guarantee. The
desired state lives in the wake lock, not in the window.

Neither platform releases the request for you when a screen goes away. That is the caller's job.

## Crash handling

| | Android | iOS |
| --- | --- | --- |
| Hook | `Thread.setDefaultUncaughtExceptionHandler` | Kotlin/Native `setUnhandledExceptionHook` |
| Catches | Kotlin/Java exceptions reaching the top of any thread | Kotlin exceptions reaching the top of a Kotlin frame |
| Does **not** catch | NDK/native crashes, ANRs, low-memory kills, `Runtime.halt` | Objective-C exceptions, Swift traps, signals (`SIGSEGV`, `SIGABRT`) — including Kotlin/Native's own abort after the hook |
| Default file | `filesDir/kmptoolkit_crash_log.txt` | `Documents/kmptoolkit_crash_log.txt` |
| Previous handler | always invoked afterwards | always invoked afterwards |
| `uninstall()` | restores only if ours is still active | restores unconditionally — the platform offers no way to read the current hook |

Records are one escaped, tab-separated line each. A process killed mid-write leaves a truncated
final line, which is skipped on read; every complete line before it survives.

**iOS Documents is not always the right directory.** It is what Finder file sharing exposes when an
app opts in, and it is included in iCloud backups. Point `CrashLogConfig.directoryPath` at a Caches
or Application Support path if either matters for your app.

**Both platforms: this is not a crash reporter.** No upload, no dashboard, no symbolication — an
iOS release build's stack trace is unsymbolicated unless you keep the `.dSYM`. Run it alongside a
real reporter to catch the crashes that die before a reporter can flush.

## Build variant reporting

`isPlatformDebugBuild` and `platformBuildVariant` read this **library module's** own build
configuration: `BuildConfig.DEBUG` / `BuildConfig.BUILD_TYPE` on Android (which is why the module
keeps `buildFeatures { buildConfig = true }`), and `Platform.isDebugBinary` on Kotlin/Native.

That means:

| How you consume KMPToolkit | What these report |
| --- | --- |
| Built from source alongside your app (composite/included build) | your build type — what you want |
| The published Maven Central artifact | the configuration the artifact was **published** with, so `false` / `"release"` even in your debug build |

If your app's behavior depends on your app's build type, read your own `BuildConfig.DEBUG` (or
`#if DEBUG` in Swift) at the entry point and pass the value into shared code. These properties are
for library-internal decisions and for source-built setups.

## Thread safety

- `ConnectivityObserver.status` is a `StateFlow` — readable and collectable from any thread. Its
  internal bookkeeping is driven only from the single serial callback source each platform uses.
- `ScreenWakeLock.setKeepScreenOn` may be called from any thread; the platform write is moved to
  the UI thread or the main queue for you.
- `ActivityAccess.withActivity` may be called from any thread, but it does **not** move you to the
  main thread — most `Activity` APIs still require it.
- `CrashLogStore.write` is called from whichever thread crashed, synchronously.
