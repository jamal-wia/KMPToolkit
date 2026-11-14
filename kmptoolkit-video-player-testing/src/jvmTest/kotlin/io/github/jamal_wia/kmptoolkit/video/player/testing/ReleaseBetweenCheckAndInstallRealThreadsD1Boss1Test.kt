package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.Continuation
import kotlin.coroutines.startCoroutine
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.InternalForInheritanceCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking

/**
 * D1 boss round 1, attack B2: the real-thread probe the r13 mutant M3 asked for (publish of the cut step without
 * `!released`). Prepare B runs on a second thread and is held, by a parent [Job] whose `attachChild` waits on a latch,
 * exactly between its `released` check (under the lock) and its `currentLoad.getAndUpdate` (outside it). Prepare A runs
 * on the test thread; an Unconfined collector of the buffered position, woken by A's start-step reset, releases the
 * player (re-entrant, same thread), lets B install itself, and waits until B's load is the current one. A's cut step
 * then finds a replacing load that is not a placeholder while the player is released. `04-api-reference.md`: `release`
 * is final and ends in Idle. Every wait is a latch or a bounded spin on a cell the player writes; no sleep.
 */
@OptIn(ExperimentalCoroutinesApi::class, InternalCoroutinesApi::class, InternalForInheritanceCoroutinesApi::class)
class ReleaseBetweenCheckAndInstallRealThreadsD1Boss1Test {

    private val rig: InlineRigD1R11 = InlineRigD1R11()
    private val second: VideoSource = VideoSource.Remote("https://example.test/b.mp4")

    @AfterTest
    fun tearDown() {
        rig.close()
    }

    /** A parent job that parks the thread of the load job of prepare B, when it attaches to its parent, until [go] opens. */
    private class HoldingParent(
        val checked: CountDownLatch,
        val go: CountDownLatch,
        private val delegate: CompletableJob = Job(),
    ) : Job by delegate {
        @Volatile private var held: Boolean = false

        // Delegation would forward the context lookups to the delegate, which answers with itself: this wrapper must be
        // the Job the context returns.
        @Suppress("UNCHECKED_CAST")
        override fun <E : kotlin.coroutines.CoroutineContext.Element> get(key: kotlin.coroutines.CoroutineContext.Key<E>): E? =
            if (key == Job) this as E else null

        override fun <R> fold(initial: R, operation: (R, kotlin.coroutines.CoroutineContext.Element) -> R): R = operation(initial, this)

        override fun minusKey(key: kotlin.coroutines.CoroutineContext.Key<*>): kotlin.coroutines.CoroutineContext =
            if (key == Job) kotlin.coroutines.EmptyCoroutineContext else this

        val calls: java.util.concurrent.ConcurrentLinkedQueue<String> = java.util.concurrent.ConcurrentLinkedQueue()

        private fun hold(what: String) {
            calls += what
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
            hold("attachChild")
            return delegate.attachChild(child)
        }

        override fun start(): Boolean {
            hold("start")
            return delegate.start()
        }

        override fun invokeOnCompletion(handler: kotlinx.coroutines.CompletionHandler): kotlinx.coroutines.DisposableHandle {
            hold("invokeOnCompletion")
            return delegate.invokeOnCompletion(handler)
        }

        override fun invokeOnCompletion(
            onCancelling: Boolean,
            invokeImmediately: Boolean,
            handler: kotlinx.coroutines.CompletionHandler,
        ): kotlinx.coroutines.DisposableHandle {
            hold("invokeOnCompletion3")
            return delegate.invokeOnCompletion(onCancelling, invokeImmediately, handler)
        }
    }

    private fun currentLoadIsARealOne(): Boolean {
        val cell = rig.player.javaClass.getDeclaredField("currentLoad").apply { isAccessible = true }
        val flow = cell.get(rig.player) as kotlinx.coroutines.flow.StateFlow<*>
        val load: Any = flow.value ?: return false
        val flag = load.javaClass.getDeclaredField("isPlaceholder").apply { isAccessible = true }
        return !(flag.get(load) as Boolean)
    }

