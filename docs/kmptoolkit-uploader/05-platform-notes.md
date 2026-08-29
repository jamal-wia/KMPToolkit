# Platform notes

The engine itself is identical on both platforms. The wake layer is not, and the difference is
large enough to affect what you can promise a user.

## Permissions

**This module's own `AndroidManifest.xml` declares nothing.** It does, however, depend on
`androidx.work` for the Android wake layer, and WorkManager's manifest merges four permissions into
every consuming app:

| Permission | Why WorkManager needs it |
|---|---|
| `android.permission.WAKE_LOCK` | Holds the CPU awake while a job runs |
| `android.permission.RECEIVE_BOOT_COMPLETED` | Restores scheduled work after a reboot |
| `android.permission.FOREGROUND_SERVICE` | For work a consumer chooses to run in the foreground |
| `android.permission.ACCESS_NETWORK_STATE` | Reads connectivity to honor a `NetworkType` constraint |

All four are **install-time** permissions: they appear in your Play Store listing and app-info
screen, and they never prompt the user. None can be removed — stripping one with
`tools:node="remove"` trades a listed permission for a runtime failure on somebody's device.

That set is pinned by name in this module's `LibraryManifestTest`, so a WorkManager upgrade that
starts asking for a fifth fails the build here rather than arriving silently in your app.

Notably **absent**, and asserted absent:

- `INTERNET` — the engine never opens a socket; your handlers do, through your own HTTP client.
- Every dangerous permission (camera, microphone, storage, contacts, location, notifications).
- `SCHEDULE_EXACT_ALARM` / `USE_EXACT_ALARM` — the backoff alarm is a coroutine `delay`, not an
  `AlarmManager` alarm. If you need real exact-time scheduling, that is
  [`kmptoolkit-scheduler`](../kmptoolkit-scheduler/01-overview.md).

If you do not use `createWorkManagerWakeScheduler`, the four are still merged — a manifest merge is
transitive and does not know which classes you call. Excluding the dependency is possible but then
`UploaderDrainWorker` will not resolve; if that trade matters to you, say so and it can become its own
artifact.

iOS has no permission story here at all: `BGTaskScheduler` needs an `Info.plist` entry and a
background mode, not a permission, and never prompts.

## Android — WorkManager

```kotlin
val uploader: UploaderEngine = createUploaderEngine(
    store = store,
    handlers = handlers,
    scope = applicationScope,
    wakeScheduler = createWorkManagerWakeScheduler(
        context = context,
        config = WorkManagerWakeConfig(requiresNetwork = true),
        logger = logger,
    ),
).also { it.start() }

UploaderEngineRegistry.register(uploader)
```

**How it behaves.** A non-empty queue arms one unique `OneTimeWorkRequest`, network-gated by
default, with exponential backoff. `ExistingWorkPolicy.KEEP` makes the engine's frequent arming
cheap — during a burst it arms one per enqueue, and all but the first are no-ops. An empty queue
cancels the work.

**What the job does.** Nothing to the queue. `UploaderDrainWorker` waits for the registered engine and
calls `awaitDrained`, keeping the process alive while the engine works. Draining from the worker as
well would put two drains in one process and break single-flight. If the queue has not emptied within
`drainBudget`, it returns `Result.retry()` and WorkManager re-wakes with backoff until the engine's
own `cancelWake` removes the work.

**The unique-work name** defaults to `<applicationId>.uploader.wake`. WorkManager's namespace is
global to the app, so a hardcoded name would collide between two libraries — or between two queues
in one app. Give a second queue its own `uniqueWorkName`.

**Registration is required** for the wake layer to do anything. The worker is constructed
reflectively by WorkManager, outside your object graph; without `UploaderEngineRegistry.register` it
finds nothing and returns `Result.retry()` forever.

**Reliability.** Good. WorkManager persists across reboots and runs in Doze maintenance windows. It
is not instant, and it is not meant to be.

**A failure to schedule cannot break your enqueue.** If `WorkManager.getInstance` throws — an
uninitialized WorkManager, a process without the provider — the scheduler logs it and moves on.
Delivery then falls back to the next app launch.

## iOS — BGTaskScheduler

Three steps, and all three are required. iOS raises at runtime if the identifier is not permitted,
and silently never runs the task if the handler is registered too late.

**1. `Info.plist`**

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
    <string>com.example.app.uploader.drain</string>
</array>
<key>UIBackgroundModes</key>
<array>
    <string>processing</string>
</array>
```

The default identifier is `<bundleId>.uploader.drain`. Read the resolved value from
`BackgroundTaskWakeScheduler.taskIdentifier` if you are unsure, or set `taskIdentifier` explicitly.

**2. Register the handler before `didFinishLaunching` returns**

```swift
BGTaskScheduler.shared.register(
    forTaskWithIdentifier: UploaderWiring.shared.wakeScheduler.taskIdentifier,
    using: nil
) { task in
    UploaderWiring.shared.wakeScheduler.handleWake { drained in
        task.setTaskCompleted(success: drained as! Bool)
    }
    task.expirationHandler = { task.setTaskCompleted(success: false) }
}
```

Registering after `didFinishLaunching` returns is the single most common way to end up with a wake
layer that appears to work and never fires.

**3. Submit the next request yourself if you want a repeating wake.** iOS consumes the request when
it launches the task; the engine submits a new one the next time something is enqueued or a drain
pass leaves work behind, but a queue that is *already* non-empty when the task finishes will not
re-arm on its own until then.

**Reliability.** Opportunistic, and that word is doing a lot of work. iOS decides when — typically
while the device is idle and charging — and may decide never. On the simulator, and whenever
Background App Refresh is switched off, `submitTaskRequest` simply fails; the scheduler logs it and
disarms so a later attempt can retry.

**So the primary path on iOS is the launch-time drain.** `start()` picks up everything the previous
process left behind, which happens whenever the user opens the app. The wake layer shortens the wait;
it does not replace that.

`requiresExternalPower` defaults to `false` deliberately: requiring power makes an already
opportunistic wake considerably rarer.

## Both platforms

- **The wake layer is optional.** Without it, the engine's promise is "delivered while the app runs,
  or on its next launch" — which is already correct behavior, just slower.
- **A wake job must never call `drain()`.** Use `awaitDrained`; single-flight belongs to the started
  engine.
- **Clocks.** Backoff gates and leases are absolute epoch millis, because they must survive process
  death. The engine detects a backwards wall-clock jump (see `UploaderConfig.clockAnomalyFactor`) and
  runs an item whose gate is unreachably far in the future rather than freezing it — and everything
  behind it in its ordering channel — for the duration of the jump. A forwards jump merely runs some
  items early, which is harmless.
- **Ids** are `java.util.UUID` on Android and `NSUUID` on iOS, both canonical UUID strings. Override
  `idGenerator` if your server wants a different idempotency-key shape.
