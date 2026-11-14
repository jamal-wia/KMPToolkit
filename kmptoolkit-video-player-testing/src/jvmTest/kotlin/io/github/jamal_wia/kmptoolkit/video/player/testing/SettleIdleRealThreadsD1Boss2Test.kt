package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.InternalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * D1 boss round 2: the shared `settleIdle` step (unload, release, the cancel path of prepare) under REAL threads, with a
 * second thread's `prepare` installing itself in `currentLoad` (outside the lock) while the settling call holds the
 * lock. `04-api-reference.md`: the calls whose result is the point of the call reset the flows and end in `Idle`; an
 * `unload()` or a cancelled `prepare` leaves the state to a `prepare` that replaced it, which shows `Preparing` and then
 * its own result; every state a prepare publishes for its own source stands. The three cases:
 * 1. the cancel path of a prepare from an already-cancelled caller, a second thread's prepare installed from inside the
 *    reset (between two of its writes): the reset stops, `Preparing` is shown, the second prepare ends `Ready`;
 * 2. `unload()` with a second thread's prepare installed between `abandonLoad` and the settle step, the prepare having
 *    no dispatcher (it resumes inline inside `unload` and finishes there): its `Ready` stands (the `finished` guard);
 * 3. the same with a dispatched prepare (it cannot run until `unload` returns): `Preparing`, then its `Ready`.
 * Every wait is a latch or a bounded spin on a cell the player writes; no sleep.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class, InternalForInheritanceCoroutinesApi::class)
class SettleIdleRealThreadsD1Boss2Test {

