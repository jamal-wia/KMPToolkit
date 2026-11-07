package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.lang.ref.WeakReference
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
    private val engines: MutableList<VlcjVideoEngine> = mutableListOf()

    @AfterTest
    fun tearDown() {
        runtime.shutdown()
    }

    private fun newEngine(): VlcjVideoEngine = VlcjVideoEngine(
        vlcArgs = emptyList(),
        discover = { true },
        createRuntime = { runtime },
        teardown = Executor { it.run() },
    )

    /** Loads a clip and drops the engine; only a weak reference leaves this frame. */
    private fun loadAndDrop(): WeakReference<VlcjVideoEngine> {
        val engine: VlcjVideoEngine = newEngine()
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
    fun `release then dispose frees the player exactly once`() {
        val engine: VlcjVideoEngine = newEngine()
        runBlocking { engine.load(VideoSource.File(TestClip.file.path)) }

        engine.dispose()
        engine.dispose()

        assertEquals(1, runtime.player.calls.count { it == "release" })
        assertEquals(1, runtime.released)
    }

    @Test
    fun `an engine that never loaded has nothing native to free when it is dropped`() {
        val reference: WeakReference<VlcjVideoEngine> = WeakReference(newEngine())

        assertTrue(awaitCleared(reference))
        assertNull(runtime.players.firstOrNull())
        assertEquals(0, runtime.released)
    }
}
