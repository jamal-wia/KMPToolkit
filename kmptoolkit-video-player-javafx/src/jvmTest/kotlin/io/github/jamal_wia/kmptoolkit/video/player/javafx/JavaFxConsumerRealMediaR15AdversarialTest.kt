package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * D2 round 15, Q4 in real time: the shapes the model explorer cannot show (a real decoder, real clip ends, real loop wraps), driven
 * through the PUBLIC factory `createJavaFxVideoPlayer()` and the player's flows only. Real media: runs under the exclusive slot.
 * Every wait is a flow wait with a hang bound that fails with its own message; nothing sleeps to wait.
 *
 * - the end of a 3 s clip at speed 2, played over three times: each play starts the clip over and reaches `Completed` again;
 * - a looping clip at speed 2 over three wraps: the player stays `Playing`, never reports `Completed`, and the position never jumps
 *   back except at a wrap;
 * - a pause/play/seek/speed/replay storm from four threads: afterwards a `stop()`, a speed of 2 and a `play()` move the playhead on
 *   (no freeze) within the bound.
 */
class JavaFxConsumerRealMediaR15AdversarialTest {

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
    }

    private fun newPlayer(): VideoPlayer =
        createJavaFxVideoPlayer(VideoPlayerConfig(positionUpdateIntervalMs = 25L, maxPlaybackSpeed = 4f))

    private fun prepare(player: VideoPlayer) = runBlocking {
        withTimeout(30_000L) { player.prepare(VideoSource.Asset(TestClip.ASSET)) }
        assertTrue(player.stateFlow.value is VideoPlayerState.Ready, "state ${player.stateFlow.value}")
    }

    @Test
    fun theEndOfAClipAtSpeedTwoPlayedThreeTimesRestartsAndCompletesEveryTime() {
        val player: VideoPlayer = newPlayer()
        try {
            prepare(player)
            player.setPlaybackSpeed(2f)
            repeat(3) { round: Int ->
                player.play()
                runBlocking {
                    withTimeout(HANG_MS) { player.playbackPositionFlow.first { it in 200L..2_500L } }
                }
                val state: VideoPlayerState = player.stateFlow.value
                assertTrue(state is VideoPlayerState.Playing, "round $round: $state after the playhead moved")
                runBlocking {
                    withTimeout(HANG_MS) { player.stateFlow.first { it is VideoPlayerState.Completed } }
                }
                assertTrue(player.playbackSpeedFlow.value == 2f, "round $round: the speed is ${player.playbackSpeedFlow.value} after the end")
            }
        } finally {
            player.release()
        }
    }

    @Test
    fun aLoopingClipAtSpeedTwoWrapsThreeTimesStaysPlayingAndNeverJumpsBackOutsideAWrap() {
        val player: VideoPlayer = newPlayer()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            prepare(player)
            player.setRepeatMode(RepeatMode.One)
            player.setPlaybackSpeed(2f)
            val states: MutableList<VideoPlayerState> = CopyOnWriteArrayList()
            scope.launch(start = CoroutineStart.UNDISPATCHED) { player.stateFlow.collect { states += it } }
            val wraps = CountDownLatch(3)
            val positions: MutableList<Long> = CopyOnWriteArrayList()
            val jumps: MutableList<String> = CopyOnWriteArrayList()
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                var last = -1L
                player.playbackPositionFlow.collect { now: Long ->
                    if (last >= 0L && now < last) {
                        if (last - now > WRAP_DROP_MS) wraps.countDown() else if (last - now > 50L) jumps += "$last -> $now"
                    }
                    last = now
                    positions += now
                }
            }
            player.play()
            // timing-hygiene-ignore: TH2 bound the real-time hang bound of a real loop that must wrap three times
            val ok: Boolean = wraps.await(HANG_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
            assertTrue(ok, "fewer than three wraps within $HANG_MS ms; positions ${positions.takeLast(20)}; states ${states.takeLast(5)}")
            assertTrue(states.none { it is VideoPlayerState.Completed }, "a looping clip reported Completed: $states")
            assertTrue(jumps.isEmpty(), "the position jumped back outside a wrap: $jumps")
        } finally {
            scope.cancel()
            player.release()
        }
    }

    @Test
    fun aFourThreadStormOfTransportAndSpeedCallsLeavesAPlayerThatMovesOnAtTheNextPlay() {
        for (trial: Int in 0 until TRIALS) {
            val player: VideoPlayer = newPlayer()
            try {
                prepare(player)
                val failures: MutableList<Throwable> = CopyOnWriteArrayList()
                val go = CountDownLatch(1)
                val threads: List<Thread> = (0 until 4).map { id: Int ->
                    Thread {
                        val rng = Random(SEED_BASE + trial * 10 + id)
                        go.await()
                        try {
                            repeat(STORM_OPS) {
                                when (rng.nextInt(7)) {
                                    0, 1 -> player.play()
                                    2 -> player.pause()
                                    3 -> player.seekTo(rng.nextLong(0L, 3_000L))
                                    4, 5 -> player.setPlaybackSpeed(listOf(0.5f, 1f, 2f, 3f, 4f)[rng.nextInt(5)])
                                    else -> player.replay()
                                }
                            }
                        } catch (t: Throwable) {
                            failures += t
                        }
                    }.apply { isDaemon = true; start() }
                }
                go.countDown()
                threads.forEach { it.join(HANG_MS) }
                assertTrue(threads.none { it.isAlive }, "trial $trial: a storm thread did not finish")
                assertTrue(failures.isEmpty(), "trial $trial: a call threw $failures")
                player.stop()
                player.setPlaybackSpeed(2f)
                player.play()
                runBlocking {
                    withTimeout(HANG_MS) { player.playbackPositionFlow.first { it >= 1_200L } }
                }
            } finally {
                player.release()
            }
        }
    }


    /**
     * The best slope (media ms per wall ms) of a stretch of at least 300 ms without a wrap, in [windowMs] of flow time: the playhead
     * as (nanoTime, position) pairs taken when the flow value arrives. A wrap (the position drops by more than 500 ms) ends a
     * stretch; a frozen playhead gives a slope of 0 for every stretch, so it cannot pass a threshold above 0.
     */
    private fun slopeOver(player: VideoPlayer, windowMs: Long): Double {
        val samples: MutableList<Pair<Long, Long>> = CopyOnWriteArrayList()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val done = CountDownLatch(1)
        // timing-hygiene-ignore: TH2 producer the stamp is taken where the flow value arrives, never read after a sleep
        val start: Long = System.nanoTime()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            player.playbackPositionFlow.collect { position: Long ->
                // timing-hygiene-ignore: TH2 producer the stamp is taken where the flow value arrives, never read after a sleep
                val now: Long = System.nanoTime()
                samples += now to position
                if (now - start > windowMs * NANOS_PER_MILLI) done.countDown()
            }
        }
        assertTrue(done.await(HANG_MS, java.util.concurrent.TimeUnit.MILLISECONDS), "the position flow stopped for $HANG_MS ms")
        scope.cancel()
        val points: List<Pair<Long, Long>> = samples.toList()
        var best = 0.0
        var from = 0
        for (index: Int in 1..points.size) {
            val wrapped: Boolean = index == points.size || points[index].second < points[index - 1].second - 500L
            if (wrapped) {
                val first: Pair<Long, Long> = points[from]
                val last: Pair<Long, Long> = points[index - 1]
                val wallMs: Double = (last.first - first.first) / 1_000_000.0
                if (wallMs >= 300.0) best = maxOf(best, (last.second - first.second) / wallMs)
                from = index
            }
        }
        return best
    }

    @Test
    fun aReplayStormAtSpeedTwoLeavesAPlayheadThatRunsAtTwo() {
        val player: VideoPlayer = newPlayer()
        try {
            prepare(player)
            player.setRepeatMode(RepeatMode.One)
            player.setPlaybackSpeed(2f)
            player.play()
            runBlocking { withTimeout(HANG_MS) { player.playbackPositionFlow.first { it > 300L } } }
            repeat(8) {
                player.replay()
                runBlocking { withTimeout(HANG_MS) { player.playbackPositionFlow.first { it in 0L..400L } } }
            }
            // the speed is given back after about a second at most; measure after that
            runBlocking { withTimeout(HANG_MS) { player.playbackPositionFlow.first { it > 1_000L } } }
            runBlocking { withTimeout(HANG_MS) { player.playbackPositionFlow.first { it in 0L..300L } } }
            runBlocking { withTimeout(HANG_MS) { player.playbackPositionFlow.first { it > 200L } } }
            val slope: Double = slopeOver(player, 2_500L)
            assertTrue(player.stateFlow.value is VideoPlayerState.Playing, "state ${player.stateFlow.value}")
            assertTrue(slope > 1.4, "the best wrap-free stretch after a replay storm at speed 2 had the slope $slope ms per ms")
        } finally {
            player.release()
        }
    }

    @Test
    fun aSpeedToggleStormBetweenOneAndTwoEndsWithTheLastAskedSpeedOnARealDecoder() {
        val player: VideoPlayer = newPlayer()
        try {
            prepare(player)
            player.setRepeatMode(RepeatMode.One)
            player.play()
            runBlocking { withTimeout(HANG_MS) { player.playbackPositionFlow.first { it > 300L } } }
            repeat(40) { index: Int ->
                player.setPlaybackSpeed(if (index % 2 == 0) 2f else 1f)
                // each ask waits for the playhead to move by flow value change, so the asks are spread over real playback
                val seen: Long = player.playbackPositionFlow.value
                runBlocking { withTimeout(HANG_MS) { player.playbackPositionFlow.first { it != seen } } }
            }
            player.setPlaybackSpeed(2f)
            // the speed is given after about 200 ms of playback and the wrap costs about a second: look for a stretch with the slope of 2
            var best = 0.0
            repeat(4) {
                best = maxOf(best, slopeOver(player, 600L))
            }
            assertTrue(best > 1.4, "the best slope of four 600 ms windows after the last ask of 2 was $best ms per ms")
        } finally {
            player.release()
        }
    }

    @Test
    fun aSpeedAskedAtTheEndOfTheClipDoesNotLeaveThePlayerStuckBeforeTheNextPlay() {
        val player: VideoPlayer = newPlayer()
        try {
            prepare(player)
            player.setPlaybackSpeed(4f)
            player.play()
            runBlocking { withTimeout(HANG_MS) { player.playbackPositionFlow.first { it > 1_800L } } }
            player.setPlaybackSpeed(1f)
            player.setPlaybackSpeed(3f)
            runBlocking { withTimeout(HANG_MS) { player.stateFlow.first { it is VideoPlayerState.Completed } } }
            player.setPlaybackSpeed(2f)
            player.play()
            runBlocking { withTimeout(HANG_MS) { player.playbackPositionFlow.first { it in 300L..2_000L } } }
            runBlocking { withTimeout(HANG_MS) { player.stateFlow.first { it is VideoPlayerState.Completed } } }
        } finally {
            player.release()
        }
    }

    @Test
    fun theGettingStartedSnippetPlaysAndTheGuideEngineChoiceSnippetPicksTheEngine() {
        // 03-guide: fun createDesktopVideoPlayer(): VideoPlayer? = if (isJavaFxMediaAvailable()) createJavaFxVideoPlayer() else null
        val player: VideoPlayer = checkNotNull(if (isJavaFxMediaAvailable()) createJavaFxVideoPlayer() else null) { "JavaFX is available here" }
        val scope = CoroutineScope(Dispatchers.Default)
        try {
            // 02-getting-started: scope.launch { player.prepare(VideoSource.Asset(...)); player.play() }
            scope.launch {
                player.prepare(VideoSource.Asset(TestClip.ASSET))
                player.play()
            }
            runBlocking { withTimeout(HANG_MS) { player.playbackPositionFlow.first { it > 300L } } }
            assertTrue(player.stateFlow.value is VideoPlayerState.Playing, "state ${player.stateFlow.value}")
        } finally {
            scope.cancel()
            player.release()
        }
    }

    @Test
    fun badRemoteSourcesArriveAsErrorsAndTheSamePlayerPlaysAfterwards() {
        val player: VideoPlayer = newPlayer()
        try {
            val causes: MutableMap<String, Throwable> = linkedMapOf()
            for (url: String in listOf("ftp://example.test/a.mp4", "http://127.0.0.1:1/a.mp4", "gopher:nothing")) {
                runBlocking { withTimeout(HANG_MS) { player.prepare(VideoSource.Remote(url)) } }
                val state: VideoPlayerState = player.stateFlow.value
                assertTrue(state is VideoPlayerState.Error, "$url settled on $state")
                causes[url] = (state as VideoPlayerState.Error).cause
            }
            // guide, "Errors you can see": a connection refused is MediaException with type MEDIA_INACCESSIBLE
            val refused: Throwable = causes.getValue("http://127.0.0.1:1/a.mp4")
            assertTrue(refused is javafx.scene.media.MediaException, "a refused connection was $refused")
            assertTrue(
                (refused as javafx.scene.media.MediaException).type == javafx.scene.media.MediaException.Type.MEDIA_INACCESSIBLE,
                "a refused connection has type ${refused.type}",
            )
            // guide, "Sources on desktop": a `File` that names a directory is a FileNotFoundException; an `Asset` that names a directory of
            // the class path (or `/`) is a MediaException of JavaFX's own (an empty or unrecognised file signature)
            runBlocking { withTimeout(HANG_MS) { player.prepare(VideoSource.File(java.io.File("build").absoluteFile.path)) } }
            val directory: Throwable = (player.stateFlow.value as VideoPlayerState.Error).cause
            assertTrue(directory is java.io.FileNotFoundException, "a directory as a File was $directory")
            runBlocking { withTimeout(HANG_MS) { player.prepare(VideoSource.Asset("/")) } }
            val root: VideoPlayerState = player.stateFlow.value
            assertTrue(root is VideoPlayerState.Error && root.cause is javafx.scene.media.MediaException, "Asset(\"/\") settled on $root")
            prepare(player)
            player.play()
            runBlocking { withTimeout(HANG_MS) { player.playbackPositionFlow.first { it > 300L } } }
        } finally {
            player.release()
        }
    }

    // Not committed: the documented MediaException for an unknown protocol (r15-D1, a documentation inexactness) is in the report.

    private companion object {
        const val NANOS_PER_MILLI: Long = 1_000_000L
        const val HANG_MS: Long = 30_000L
        const val WRAP_DROP_MS: Long = 1_000L
        const val STORM_OPS: Int = 150
        const val TRIALS: Int = 2
        const val SEED_BASE: Int = 15_500
    }
}
