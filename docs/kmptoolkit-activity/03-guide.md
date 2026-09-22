# kmptoolkit-activity — guide

## Choosing the tracking predicate

`createActivityAccess(application)` tracks every activity in the process. That is the right default
for anything that belongs to *the window the user is looking at* — and the wrong one as soon as your
process hosts a window you do not own the behaviour of.

You probably host more of those than you think. Some arrive without you writing an activity at all:
a `ComponentActivity` declared in a dependency's manifest, a billing or sign-in flow that runs
in-process, a trampoline activity behind a notification. When one of those resumes, an unfiltered
tracker reports it as the current activity, and whatever is driven off that answer follows it there.

Two symptoms worth recognising, because neither looks like a tracking problem from the outside:

- a screen that had claimed fullscreen leaves a *picker* with no system bars, because the bar
  configuration was applied to the picker's window on resume;
- a keep-screen-awake flag set for a playback session follows the user into a file picker and keeps
  the screen on there.

If either could apply, name the activities you mean:

```kotlin
createActivityAccess(application) { it is MainActivity }
```

The predicate runs on the main thread inside `onActivityResumed`, so keep it to a type check. Prefer
listing your own activity classes over excluding the ones you have noticed — an exclusion list is
one dependency upgrade away from being incomplete, and the failure is silent.

## Sharing one instance

Every consumer of an `ActivityAccess` should usually get the same one. Two instances with different
predicates will disagree about which window they mean, which is exactly the confusion this module
exists to remove:

```kotlin
val activityAccess = createActivityAccess(application) { it is MainActivity }

val systemBars = createSystemBarsController(activityAccess)
val wakeLock = createScreenWakeLockController(activityAccess)
```

Each of those keeps a reference to it and neither releases it — the lifetime is yours.

## `withActivity` returns null, and that is normal

```kotlin
val shown: Boolean = activityAccess.withActivity { activity ->
    dialog.show(activity)
    true
} ?: false
```

`null` means "there is no activity to run this against right now": the app is backgrounded, or it is
between two activities during a configuration change. It is not an error and it is not rare. Decide
per call site whether to skip the work or to record it and replay it from
`addOnActivityResumedListener`, which fires immediately when an activity is already resumed and
again on the next one.

## Do not capture the activity

The block is where the safety lives. This is fine:

```kotlin
activityAccess.withActivity { activity -> activity.window.addFlags(FLAG_KEEP_SCREEN_ON) }
```

and this defeats the entire module:

```kotlin
// Don't. This is the static field the module replaced, with extra steps.
private var cached: Activity? = activityAccess.withActivity { it }
```

The same goes for a listener: it is held strongly until you cancel the subscription, so anything it
captures lives as long as the tracker does. Capture the `ActivityAccess`, not an `Activity`.

## Threading

`withActivity` runs the block on the calling thread and does not move you to the main thread —
most `Activity` APIs require it, so hop yourself if you are not already there. Listeners are invoked
synchronously on whatever thread the framework delivers `onActivityResumed` on, which is the main
thread.

## Opening system screens

`kmptoolkit-biometric` (enrolment), `kmptoolkit-location` (location settings) and
`kmptoolkit-permission` (app details, the special-permission screens) open Settings screens for you.
Each has a `…WithLauncher` factory that takes a `SystemScreenLauncher`, which decides how.

### Which preset

| | `SeparateTask` | `callerTask(activityAccess)` |
|---|---|---|
| Where the screen goes | a task of its own | on top of your resumed activity, in your task |
| System Back | leaves Settings; Android shows whatever was behind it, normally your app | returns to your activity |
| Recents | a card per screen | one card, your app |
| Two-pane Settings (tablets, foldables) | a Settings page hands itself to the Settings homepage, whose task may be a stale one | single pane, no hand-off |
| Lock-task (kiosk) app | stays out of the locked task | ends up inside the locked task |
| No activity resumed, or not on the main thread | — | falls back to `SeparateTask` |

For an ordinary app, `callerTask` is usually what the user expects, and it is the only way to avoid
the two-pane hand-off. For a lock-task app, keep `SeparateTask`: a screen in your locked task is no
longer confined by the lock-task allowlist.

### What each factory does by default

| Factory | Screens | Default |
|---|---|---|
| `createBiometricGate(…)` (every overload) | enrolment | `SeparateTask` |
| `createLocationProvider(context, config, logger)` | location settings | `SeparateTask` |
| `createSpecialPermissionHandler(context, logger)` | special-permission screens | `SeparateTask` |
| `createPermissionHandler(…)` (both overloads) | app details (`openAppSettings`) | `callerTask` on its tracker — the one you pass, or the one it creates |
| `create…WithLauncher(…, systemScreenLauncher, …)` | the module's screens | the launcher you pass |

