package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Engine-boundary and parity attacks on [VlcjVideoEngine] over [FakeVlcRuntime]. */
@OptIn(ToolkitInternalApi::class)
class VlcjBoundaryAdversarialTest {

    private val runtime = FakeVlcRuntime()
    private val errors: MutableList<String> = CopyOnWriteArrayList()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    @BeforeTest
    fun setUp() {
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e -> errors += "${t.name}: $e" }
    }

    @AfterTest
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        runtime.shutdown()
    }

    private fun newEngine(): VlcjVideoEngine =
        VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { runtime })

    private fun loadClip(engine: VlcjVideoEngine) = runBlocking {
        withTimeout(5_000L) { engine.load(VideoSource.File(TestClip.file.path)) }
    }

    private fun startAndPlay(engine: VlcjVideoEngine): FakeVlcPlayer {
        loadClip(engine)
        engine.start()
        val player: FakeVlcPlayer = runtime.player
        player.fire { playing() }
        runtime.drainTasks()
        return player
    }

    @Test
    fun `a volume that is NaN neither throws nor poisons the seek that was waiting for output`() {
        val engine = newEngine()
        loadClip(engine)
        engine.seekTo(1_200L)
        engine.start()
        engine.setVolume(Float.NaN)
        val player: FakeVlcPlayer = runtime.player
        player.fire { playing() }
        runtime.drainTasks()
        assertEquals(emptyList(), errors.toList(), "an exception escaped on a native thread")
        assertTrue("setTime(1200)" in player.calls, "the parked seek was lost: ${player.calls}")
        engine.dispose()
    }

    @Test
    fun `a rate that is not finite and positive never reaches libvlc`() {
        val engine = newEngine()
        val player: FakeVlcPlayer = startAndPlay(engine)
        for (rate: Float in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -1f)) {
            engine.setSpeed(rate)
        }
        runtime.drainTasks()
        val bad: List<String> = player.calls.filter { it.startsWith("setRate(") && it != "setRate(1.0)" }
        assertEquals(emptyList(), bad, "hostile rates reached libvlc")
        // A valid rate still applies.
        engine.setSpeed(2f)
        runtime.drainTasks()
        assertEquals("setRate(2.0)", player.calls.last { it.startsWith("setRate(") }, "a valid rate was not applied")
        engine.dispose()
    }

    @Test
    fun `a NaN volume set before the load never reaches libvlc`() {
        val engine = newEngine()
        engine.setVolume(Float.NaN)
        val player: FakeVlcPlayer = startAndPlay(engine)
        assertTrue("setVolume(100)" in player.calls, "the default volume was not applied: ${player.calls}")
        assertEquals(emptyList(), errors.toList(), "an exception escaped on a native thread")
        engine.dispose()
    }
}
