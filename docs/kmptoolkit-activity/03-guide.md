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
Each of their Android factories has an overload taking a `SystemScreenLauncher`, which decides how.

### Which preset

| | `SeparateTask` | `callerTask(activityAccess)` |
|---|---|---|
| Where the screen goes | a task of its own | on top of your resumed activity, in your task |
| System Back | leaves Settings; Android shows whatever was behind it, normally your app | returns to your activity |
| Recents | a separate Settings card | one card, your app |
| Two-pane Settings (tablets, foldables) | hands itself to the Settings homepage, whose task may be a stale one | single pane, no hand-off |
| Lock-task (kiosk) app | stays out of the locked task | ends up inside the locked task |
| No activity resumed | — | falls back to `SeparateTask` |

For an ordinary app, `callerTask` is usually what the user expects, and it is the only way to avoid
the two-pane hand-off. For a lock-task app, keep `SeparateTask`: a screen in your locked task is no
longer confined by the lock-task allowlist.

Which one a module uses by default is decided per factory and documented there: a factory that only
has a `Context` uses `SeparateTask`, because it has no activity to launch from; a factory that takes
your `ActivityAccess` uses `callerTask` on it, except biometric enrolment, which always defaults to a
separate task.

### Your own launcher

A launcher gets one `SystemScreenRequest` per logical request — every candidate intent at once — and
answers whether a screen was opened:

```kotlin
// Log, then delegate to a preset.
val logged = SystemScreenLauncher { request ->
    Log.i("Screens", "opening ${request.kind}")
    SystemScreenLauncher.SeparateTask.launch(request)
}

// Launch for a result through an ActivityResultLauncher<Intent> your activity registered.
val forResult = SystemScreenLauncher { request ->
    request.startFirstResolvable { intent -> settingsResults.launch(intent) }
}

// A kiosk: allow Settings in lock-task for the duration of this screen only.
val kiosk = SystemScreenLauncher { request ->
    when (request.kind) {
        BiometricEnrollmentScreen -> {
            allowSettingsWindow.open()
            SystemScreenLauncher.SeparateTask.launch(request).also { launched ->
                if (!launched) allowSettingsWindow.close()
            }
        }
        else -> SystemScreenLauncher.SeparateTask.launch(request)
    }
}
```

- **One call per request.** A dialog your launcher shows appears once, however many candidates the
  request has.
- **`startFirstResolvable`** tries the candidates in order and moves on when one throws
  `ActivityNotFoundException` or `SecurityException`; it hands your block a copy, so adding flags is
  safe. Use it rather than looping yourself.
- **`false` means nothing was opened.** The calling module turns it into its own "could not open"
  answer. So does a launcher that throws.
- **`kind` is open.** Match the kinds you care about and keep an `else` branch: later releases add
  kinds without breaking you.
- **Threading.** Modules call the launcher on the thread their caller used. Both presets work from any
  thread; a launcher of yours that touches UI switches to the main thread itself.