    @Test
    fun `a release made from a collector while another thread's prepare installs itself leaves the player Idle`() {
        rig.prepareNow()
        checkNotNull(rig.engine.sink).onVideoSizeChanged(VideoSize(640, 360))
        assertEquals(4_000L, rig.player.bufferedPositionFlow.value)

        val checked = CountDownLatch(1)
        val go = CountDownLatch(1)
        val failure: AtomicReference<Throwable?> = AtomicReference(null)
        val bDone = CountDownLatch(1)
        // Started bare, so the holding parent itself is the Job of prepare B's context (a builder would put its own
        // coroutine there); B resumes on whichever thread completes what it waits for.
        val parent = HoldingParent(checked, go)
        val threadB = Thread {
            suspend { rig.player.prepare(second) }.startCoroutine(
                Continuation(parent) { result ->
                    failure.set(result.exceptionOrNull())
                    bDone.countDown()
                },
            )
        }
        threadB.start()
        assertTrue(checked.await(10, TimeUnit.SECONDS), "B never reached the point after its released check; B done ${bDone.count == 0L}, failure ${failure.get()}, parent calls ${parent.calls}")

        var installedSeen = false
        val fired = rig.reactOnce(rig.player.bufferedPositionFlow) {
            rig.player.release()
            go.countDown()
            // timing-hygiene-ignore: TH2 bound the spin on B's install fails after 10 s instead of hanging
            val deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!currentLoadIsARealOne()) {
                // timing-hygiene-ignore: TH2 bound the same hang bound
                check(System.nanoTime() < deadline) { "B did not install itself within 10 s" }
                Thread.onSpinWait()
            }
            installedSeen = true
        }
        runBlocking { rig.player.prepare(rig.source) }
        threadB.join(30_000L)
        assertTrue(!threadB.isAlive, "thread B did not finish within 30 s")
        assertTrue(bDone.await(30, TimeUnit.SECONDS), "prepare B did not finish within 30 s")
        assertNull(failure.get(), "B threw")
        assertTrue(fired.value && installedSeen, "the interleaving was not reached: fired ${fired.value}, installed $installedSeen")

        assertEquals(VideoPlayerState.Idle, rig.player.stateFlow.value, "a released player ends in Idle; log ${rig.engine.log}")
        rig.player.play()
        assertEquals(VideoPlayerState.Idle, rig.player.stateFlow.value)
        assertEquals(1, rig.engine.log.count { it == "dispose" }, "dispose exactly once: ${rig.engine.log}")
        assertTrue(rig.engine.log.none { it.startsWith("AFTER_DISPOSE") }, "engine called after dispose: ${rig.engine.log}")
    }

    @Test
    fun `a cancelled prepare that installs itself after a whole release calls nothing on the disposed engine`() {
        rig.prepareNow()
        val checked = CountDownLatch(1)
        val go = CountDownLatch(1)
        val parent = HoldingParent(checked, go)
        val outcome: AtomicReference<Throwable?> = AtomicReference(null)
        val bDone = CountDownLatch(1)
        val threadB = Thread {
            suspend { rig.player.prepare(second) }.startCoroutine(
                Continuation(parent) { result ->
                    outcome.set(result.exceptionOrNull())
                    bDone.countDown()
                },
            )
        }
        threadB.start()
        assertTrue(checked.await(10, TimeUnit.SECONDS), "B never reached the point after its released check")
        // A whole release runs between B's released check and B's install, and B's caller is cancelled meanwhile.
        rig.player.release()
        parent.cancel()
        go.countDown()
        threadB.join(30_000L)
        assertTrue(!threadB.isAlive, "thread B did not finish within 30 s")
        assertTrue(bDone.await(30, TimeUnit.SECONDS), "prepare B did not finish within 30 s")

        assertTrue(outcome.get() is kotlinx.coroutines.CancellationException, "B ended with ${outcome.get()}")
        assertEquals(VideoPlayerState.Idle, rig.player.stateFlow.value)
        assertEquals(1, rig.engine.log.count { it == "dispose" }, "dispose exactly once: ${rig.engine.log}")
        assertTrue(rig.engine.log.none { it.startsWith("AFTER_DISPOSE") }, "engine called after dispose: ${rig.engine.log}")
    }
}
