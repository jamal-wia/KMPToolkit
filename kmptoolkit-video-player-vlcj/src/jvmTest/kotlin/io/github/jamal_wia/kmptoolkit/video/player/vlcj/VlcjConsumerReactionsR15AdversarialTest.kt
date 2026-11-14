package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * D2 round 15, Q2 and Q4: the consumer's own code around the player, over the vlcj engine on its fake runtime.
 *
 * - settings given before a load reach the native player of every NEW source (core API reference), and a muted player keeps its
 *   native volume at 0 whatever `setVolume` says until `setMuted(false)` (vlcj API reference: "VLC volume round(v x 100) %", "as 0f while muted");
 * - three prepares racing: exactly one native player survives and each of the others is freed once; a cancelled prepare leaves none;
 * - the consumer pattern "when the clip completes, play again" written as a state collector, ten rounds, at a speed of 2: each round
 *   reaches libvlc as a play and the speed stays 2.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjConsumerReactionsR15AdversarialTest {

    private val runtime: FakeVlcRuntime = FakeVlcRuntime().also {
        it.parsedInfo = VlcParsedInfo(durationMs = 3_000L, trackCount = 1, videoSize = null)
    }

    @AfterTest
    fun tearDown() {
        runtime.shutdown()
    }

    private fun newPlayer(): VideoPlayer = createVlcjVideoPlayer(
        VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { runtime }),
        VideoPlayerConfig(positionUpdateIntervalMs = ONE_HOUR_MS),
        Dispatchers.Default,
    )

    private fun prepare(player: VideoPlayer) {
        runBlocking { withTimeout(BOUND_MS) { player.prepare(VideoSource.File(TestClip.file.path)) } }
    }

    private fun outputBegins() {
        runtime.drainTasks()
        runtime.player.fire { playing() }
        runtime.drainTasks()
    }

    @Test
    fun `settings given before a load reach the native player of every new source`() {
        val player: VideoPlayer = newPlayer()
        player.setVolume(0.3f)
        player.setPlaybackSpeed(2f)
        prepare(player)
        player.play()
        outputBegins()
        assertEquals(30, runtime.player.volume())
        assertEquals(2f, runtime.player.rate())
        prepare(player)
        assertEquals(2, runtime.players.size, "the second prepare reused the first native player")
        player.play()
        outputBegins()
        assertEquals(30, runtime.player.volume(), "volume of the second source")
        assertEquals(2f, runtime.player.rate(), "speed of the second source")
        player.release()
    }

    @Test
    fun `a muted player keeps its native volume at zero whatever set volume says until unmuted`() {
        val player: VideoPlayer = newPlayer()
        prepare(player)
        player.play()
        outputBegins()
        val fake: FakeVlcPlayer = runtime.player
        player.setMuted(true)
        runtime.drainTasks()
        assertEquals(0, fake.volume(), "muted")
        for (asked: Float in listOf(0.7f, 5f, -1f, Float.NaN, 0.2f)) {
            player.setVolume(asked)
            runtime.drainTasks()
            assertEquals(0, fake.volume(), "volume $asked while muted reached libvlc")
        }
        player.setMuted(false)
        runtime.drainTasks()
        assertEquals(20, fake.volume(), "unmuting restores the last volume")
        player.release()
    }

    @Test
    fun `three prepares racing leave one live native player and free each other one once`() = runBlocking<Unit> {
        val player: VideoPlayer = newPlayer()
        withTimeout(BOUND_MS) {
            List(3) { async(Dispatchers.Default) { player.prepare(VideoSource.File(TestClip.file.path)) } }.awaitAll()
        }
        assertTrue(player.stateFlow.value is VideoPlayerState.Ready, "state ${player.stateFlow.value}")
        runtime.drainTasks()
        withTimeout(BOUND_MS) { while (runtime.players.count { it.calls.contains("release") } < runtime.players.size - 1) delay(5L) }
        val live: Int = runtime.players.count { !it.calls.contains("release") }
        assertEquals(1, live, "live native players: $live of ${runtime.players.size} built")
        for (built: FakeVlcPlayer in runtime.players) {
            assertTrue(built.calls.count { it == "release" } <= 1, "a native player freed twice: ${built.calls}")
        }
        assertEquals(0, runtime.released, "libvlc is kept across prepares")
        player.release()
    }

    @Test
    fun `a cancelled prepare leaves no live native player and the state Idle`() = runBlocking<Unit> {
        runtime.parseResult = null
        val player: VideoPlayer = newPlayer()
        val preparing = launch(Dispatchers.Default) { player.prepare(VideoSource.File(TestClip.file.path)) }
        withTimeout(BOUND_MS) { while (runtime.players.isEmpty()) delay(5L) }
        withTimeout(BOUND_MS) { preparing.cancelAndJoin() }
        withTimeout(BOUND_MS) { while (!runtime.players[0].calls.contains("release")) delay(5L) }
        assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
        player.release()
    }

    @Test
    fun `a collector that plays again on every completion keeps its speed and every round reaches libvlc`() {
        val player: VideoPlayer = newPlayer()
        prepare(player)
        player.setPlaybackSpeed(2f)
        val completions = AtomicInteger()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                player.stateFlow.collect { state: VideoPlayerState ->
                    if (state is VideoPlayerState.Completed) {
                        completions.incrementAndGet()
                        player.play()
                    }
                }
            }
            player.play()
            outputBegins()
            val fake: FakeVlcPlayer = runtime.player
            repeat(10) { round: Int ->
                runtime.player.fire { finished() }
                runtime.drainTasks()
                assertEquals(round + 1, completions.get(), "round $round")
                assertTrue(player.stateFlow.value is VideoPlayerState.Playing, "round $round: state ${player.stateFlow.value}")
                runtime.player.fire { playing() }
                runtime.drainTasks()
            }
            assertEquals(2f, player.playbackSpeedFlow.value)
            assertEquals(2f, fake.rate(), "libvlc lost the speed over the manual loop")
            assertTrue(fake.calls.count { it == "play" } >= 11, "plays reaching libvlc: ${fake.calls.count { it == "play" }}")
            player.pause()
            assertTrue(player.stateFlow.value is VideoPlayerState.Paused, "state ${player.stateFlow.value}")
        } finally {
            scope.cancel()
            player.release()
        }
    }

    private companion object {
        const val ONE_HOUR_MS: Long = 3_600_000L
        const val BOUND_MS: Long = 10_000L
    }
}
