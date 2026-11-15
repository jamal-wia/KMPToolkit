@file:OptIn(ExperimentalAtomicApi::class)

package io.github.jamal_wia.kmptoolkit.location

import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/*
 * Support for the iOS heading tests (group loc-heading): a fake device whose managers record every call with
 * thread-safe counters, a confine that either runs inline or queues until the test drains it (to land a cancel
 * between the creation and the teardown of a manager), and a recording logger. Nothing here touches CoreLocation,
 * so these tests also run where there is no compass and no authorization.
 */

/** A [Logger] that records every message of every level. */
internal class RecordingLogger : Logger {
    private val entries: AtomicReference<List<Pair<LogLevel, String>>> = AtomicReference(emptyList())

    override val tag: String = "RecordingLogger"

    val messages: List<Pair<LogLevel, String>> get() = entries.load()

    override fun isLoggable(level: LogLevel): Boolean = true

    override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
        val line: String = message()
        while (true) {
            val before: List<Pair<LogLevel, String>> = entries.load()
            if (entries.compareAndSet(before, before + (level to line))) return
        }
    }
}

/** A confine that runs every block at once, on the calling thread. */
internal val inlineConfine: (() -> Unit) -> Unit = { block: () -> Unit -> block() }

/** A confine that queues blocks, in order, until [drain]; single-threaded tests only. */
internal class DeferredConfine {
    private val queue: ArrayDeque<() -> Unit> = ArrayDeque()

    val pending: Int get() = queue.size

    val confine: (() -> Unit) -> Unit = { block: () -> Unit -> queue.addLast(block) }

    /** Runs queued blocks in order, including ones queued while draining. */
    fun drain() {
        while (queue.isNotEmpty()) queue.removeFirst().invoke()
    }
}

/** A scripted [IosHeadingDevice]. */
internal class FakeHeadingDevice(override val isHeadingAvailable: Boolean = true) : IosHeadingDevice {

    /** Whether managers opened from now on start authorized. */
    @Volatile
    var authorizedAtOpen: Boolean = false

    /** Thrown by the next and every later `openManager` when set. */
    @Volatile
    var openFailure: Exception? = null

    /** Thrown by `startHeading` of managers opened from now on when set. */
    @Volatile
    var startHeadingFailure: Throwable? = null

    /** Thrown by `release` of managers opened from now on when set, after the manager was released. */
    @Volatile
    var releaseFailure: Throwable? = null

    /** Runs inside every manager's `startHeading`, after it took effect: a manager that delivers in place. */
    @Volatile
    var afterStartHeading: (FakeManager) -> Unit = { }

    /** Runs inside every manager's `startLocation`, after it took effect: a manager that delivers in place. */
    @Volatile
    var afterStartLocation: (FakeManager) -> Unit = { }

    /** Runs after a manager was recorded (1-based count), on the opening thread. */
    @Volatile
    var afterOpen: (count: Int) -> Unit = { }

    val openCalls: AtomicInt = AtomicInt(0)
    private val live: AtomicReference<List<FakeManager>> = AtomicReference(emptyList())
    private val everOpened: AtomicReference<List<FakeManager>> = AtomicReference(emptyList())
    private val problems: AtomicReference<List<String>> = AtomicReference(emptyList())

    val liveManagers: List<FakeManager> get() = live.load()
    val allManagers: List<FakeManager> get() = everOpened.load()
    val problemList: List<String> get() = problems.load()

    fun report(problem: String) {
        while (true) {
            val before: List<String> = problems.load()
            if (problems.compareAndSet(before, before + problem)) return
        }
    }

    override fun openManager(
        events: IosHeadingEvents,
        orientation: ScreenRotation,
        headingFilterDegrees: Double,
    ): IosHeadingManager {
        openCalls.incrementAndFetch()
        openFailure?.let { failure: Exception -> throw failure }
        val manager = FakeManager(events, orientation, headingFilterDegrees, authorizedAtOpen, startHeadingFailure, releaseFailure)
        while (true) {
            val before: List<FakeManager> = live.load()
            if (live.compareAndSet(before, before + manager)) break
        }
        while (true) {
            val before: List<FakeManager> = everOpened.load()
            if (everOpened.compareAndSet(before, before + manager)) break
        }
        afterOpen(everOpened.load().size)
        return manager
    }

    inner class FakeManager(
        val events: IosHeadingEvents,
        val orientation: ScreenRotation,
        val headingFilterDegrees: Double,
        authorized: Boolean,
        private val startHeadingFailure: Throwable?,
        private val releaseFailure: Throwable? = null,
    ) : IosHeadingManager {

        private val authorizedFlag = AtomicBoolean(authorized)
        private val locationFlag = AtomicBoolean(false)
        private val headingFlag = AtomicBoolean(false)
        private val delegateNil = AtomicBoolean(false)

        val headingStarts: AtomicInt = AtomicInt(0)
        val locationStarts: AtomicInt = AtomicInt(0)
        val locationStops: AtomicInt = AtomicInt(0)
        val releases: AtomicInt = AtomicInt(0)

        val locationRunning: Boolean get() = locationFlag.load()
        val headingRunning: Boolean get() = headingFlag.load()

        /** `true` once released: both updates stopped and the delegate set to nil. */
        val isReleased: Boolean get() = delegateNil.load()

        var authorized: Boolean
            get() = authorizedFlag.load()
            set(value) = authorizedFlag.store(value)

        override val isAuthorized: Boolean
            get() {
                if (delegateNil.load()) report("isAuthorized after release")
                return authorizedFlag.load()
            }

        override fun startHeading() {
            if (delegateNil.load()) report("startHeading after release")
            startHeadingFailure?.let { failure: Throwable -> throw failure }
            headingStarts.incrementAndFetch()
            headingFlag.store(true)
            afterStartHeading(this)
        }

        override fun startLocation() {
            if (!authorizedFlag.load()) report("startLocation while unauthorized")
            if (delegateNil.load()) report("startLocation after release")
            locationStarts.incrementAndFetch()
            locationFlag.store(true)
            afterStartLocation(this)
        }

        override fun stopLocation() {
            if (delegateNil.load()) report("stopLocation after release")
            locationStops.incrementAndFetch()
            locationFlag.store(false)
        }

        override fun release() {
            releases.incrementAndFetch()
            headingFlag.store(false)
            locationFlag.store(false)
            delegateNil.store(true)
            while (true) {
                val before: List<FakeManager> = live.load()
                if (live.compareAndSet(before, before - this)) break
            }
            releaseFailure?.let { failure: Throwable -> throw failure }
        }
    }
}