    private val rig: InlineRigD1R11 = InlineRigD1R11()
    private val other: VideoSource = VideoSource.Remote("https://example.test/c.mp4")
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "boss2-prepare-b") }

    @AfterTest
    fun tearDown() {
        executor.shutdownNow()
        rig.close()
    }

    /**
     * A parent job that parks prepare B's thread, when B's load job attaches to it, until [go] opens. Delegation forwards
     * `plus` to the delegate too, so a context with a dispatcher is built as `dispatcher + parent`, never `parent + x`.
     */
    private class HoldingParent(
        val checked: CountDownLatch,
        val go: CountDownLatch,
        private val delegate: CompletableJob = Job(),
    ) : Job by delegate {
        @Volatile private var held: Boolean = false

        @Suppress("UNCHECKED_CAST")
        override fun <E : CoroutineContext.Element> get(key: CoroutineContext.Key<E>): E? =
            if (key == Job) this as E else null

        override fun <R> fold(initial: R, operation: (R, CoroutineContext.Element) -> R): R = operation(initial, this)

        override fun minusKey(key: CoroutineContext.Key<*>): CoroutineContext = if (key == Job) EmptyCoroutineContext else this


        private fun hold() {
            if (!held && Thread.currentThread().stackTrace.any { it.className.contains("EngineVideoPlayer") }) {
                held = true
                checked.countDown()
                check(go.await(10, TimeUnit.SECONDS)) { "B was never let go" }
            }
        }

        // The load job of prepare B registers itself with its parent between B's released check and B's install.
        // ChildJob and ChildHandle are internal API of kotlinx.coroutines, deprecated at error level; a test-only probe.
        @Suppress("DEPRECATION_ERROR")
        override fun attachChild(child: kotlinx.coroutines.ChildJob): kotlinx.coroutines.ChildHandle {
            hold()
            return delegate.attachChild(child)
        }
    }

    private fun currentLoad(): Any? {
        val cell = rig.player.javaClass.getDeclaredField("currentLoad").apply { isAccessible = true }
        return (cell.get(rig.player) as StateFlow<*>).value
    }

    private fun isPlaceholder(load: Any?): Boolean {
        if (load == null) return false
        val flag = load.javaClass.getDeclaredField("isPlaceholder").apply { isAccessible = true }
        return flag.get(load) as Boolean
    }

    private fun spinUntil(what: String, condition: () -> Boolean) {
        // timing-hygiene-ignore: TH2 bound the spin on another thread's install fails after 10 s instead of hanging
        val deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            // timing-hygiene-ignore: TH2 bound the same hang bound
            check(System.nanoTime() < deadline) { "$what did not happen within 10 s" }
            Thread.onSpinWait()
        }
    }

    private fun pausedAt3000WithSize() {
        rig.prepareNow()
        rig.player.play()
        rig.engine.position = 3_000L
        rig.tick()
        rig.player.pause()
        checkNotNull(rig.engine.sink).onVideoSizeChanged(VideoSize(640, 360))
        assertEquals(VideoPlayerState.Paused(10_000L, 3_000L), rig.player.stateFlow.value)
    }

    private fun stateNames(states: List<VideoPlayerState>): List<String> = synchronized(states) {
        states.map { it::class.simpleName.orEmpty() }
    }

    private fun recordStatesSynchronized(): List<VideoPlayerState> {
        val seen: MutableList<VideoPlayerState> = mutableListOf()
        rig.scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            rig.player.stateFlow.collect { synchronized(seen) { seen += it } }
        }
        return seen
    }

    /** Starts prepare B, held between its released check and its install; returns once it is held. */
    private fun startHeldPrepare(
        parent: HoldingParent,
        context: CoroutineContext,
        outcome: AtomicReference<Result<Unit>?>,
        done: CountDownLatch,
        onThread: Boolean,
    ) {
        val body: () -> Unit = {
            suspend { rig.player.prepare(other) }.startCoroutine(
                Continuation(context) { result ->
                    outcome.set(result)
                    done.countDown()
                },
            )
        }
        if (onThread) Thread(body, "boss2-bare-b").start() else body()
        assertTrue(parent.checked.await(10, TimeUnit.SECONDS), "B never reached the point after its released check; outcome ${outcome.get()?.exceptionOrNull()?.stackTraceToString()?.take(1500)}")
    }

    @Test
    fun `a prepare another thread installs inside the cancel reset shows Preparing and then wins`() {
        pausedAt3000WithSize()
        val states: List<VideoPlayerState> = recordStatesSynchronized()
        // B passed its released check before the cancelled call took the lock; it installs itself from inside the reset.
        val parent = HoldingParent(CountDownLatch(1), CountDownLatch(1))
        val outcome: AtomicReference<Result<Unit>?> = AtomicReference(null)
        val bDone = CountDownLatch(1)
        startHeldPrepare(parent, executor.asCoroutineDispatcher() + parent, outcome, bDone, onThread = false)
        var replaced: Any? = null
        val fired: InlineRigD1R11.Fired = rig.reactOnce(rig.player.playbackPositionFlow) {
            // Inside the cancel path's reset, the lock held by this thread: B installs itself from another thread.
            val cancelled: Any? = currentLoad()
            replaced = cancelled
            parent.go.countDown()
            spinUntil("B's install") { currentLoad() !== cancelled }
        }
        var thrown: Throwable? = null
        runBlocking {
            launch {
                coroutineContext.cancel()
                try {
                    rig.player.prepare(rig.source)
                } catch (caught: CancellationException) {
                    thrown = caught
                }
            }.join()
        }
        assertTrue(bDone.await(10, TimeUnit.SECONDS), "prepare B did not finish within 10 s; states ${stateNames(states)}")
        assertTrue(fired.value && replaced != null, "the interleaving was not reached")
        assertTrue(thrown is CancellationException, "the cancelled caller received $thrown")
        assertTrue(checkNotNull(outcome.get()).isSuccess, "B ended with ${outcome.get()}")
        assertEquals(VideoPlayerState.Ready(10_000L), rig.player.stateFlow.value, "states ${stateNames(states)}; log ${rig.engine.log}")
        assertEquals(listOf("Paused", "Preparing", "Ready"), stateNames(states), "never Idle, never the old Paused again")
        assertEquals(4_000L, rig.player.bufferedPositionFlow.value)
        assertEquals(0L, rig.player.playbackPositionFlow.value)
        assertEquals(null, rig.player.videoSizeFlow.value, "B's start step reset the size the cut reset left")
        assertEquals(false, rig.player.isBufferingFlow.value)
        assertTrue("start" !in rig.engine.log.dropWhile { it != "release" }, "nothing started on the freed engine: ${rig.engine.log}")
        assertEquals(emptyList(), rig.escaped)
    }

    @Test
    fun `an unload that another thread's undispatched prepare replaces before its settle step keeps that prepare's Ready`() {
        pausedAt3000WithSize()
        val states: List<VideoPlayerState> = recordStatesSynchronized()
        val parent = HoldingParent(CountDownLatch(1), CountDownLatch(1))
        val outcome: AtomicReference<Result<Unit>?> = AtomicReference(null)
        val done = CountDownLatch(1)
        startHeldPrepare(parent, parent, outcome, done, onThread = true)
        var hooked = false
        rig.engine.onRelease = {
            if (!hooked) {
                hooked = true
                // Inside unload's abandonLoad, after the placeholder took the slot: B installs itself now.
                parent.go.countDown()
                spinUntil("B's install") { currentLoad().let { it != null && !isPlaceholder(it) } }
            }
        }
        rig.player.unload()
        assertTrue(done.await(10, TimeUnit.SECONDS), "prepare B did not finish within 10 s")
        assertTrue(hooked, "the interleaving was not reached")
        assertTrue(checkNotNull(outcome.get()).isSuccess, "B ended with ${outcome.get()}")
        assertEquals(VideoPlayerState.Ready(10_000L), rig.player.stateFlow.value, "states ${stateNames(states)}; log ${rig.engine.log}")
        assertEquals("Ready", stateNames(states).last(), "states ${stateNames(states)}")
        assertEquals(4_000L, rig.player.bufferedPositionFlow.value)
        assertEquals(null, rig.player.videoSizeFlow.value)
        assertEquals(1, rig.engine.log.count { it == "load" } - 1, "B loaded once: ${rig.engine.log}")
        assertEquals(emptyList(), rig.escaped)
    }

    @Test
    fun `an unload that another thread's dispatched prepare replaces before its settle step shows Preparing and then its Ready`() {
        pausedAt3000WithSize()
        val states: List<VideoPlayerState> = recordStatesSynchronized()
        val parent = HoldingParent(CountDownLatch(1), CountDownLatch(1))
        val outcome: AtomicReference<Result<Unit>?> = AtomicReference(null)
        val done = CountDownLatch(1)
        startHeldPrepare(parent, executor.asCoroutineDispatcher() + parent, outcome, done, onThread = false)
        var hooked = false
        rig.engine.onRelease = {
            if (!hooked) {
                hooked = true
                parent.go.countDown()
                spinUntil("B's install") { currentLoad().let { it != null && !isPlaceholder(it) } }
            }
        }
        rig.player.unload()
        assertTrue(done.await(10, TimeUnit.SECONDS), "prepare B did not finish within 10 s; states ${stateNames(states)}")
        assertTrue(hooked, "the interleaving was not reached")
        assertTrue(checkNotNull(outcome.get()).isSuccess, "B ended with ${outcome.get()}")
        assertEquals(VideoPlayerState.Ready(10_000L), rig.player.stateFlow.value, "states ${stateNames(states)}; log ${rig.engine.log}")
        assertEquals(listOf("Paused", "Preparing", "Ready"), stateNames(states), "never Idle, never the old Paused again")
        assertEquals(4_000L, rig.player.bufferedPositionFlow.value)
        assertEquals(0L, rig.player.playbackPositionFlow.value)
        assertEquals(null, rig.player.videoSizeFlow.value)
        assertEquals(emptyList(), rig.escaped)
    }
}