So in an ordinary app, opting every screen into your own task looks like this:

```kotlin
val launcher = SystemScreenLauncher.callerTask(activityAccess)

val location = createLocationProviderWithLauncher(context, launcher)
val special = createSpecialPermissionHandlerWithLauncher(context, launcher)
val gate = createBiometricGateWithLauncher(context, launcher, activityAccess = activityAccess)
```

Biometric enrolment stays in a separate task by default because it is the screen lock-task apps are
known to open; pass `callerTask` yourself, as above, if yours is not one.

### Your own launcher

A launcher gets one `SystemScreenRequest` per logical request — every candidate intent at once — and
answers whether a screen was opened.

**Logging.** Wrap the preset the factory would have used anyway, so adding a log line does not also
move the screen to another task:

```kotlin
fun logged(delegate: SystemScreenLauncher) = SystemScreenLauncher { request ->
    Log.i("Screens", "opening ${request.kind}")
    delegate.launch(request)
}

val location = createLocationProviderWithLauncher(context, logged(SystemScreenLauncher.SeparateTask))
val permissions = createPermissionHandlerWithLauncher(
    context, host, storage, activityAccess, logged(SystemScreenLauncher.callerTask(activityAccess)),
)
```

**A kiosk.** Lock-task mode allows a screen only if its package is on the allowlist, so a kiosk opens
an allowlist window around the screens it means to allow — here, biometric enrolment only:

```kotlin
val kiosk = SystemScreenLauncher { request ->
    if (request.kind == BiometricEnrollmentScreen) {
        launchInsideAllowlistWindow(request)
    } else {
        SystemScreenLauncher.SeparateTask.launch(request)
    }
}

fun launchInsideAllowlistWindow(request: SystemScreenRequest): Boolean {
    allowSettingsWindow.open()
    val launched: Boolean = try {
        SystemScreenLauncher.SeparateTask.launch(request)
    } catch (e: Exception) {
        allowSettingsWindow.close()
        throw e
    }
    if (!launched) allowSettingsWindow.close()
    return launched
}
```

- Close the window when the launch returned `false` **or threw** — otherwise it stays open.
- `startActivity` returns before the screen appears, so do not close the window when `launch`
  returns. Close it when your app resumes, which is also when you re-check the outcome.
- While the window is open, the whole Settings package is allowed, not just this one screen: the
  user can navigate elsewhere inside Settings.
- `SeparateTask`, never `callerTask`: a screen in your locked task is not confined by the allowlist.

**A result.** Starting with `FLAG_ACTIVITY_NEW_TASK` for a result gets `RESULT_CANCELED` at once, so
a launcher that wants a result starts without task flags from your activity, through an
`ActivityResultLauncher` that activity registered. Look it up when the launcher runs rather than
capturing it: the launcher outlives every activity instance, and a captured `ActivityResultLauncher`
stops working after a rotation. Like `callerTask`, it only starts from the activity on the main
thread.

```kotlin
interface HasSettingsResults { val settingsResults: ActivityResultLauncher<Intent> }

val forResult = SystemScreenLauncher { request ->
    val onMainThread: Boolean = Looper.myLooper() == Looper.getMainLooper()
    if (!onMainThread) return@SystemScreenLauncher SystemScreenLauncher.SeparateTask.launch(request)
    activityAccess.withActivity { activity ->
        val results = (activity as? HasSettingsResults)?.settingsResults ?: return@withActivity null
        request.startFirstResolvable { intent -> results.launch(intent) }
    } ?: SystemScreenLauncher.SeparateTask.launch(request)
}
```

Most Settings pages always report `RESULT_CANCELED`; the result that matters is what you re-check
afterwards (`isGranted`, `availability()`, …). The battery-optimisation dialog is the exception: it
reports `RESULT_OK` when the user allowed it.

**Rules for any launcher.**

- **One call per request.** A dialog your launcher shows appears once, however many candidates the
  request has.
- **`startFirstResolvable`** tries the candidates in order and moves on when one throws
  `ActivityNotFoundException` or `SecurityException`; it hands your block a copy, so adding flags is
  safe. Use it rather than looping yourself — modules may add candidates in later releases.
- **`false` means nothing was opened.** The calling module turns it into its own "could not open"
  answer. So does a launcher that throws.
- **`kind` is open.** Match the kinds you care about and keep an `else` branch: later releases add
  kinds without breaking you.
- **Threading.** Modules call the launcher on the thread their caller used. `SeparateTask` works from
  any thread; `callerTask` opens the screen on your task only when called on the main thread, and in
  a separate task otherwise. A launcher of yours that touches UI switches to the main thread itself.

Testing a launcher of your own: see [`06-testing.md`](06-testing.md).
