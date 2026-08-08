package io.github.jamal_wia.kmptoolkit.outbox

import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A process-wide slot holding the running engine, for code the operating system constructs outside
 * your object graph.
 *
 * This is the module's one piece of global state, and it exists for one reason: a WorkManager
 * `Worker` is instantiated reflectively by the platform, and an iOS `BGTask` handler runs at cold
 * launch before anything of yours has been wired. Neither can be handed a dependency. They need a
 * way to find the engine, and a global slot is the only mechanism the platforms leave open.
 *
 * Everything else takes the engine as a parameter. If your code *can* be given the engine, give it
 * the engine — do not reach in here.
 *
 * ## Usage
 *
 * Register right after starting the engine, and only if you use the platform wake layer or settle
 * detached deliveries from an OS-constructed executor:
 *
 * ```kotlin
 * val engine: OutboxEngine = createOutboxEngine(...).also { it.start() }
 * OutboxEngineRegistry.register(engine)
 * ```
 *
 * A wake job or executor then waits for it, tolerating the case where the OS got there before your
 * bootstrap finished:
 *
 * ```kotlin
 * val engine: OutboxEngine? = OutboxEngineRegistry.await(5.seconds)
 *     ?: return Result.retry() // startup has not produced one; ask the platform to try again
 * engine.settle(itemId, SettleResult.Delivered)
 * ```
 *
 * ## Contract
 *
 * - **One slot.** Registering a second engine replaces the first; the replaced engine keeps
 *   working, it is simply no longer reachable through here. Two engines in one process is a
 *   supported configuration (two independent queues), but only one of them can own the platform
 *   wake entry points.
 * - **Registration is explicit.** [createOutboxEngine] and [OutboxEngine.start] never register on
 *   your behalf — a library that installs itself into global state behind your back is impossible
 *   to reason about in a test, and impossible to use twice.
 * - **[OutboxEngine.close] clears the slot** if the closing engine is the registered one, so a
 *   closed engine is never handed out.
 * - Safe to call from any thread.
 */
public object OutboxEngineRegistry {

    private val slot: MutableStateFlow<OutboxEngine?> = MutableStateFlow(null)

    /** The currently registered engine, or `null`. A non-suspending peek; prefer [await]. */
    public val current: OutboxEngine?
        get() = slot.value

    /**
     * Puts [engine] in the slot, replacing whatever was there.
     *
     * @param engine the engine platform entry points should find. Register it after [OutboxEngine.start],
     *   so that anyone who finds it finds a running one.
     */
    public fun register(engine: OutboxEngine) {
        slot.value = engine
    }

    /**
     * Empties the slot if [engine] is what it holds, and does nothing otherwise.
     *
     * The identity check matters during a restart: if a fresh engine has already registered, a
     * late [unregister] from the old one must not remove it.
     *
     * @param engine the engine to remove.
     */
    public fun unregister(engine: OutboxEngine) {
        slot.compareAndSet(expect = engine, update = null)
    }

    /**
     * Returns the registered engine, waiting up to [timeout] for one to appear.
     *
     * The wait is what makes a wake that fires during app startup work: instead of failing and
     * asking the platform to reschedule, the caller simply suspends until registration lands. A few
     * seconds is generous — a bootstrap registers within milliseconds.
     *
     * @param timeout how long to wait for a registration.
     * @return the engine, or `null` if none appeared in time. A `null` means the outcome did not
     *   land: a platform executor should ask to be retried rather than treat it as success.
     */
    public suspend fun await(timeout: Duration): OutboxEngine? =
        withTimeoutOrNull(timeout) { slot.filterNotNull().first() }
}
