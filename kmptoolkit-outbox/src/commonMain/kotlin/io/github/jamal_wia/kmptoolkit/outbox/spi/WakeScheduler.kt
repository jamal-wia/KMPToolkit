package io.github.jamal_wia.kmptoolkit.outbox.spi

/**
 * The bridge to an OS-level scheduler that can revive a **killed** process to finish delivering the
 * queue — WorkManager on Android, `BGTaskScheduler` on iOS.
 *
 * Without one the engine's guarantee is "delivered while the app is alive, or on its next launch".
 * With one, the operating system re-launches the app in the background and the queue drains
 * without the user ever reopening it. It is an accelerator on top of the launch-time drain, never
 * a replacement for it — see `docs/kmptoolkit-outbox/05-platform-notes.md` for how far each
 * platform's promise actually goes.
 *
 * This module ships an implementation for each platform (`createWorkManagerWakeScheduler` on
 * Android, `createBackgroundTaskWakeScheduler` on iOS); you only implement this interface yourself
 * if you want different platform behavior, or none at all.
 *
 * ## Contract
 *
 * - **Both calls must be idempotent and cheap.** The engine calls [scheduleWake] on every enqueue
 *   and at the end of every drain pass that leaves work behind — during a burst that is once per
 *   item. Deduplicate on your side (unique work, a single task identifier, an armed flag). Neither
 *   call may suspend or block: they run on the enqueueing coroutine.
 * - **Neither call may throw.** A platform that refuses to schedule (background refresh disabled,
 *   a quota exhausted) is a degradation, not an error — swallow it, log it, and let the engine
 *   carry on. An exception here would propagate into an unrelated `enqueue`.
 * - **[scheduleWake] means "there is work owed"**, not "run now". The engine does not ask for a
 *   deadline; it is the platform's decision when to grant execution.
 * - **[cancelWake] means "nothing is owed"** — it is called when a drain pass finds the queue
 *   empty. It must be safe to call when nothing is armed.
 * - **The woken process is expected to run its normal startup**, construct and `start()` the
 *   engine, and let the engine drain. A wake job should keep the process alive while that happens
 *   (`OutboxEngine.awaitDrained`) rather than draining the queue itself — draining from two places
 *   at once would break the engine's single-flight guarantee.
 *
 * [NoOp] is the default. The engine is fully functional without any wake layer.
 */
public interface WakeScheduler {

    /** Arms — or leaves armed — the platform wake, because the queue is not empty. */
    public fun scheduleWake()

    /** Disarms the platform wake, because the queue is empty and nothing is owed. */
    public fun cancelWake()

    /**
     * The default: no OS-level wake at all.
     *
     * Delivery then happens while the app is running and on its next launch, which is the whole
     * guarantee on any platform where you have not wired a wake adapter.
     */
    public object NoOp : WakeScheduler {
        override fun scheduleWake(): Unit = Unit
        override fun cancelWake(): Unit = Unit
    }
}
