package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * libvlc 3.0.21 (probe rate2.c) accepts any float in set_rate (return 0, get_rate echoes it) but plays
 * only 1/32..32: at 0.03 and 1e-3 the clock stays frozen while the state reads Playing. A finite positive
 * rate passes the engine's guard (D-62 V2), and a player configured with a lower minPlaybackSpeed hands one over.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjRateRangeAdversarialTest {

    private val runtime = FakeVlcRuntime()

    @AfterTest
    fun tearDown() {
        runtime.shutdown()
    }

    @Test
    fun `a rate below what libvlc can play is not handed over as it is`() {
        val engine = VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { runtime })
        runBlocking { withTimeout(5_000L) { engine.load(VideoSource.File(TestClip.file.path)) } }
        engine.start()
        val player: FakeVlcPlayer = runtime.player
        player.fire { playing() }
        runtime.drainTasks()
        for (rate: Float in listOf(0.03f, 1e-3f, 1e-10f, Float.MIN_VALUE)) engine.setSpeed(rate)
        runtime.drainTasks()
        val handedOver: List<Float> = player.calls.filter { it.startsWith("setRate(") }
            .map { it.removePrefix("setRate(").removeSuffix(")").toFloat() }
        assertTrue(handedOver.none { it < 1f / 32f }, "rates below libvlc's 1/32 minimum reached it: $handedOver")
        engine.dispose()
    }

    @Test
    fun `a rate above libvlc's maximum is played at 32 and a rate in range is handed over unchanged`() {
        val engine = VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { runtime })
        runBlocking { withTimeout(5_000L) { engine.load(VideoSource.File(TestClip.file.path)) } }
        engine.start()
        val player: FakeVlcPlayer = runtime.player
        player.fire { playing() }
        runtime.drainTasks()
        engine.setSpeed(100f)
        runtime.drainTasks()
        assertEquals("setRate(32.0)", player.calls.last { it.startsWith("setRate(") })
        engine.setSpeed(1.5f)
        runtime.drainTasks()
        assertEquals("setRate(1.5)", player.calls.last { it.startsWith("setRate(") })
        engine.dispose()
    }
}
