package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javafx.application.Platform
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Adversarial tests of the toolkit start and of the FX-thread hand-over, through the engine's seams. */
@OptIn(ToolkitInternalApi::class)
class ToolkitRuntimeAdversarialTest {

    @Test
    fun `sixteen callers around a start that fails busy once start again only until one succeeds`() {
        val starts = AtomicInteger()
        val runtime = ToolkitJavaFxRuntime(
            startToolkit = {
                if (starts.incrementAndGet() == 1) {
                    Thread.sleep(150L)
                    throw FxThreadUnresponsiveException("busy")
                }
            },
            onFxThread = { false },
        )
        val answers: List<AtomicReference<Boolean>> = List(16) { AtomicReference() }
        val threads = List(16) { n -> thread { answers[n].set(runtime.isAvailable()) } }
        threads.forEach { it.join(10_000L) }
        assertTrue(threads.none { it.isAlive }, "a caller hung")
        assertEquals(2, starts.get(), "one busy start, one successful start, nobody else starts")
        assertEquals(15, answers.count { it.get() == true }, "everyone but the busy starter sees it running")
        assertTrue(runtime.isAvailable())
        assertEquals(2, starts.get())
    }

    @Test
    fun `a running toolkit that exits is reported unavailable on the next call without another start`() {
        val starts = AtomicInteger()
        val exited = AtomicBoolean(false)
        val runtime = ToolkitJavaFxRuntime(
            startToolkit = { starts.incrementAndGet() },
            onFxThread = { false },
            hasExited = { exited.get() },
        )
        assertTrue(runtime.isAvailable())
        exited.set(true)
        assertEquals(false, runtime.isAvailable())
        assertEquals(1, starts.get())
    }

    @Test
    fun `a toolkit already started by the host keeps the host's implicit exit policy`() {
        assumeJavaFxMedia()
        FxThread.startToolkit()
        val realStartup: (Runnable) -> Unit = FxThread.startup
        Platform.setImplicitExit(true) // the host app's own choice
        FxThread.startup = { throw IllegalStateException("Toolkit already initialized") }
        try {
            val runtime = ToolkitJavaFxRuntime()
            assertTrue(runtime.isAvailable(), "an already running toolkit is available")
            assertTrue(Platform.isImplicitExit(), "the host's implicit exit policy was overwritten")
        } finally {
            FxThread.startup = realStartup
            Platform.setImplicitExit(false)
        }
    }

    @Test
    fun `eight callers while the real FX thread is busy are all told the toolkit runs once it answers`() {
        assumeJavaFxMedia()
        FxThread.startToolkit()
        val release = CountDownLatch(1)
        val busy = CountDownLatch(1)
        Platform.runLater {
            busy.countDown()
            release.await(8L, TimeUnit.SECONDS)
        }
        assertTrue(busy.await(5L, TimeUnit.SECONDS))
        val runtime = ToolkitJavaFxRuntime()
        val answers: List<AtomicReference<Boolean>> = List(8) { AtomicReference() }
        val threads = List(8) { n -> thread { answers[n].set(runtime.isAvailable()) } }
        Thread.sleep(400L)
        release.countDown()
        threads.forEach { it.join(15_000L) }
        assertTrue(threads.none { it.isAlive }, "a caller hung")
        assertTrue(answers.all { it.get() == true }, "answers: ${answers.map { it.get() }}")
    }

    @Test
    fun `ten prepares after the toolkit exited each fail on the runtime error within the ready timeout and leave no thread behind`() = runBlocking<Unit> {
        assumeJavaFxMedia()
        FxThread.startToolkit()
        val dropped: MutableList<Runnable> = CopyOnWriteArrayList()
        val realRunLater: (Runnable) -> Unit = FxThread.runLater
        val before: Int = Thread.getAllStackTraces().keys.count { it.isAlive }
        FxThread.runLater = { dropped += it } // what runLater does after Platform.exit: drops the block
        try {
            repeat(10) { round: Int ->
                val player = createVideoPlayer(JavaFxVideoEngine(readyTimeoutMs = 250L), VideoPlayerConfig(), Dispatchers.Default)
                withTimeout(5_000L) { player.prepare(VideoSource.Asset(TestClip.ASSET)) }
                val state = player.stateFlow.value
                assertTrue(
                    state is VideoPlayerState.Error && state.cause is JavaFxVideoPlayerException.RuntimeUnavailable,
                    "round $round: $state",
                )
                player.release()
            }
        } finally {
            FxThread.runLater = realRunLater
            dropped.forEach { Platform.runLater(it) }
            val done = CountDownLatch(1)
            Platform.runLater { done.countDown() }
            assertTrue(done.await(5L, TimeUnit.SECONDS), "the FX thread did not drain")
        }
        Thread.sleep(300L)
        val after: Int = Thread.getAllStackTraces().keys.count { it.isAlive }
        assertTrue(after <= before + 4, "threads grew from $before to $after")
    }

    @Test
    fun `sixteen engines loading at once on a runtime that starts once share one start`() = runBlocking<Unit> {
        val starts = AtomicInteger()
        val runtime = ToolkitJavaFxRuntime(
            startToolkit = { starts.incrementAndGet(); Thread.sleep(100L) },
            onFxThread = { false },
        )
        withTimeout(30_000L) {
            List(16) { async(Dispatchers.IO) { runtime.ensureStarted() } }.forEach { it.await() }
        }
        assertEquals(1, starts.get())
    }
}
