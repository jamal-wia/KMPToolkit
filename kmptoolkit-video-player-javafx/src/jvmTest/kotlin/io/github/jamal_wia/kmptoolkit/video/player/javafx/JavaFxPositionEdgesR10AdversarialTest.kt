package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 10 (breaker bt), position edges and event order on real JavaFX: what a collector of `playbackPositionFlow` and
 * `stateFlow` sees across a whole play-through. The playhead never moves backwards during an uninterrupted play (also
 * across a pause, a resume and speed changes), never passes the duration, equals the duration when Completed is
 * delivered, and Completed is delivered once per play-through (also across `replay()`). A `stop()` leaves the player
 * Ready: no completion reported before it may arrive after it.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxPositionEdgesR10AdversarialTest {

    private lateinit var player: VideoPlayer
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Unconfined)
    private val positions: MutableList<Long> = CopyOnWriteArrayList()
    private val states: MutableList<VideoPlayerState> = CopyOnWriteArrayList()

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
        player = createVideoPlayer(JavaFxVideoEngine(readyTimeoutMs = 15_000L), VideoPlayerConfig(positionUpdateIntervalMs = 25L))
        scope.launch(start = CoroutineStart.UNDISPATCHED) { player.playbackPositionFlow.collect { positions += it } }
        scope.launch(start = CoroutineStart.UNDISPATCHED) { player.stateFlow.collect { states += it } }
        runBlocking { withTimeout(20_000L) { player.prepare(VideoSource.Asset(TestClip.ASSET)) } }
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
        if (::player.isInitialized) player.release()
    }

    private fun awaitCompleted(count: Int, timeoutMs: Long = 9_000L) {
        awaitTrue(timeoutMs, { "Completed number $count never arrived; states ${states.map { it::class.simpleName }}" }) {
            states.count { it is VideoPlayerState.Completed } >= count
        }
    }

    private fun assertMonotone(from: Int, label: String) {
        val slice: List<Long> = positions.drop(from)
        for (index: Int in 1 until slice.size) {
            if (slice[index] < slice[index - 1]) fail("$label: position went back from ${slice[index - 1]} to ${slice[index]} (index $index of $slice)")
        }
        assertTrue(slice.all { it in 0L..TestClip.DURATION_MS }, "$label: a position outside 0..${TestClip.DURATION_MS}: $slice")
    }

    @Test
    fun `an uninterrupted play never moves the playhead back and ends exactly at the duration with one completion`() {
        val mark: Int = positions.size
        player.play()
        awaitCompleted(1)
        Thread.sleep(300L)
        assertMonotone(mark, "plain play")
        assertEquals(TestClip.DURATION_MS, player.playbackPositionFlow.value)
        assertEquals(1, states.count { it is VideoPlayerState.Completed }, "${states.map { it::class.simpleName }}")
        val completed: VideoPlayerState.Completed = states.last { it is VideoPlayerState.Completed } as VideoPlayerState.Completed
        assertEquals(TestClip.DURATION_MS, completed.duration)
    }

    @Test
    fun `pause and resume at the normal speed keep the playhead monotone and the paused position still`() {
        player.play()
        awaitTrue(5_000L, { "never advanced" }) { player.playbackPositionFlow.value > 300L }
        repeat(3) {
            player.pause()
            val paused: VideoPlayerState = player.stateFlow.value
            assertTrue(paused is VideoPlayerState.Paused, "$paused")
            val at: Long = (paused as VideoPlayerState.Paused).currentPosition
            Thread.sleep(300L)
            assertEquals(at, (player.stateFlow.value as VideoPlayerState.Paused).currentPosition, "a paused playhead moved")
            assertEquals(at, player.playbackPositionFlow.value)
            player.play()
            Thread.sleep(250L)
        }
        awaitCompleted(1, 12_000L)
        assertMonotone(0, "pause/resume")
        assertEquals(1, states.count { it is VideoPlayerState.Completed })
    }

    @Test
    fun `speed changes while playing keep the playhead monotone`() {
        player.play()
        awaitTrue(5_000L, { "never advanced" }) { player.playbackPositionFlow.value > 200L }
        for (speed: Float in listOf(2f, 0.5f, 1f)) {
            player.setPlaybackSpeed(speed)
            Thread.sleep(300L)
        }
        awaitCompleted(1, 12_000L)
        assertMonotone(0, "speed changes")
    }

    @Test
    fun `replay after the end starts at zero and gives a second completion`() {
        player.play()
        awaitCompleted(1)
        val mark: Int = positions.size
        player.replay()
        awaitTrue(5_000L, { "replay never moved: ${player.stateFlow.value}" }) { player.stateFlow.value is VideoPlayerState.Playing }
        assertTrue(positions.drop(mark).first() <= 100L, "a replay must start from the beginning: ${positions.drop(mark).take(5)}")
        awaitCompleted(2, 9_000L)
        Thread.sleep(200L)
        assertMonotone(mark, "replay")
        assertEquals(2, states.count { it is VideoPlayerState.Completed })
        val sequence: List<String> = states.map { it::class.simpleName.orEmpty() }
        // Ready -> Playing -> Completed -> Playing -> Completed (position-only updates collapse in the state flow)
        // replay() is a seek from Completed (which reads Paused) and a play
        assertEquals(listOf("Idle", "Preparing", "Ready", "Playing", "Completed", "Paused", "Playing", "Completed"), sequence.fold(mutableListOf<String>()) { acc, name -> if (acc.lastOrNull() != name) acc += name; acc })
    }

    @Test
    fun `a stop near the end leaves the player Ready and no earlier completion arrives after it`() {
        val failures: MutableList<String> = mutableListOf()
        repeat(6) { trial: Int ->
            val p: VideoPlayer = createVideoPlayer(JavaFxVideoEngine(readyTimeoutMs = 15_000L), VideoPlayerConfig(positionUpdateIntervalMs = 25L))
            val seen: MutableList<VideoPlayerState> = CopyOnWriteArrayList()
            val local = CoroutineScope(Dispatchers.Unconfined)
            try {
                runBlocking { withTimeout(20_000L) { p.prepare(VideoSource.Asset(TestClip.ASSET)) } }
                local.launch(start = CoroutineStart.UNDISPATCHED) { p.stateFlow.collect { seen += it } }
                p.seekTo(2_700L + trial * 40L)
                p.play()
                Thread.sleep(40L + trial * 30L)
                p.stop()
                val atStop: Int = seen.size
                val state: VideoPlayerState = p.stateFlow.value
                if (state !is VideoPlayerState.Ready) failures += "trial $trial: after stop() the player reads $state"
                Thread.sleep(1_500L)
                val late: List<VideoPlayerState> = seen.drop(atStop)
                if (late.isNotEmpty()) failures += "trial $trial: states after stop(): $late"
            } finally {
                local.cancel()
                p.release()
            }
        }
        assertEquals(emptyList(), failures)
    }
}
