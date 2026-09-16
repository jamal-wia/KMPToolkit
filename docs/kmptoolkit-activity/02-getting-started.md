# kmptoolkit-activity — getting started

## Dependency

```kotlin
androidMain.dependencies {
    implementation("io.github.jamal-wia:kmptoolkit-activity:<version>")
}
```

Android only — it has no `commonMain` counterpart. If you already depend on
`kmptoolkit-systembars`, it arrives transitively.

## Create one, once

It belongs to your application, not to a screen:

```kotlin
class MyApplication : Application() {

    lateinit var activityAccess: ActivityAccess
        private set

    override fun onCreate() {
        super.onCreate()
        activityAccess = createActivityAccess(this)
    }
}
```

`createActivityAccess` registers with the `Application` immediately and starts tracking. Nothing
else is needed — no per-activity call, no base class, no manifest entry.

**It has to be `Application.onCreate`, not later.** The tracker learns which activity is current
only from the activity-resumed callback, and Android has no public way to ask which activity
resumed before that callback was registered. An instance created afterwards — a lazy DI singleton
first resolved while your first screen composes, which on Android happens after `onResume` — answers
`null` until the user leaves and comes back. If you use a DI container, resolve it right after the
container starts.

## Use it

```kotlin
// Do something with the current activity, or nothing if there is none.
val handled: Boolean? = activityAccess.withActivity { activity ->
    activity.startActivityForResult(intent, REQUEST_CODE)
    true
}

// React to the activity being replaced — a rotation, for instance.
val subscription = activityAccess.addOnActivityResumedListener { activity ->
    applyWindowFlags(activity.window)
}
```

`withActivity` returns `null` when there is no valid activity, which is a normal answer and not an
error: the app may be backgrounded, or between two activities during a configuration change.
`addOnActivityResumedListener` fires immediately if one is already resumed, so a listener that
records state and replays it does not need a separate "catch up" path.

## Say which activities count

The default tracks every activity in the process. If your process hosts activities whose appearance
or behaviour you do not own, name the ones you do:

```kotlin
activityAccess = createActivityAccess(this) { it is MainActivity || it is SettingsActivity }
```

Everything else the process resumes is then ignored outright: it does not become "the" activity, and
yours underneath is still what `withActivity` answers with when it comes back.

## Tearing down

A process-lifetime instance never needs `release()`. Call it if you build one for a narrower scope,
or in a test, to unregister from the `Application` and drop every listener. It is idempotent.
