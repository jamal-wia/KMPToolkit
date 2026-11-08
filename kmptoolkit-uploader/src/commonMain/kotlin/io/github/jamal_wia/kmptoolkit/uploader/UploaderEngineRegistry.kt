package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.filter
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
 * val engine: UploaderEngine = createUploaderEngine(...).also { it.start() }
 * UploaderEngineRegistry.register(engine)
 * ```
 *
 * A wake job or executor then waits for it, tolerating the case where the OS got there before your
 * bootstrap finished:
 *
 * ```kotlin
 * val engine: UploaderEngine? = UploaderEngineRegistry.await(5.seconds)
 *     ?: return Result.retry() // startup has not produced one; ask the platform to try again
 * engine.settle(itemId, claim, SettleResult.Delivered) // both from the AttemptContext it was handed
 * ```
 *
 * ## Contract
 *
 * - **One slot.** Registering a second engine replaces the first; the replaced engine keeps
 *   working, it is simply no longer reachable through here. Two engines in one process is a
 *   supported configuration (two independent queues, each over a store of its own), but only one of them can own
 *   the platform wake entry points.
 * - **Upload transports reach only the engine `createUploaderEngine` built.** A wrapper registered in its place
 *   (a decorator around [UploaderEngine]) serves wake jobs ([UploaderEngine.awaitDrained]), but an upload
 *   transport and [UploadGateway] find the library's engine behind it unavailable and retry. If you use an
 *   [UploadHandler], register the engine itself.
 * - **Registration is explicit.** [createUploaderEngine] and [UploaderEngine.start] never register on
 *   your behalf — a library that installs itself into global state behind your back is impossible
 *   to reason about in a test, and impossible to use twice.
 * - **[UploaderEngine.close] clears the slot** if the closing engine is the registered one, so a
 *   closed engine is never handed out. So does cancelling the scope the engine was created in: an
 *   engine stopped that way is as inert as a closed one, is unregistered, and registering one is
 *   ignored.
 * - Safe to call from any thread.
 */
public object UploaderEngineRegistry {

    private val slot: MutableStateFlow<UploaderEngine?> = MutableStateFlow(null)

    /** The currently registered engine, or `null`. A non-suspending peek; prefer [await]. */
    public val current: UploaderEngine?
        get() = slot.value?.takeUnless { it.isClosedEngine() }

    /**
     * Puts [engine] in the slot, replacing whatever was there.
     *
     * An engine that is already closed (or whose scope is already cancelled) is ignored — the slot, and any live engine in it, stay as they
     * are — and one closed while this call runs is removed again before it can be found, so
     * registration racing a teardown never leaves a closed engine in the slot.
     *
     * @param engine the engine platform entry points should find. Register it after [UploaderEngine.start],
     *   so that anyone who finds it finds a running one.
     */
    public fun register(engine: UploaderEngine) {
        if (engine.isClosedEngine()) return
        slot.value = engine
        // [close] marks the engine closed before it unregisters. If it ran between the check above and
        // the store, its unregister found nothing to remove, so the slot is cleared here instead.
        if (engine.isClosedEngine()) unregister(engine)
    }

    private fun UploaderEngine.isClosedEngine(): Boolean = this is DefaultUploaderEngine && isClosed

    /**
     * Empties the slot if [engine] is what it holds, and does nothing otherwise.
     *
     * The identity check matters during a restart: if a fresh engine has already registered, a
     * late [unregister] from the old one must not remove it.
     *
     * @param engine the engine to remove.
     */
    public fun unregister(engine: UploaderEngine) {
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
    public suspend fun await(timeout: Duration): UploaderEngine? {
        // Looked at first: withTimeoutOrNull never runs its block for a zero (or spent) budget, which would
        // read an engine that is registered right now as absent.
        current?.let { return it }
        return withTimeoutOrNull(timeout) { slot.filterNotNull().filter { !it.isClosedEngine() }.first() }
    }
}
