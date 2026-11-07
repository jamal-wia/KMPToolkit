package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The garbage-collection safety net: an engine dropped without `release()` while a source is
 * loaded must free the native player it owns, not only the libvlc instance. The fake player holds
 * the callbacks it was given strongly, as VLCJ's listener list does, so an engine that handed
 * itself to the native side could never be collected.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjVideoEngineCleanerTest {

    private val runtime = FakeVlcRuntime()

    @AfterTest
    fun tearDown() {
        runtime.shutdown()
    }

    private fun newEngine(teardown: Executor = Executor { it.run() }): VlcjVideoEngine = VlcjVideoEngine(
        vlcArgs = emptyList(),
        discover = { true },
        createRuntime = { runtime },
        teardown = teardown,
    )

    /** A serial executor that holds its tasks until [runAll], as the real teardown thread may lag. */
    private class DeferredExecutor : Executor {
        // Filled from the Cleaner thread in one test, drained from the test thread.
        private val queue: ConcurrentLinkedQueue<Runnable> = ConcurrentLinkedQueue()

        override fun execute(command: Runnable) {
            queue += command
        }

        fun hasPending(): Boolean = queue.isNotEmpty()

        /** Runs the queued tasks in submission order, including any queued meanwhile. */
        fun runAll() {
            while (true) (queue.poll() ?: break).run()
        }
    }

    private fun assertPlayerFreedBeforeInstance() {
        val player: Int = runtime.log.indexOfFirst { it.startsWith("release@") }
        val instance: Int = runtime.log.indexOfFirst { it.startsWith("runtime.release@") }
        assertTrue(player >= 0 && instance >= 0, "both must be released: ${runtime.log}")
        assertTrue(player < instance, "the native player must be freed before libvlc: ${runtime.log}")
        assertEquals(1, runtime.log.count { it.startsWith("release@") })
        assertEquals(1, runtime.log.count { it.startsWith("runtime.release@") })
    }

    /** Loads a clip and drops the engine; only a weak reference leaves this frame. */
    private fun loadAndDrop(teardown: Executor = Executor { it.run() }): WeakReference<VlcjVideoEngine> {
        val engine: VlcjVideoEngine = newEngine(teardown)
        runBlocking { engine.load(VideoSource.File(TestClip.file.path)) }
        return WeakReference(engine)
    }

    private fun awaitCleared(reference: WeakReference<*>, timeoutMs: Long = 5_000L): Boolean {
        val deadline: Long = System.nanoTime() + timeoutMs * 1_000_000L
        while (reference.get() != null && System.nanoTime() < deadline) {
            System.gc()
            Thread.sleep(20L)
        }
        return reference.get() == null
    }

    private fun awaitTrue(timeoutMs: Long = 5_000L, condition: () -> Boolean): Boolean {
        val deadline: Long = System.nanoTime() + timeoutMs * 1_000_000L
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20L)
        return condition()
    }

    @Test
    fun `a loaded engine dropped without release frees its native player and the instance`() {
        val reference: WeakReference<VlcjVideoEngine> = loadAndDrop()

        assertTrue(awaitCleared(reference), "the native side must not keep the engine reachable")
        assertTrue(awaitTrue { runtime.released == 1 }, "the Cleaner must free libvlc")
        val player: FakeVlcPlayer = runtime.player
        assertTrue("release" in player.calls, "the Cleaner must release the native player too")
        assertEquals(1, player.calls.count { it == "release" })
        assertPlayerFreedBeforeInstance()
    }

    @Test
    fun `an engine that is still referenced is not collected and its player stays open`() {
        val engine: VlcjVideoEngine = newEngine()
        runBlocking { engine.load(VideoSource.File(TestClip.file.path)) }
        val reference: WeakReference<VlcjVideoEngine> = WeakReference(engine)

        assertFalse(awaitCleared(reference, timeoutMs = 1_000L))
        assertEquals(0, runtime.released)
        assertFalse("release" in runtime.player.calls)

        // A live session still delivers events through the weak forwarder.
        runtime.player.fire { lengthChanged(1_234L) }
        assertEquals(1_234L, engine.durationMs())
        engine.dispose()
        assertEquals(1, runtime.released)
        assertTrue("release" in runtime.player.calls)
        assertNotNull(reference.get())
    }

    @Test
    fun `disposing twice frees the player and the instance exactly once`() {
        val engine: VlcjVideoEngine = newEngine()
        runBlocking { engine.load(VideoSource.File(TestClip.file.path)) }

        engine.dispose()
        engine.dispose()

        assertEquals(1, runtime.player.calls.count { it == "release" })
        assertEquals(1, runtime.released)
        assertPlayerFreedBeforeInstance()
    }

    @Test
    fun `release then dispose frees the player exactly once and before the instance`() {
        val engine: VlcjVideoEngine = newEngine()
        runBlocking { engine.load(VideoSource.File(TestClip.file.path)) }

        engine.release()
        engine.dispose()

        assertEquals(1, runtime.player.calls.count { it == "release" })
        assertPlayerFreedBeforeInstance()
    }

    @Test
    fun `a Cleaner task that runs behind the session's own queued teardown does not release twice`() {
        // dispose() queues Session.teardown first, then the Cleaner's claim-and-close of the same
        // session, then the instance. With the teardown thread lagging, both tasks are pending at
        // once: the second must find the session already claimed.
        val deferred = DeferredExecutor()
        val engine: VlcjVideoEngine = newEngine(deferred)
        runBlocking { engine.load(VideoSource.File(TestClip.file.path)) }

        engine.release()
        engine.dispose()
        assertEquals(0, runtime.released, "nothing may be freed before the teardown thread runs")
        assertFalse("release" in runtime.player.calls)
        deferred.runAll()

        assertPlayerFreedBeforeInstance()
    }

    @Test
    fun `a Cleaner run on a lagging teardown thread frees the player before the instance`() {
        val deferred = DeferredExecutor()
        val reference: WeakReference<VlcjVideoEngine> = loadAndDrop(deferred)

        assertTrue(awaitCleared(reference))
        // The Cleaner may run a moment after the reference clears; wait for its tasks.
        assertTrue(awaitTrue { deferred.hasPending() }, "the Cleaner must queue the teardown")
        deferred.runAll()

        assertPlayerFreedBeforeInstance()
    }

    @Test
    fun `dispose racing the engine's own teardown thread neither throws nor releases twice`() {
        // The Cleaner action snapshots the open sessions while the teardown thread may be claiming
        // the very session release() just queued; repeated on a real thread to hit that window.
        repeat(ROUNDS) {
            val engine: VlcjVideoEngine = newEngine(VlcjVideoEngine.newTeardownExecutor())
            runBlocking { engine.load(VideoSource.File(TestClip.file.path)) }
            engine.release()
            engine.dispose()
        }

        assertTrue(awaitTrue(timeoutMs = 20_000L) { runtime.released == ROUNDS }, "released=${runtime.released}")
        assertEquals(ROUNDS, runtime.players.size)
        assertTrue(runtime.players.all { player: FakeVlcPlayer -> player.calls.count { it == "release" } == 1 })
    }

    @Test
    fun `an engine that never loaded has nothing native to free when it is dropped`() {
        val reference: WeakReference<VlcjVideoEngine> = WeakReference(newEngine())

        assertTrue(awaitCleared(reference))
        assertNull(runtime.players.firstOrNull())
        assertEquals(0, runtime.released)
    }

    private companion object {
        const val ROUNDS: Int = 400
    }
}
