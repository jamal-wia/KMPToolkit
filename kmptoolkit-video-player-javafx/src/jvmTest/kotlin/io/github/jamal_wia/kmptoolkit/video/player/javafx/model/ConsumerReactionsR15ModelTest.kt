package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * D2 round 15, Q2 and Q4: what a consumer's own code does around the player, over the JavaFX engine on the scripted player.
 *
 * - settings given before a load reach the native player of every NEW source (core API reference: "pushed to every newly loaded
 *   source"), and a muted player keeps its native volume at 0 whatever `setVolume` says until `setMuted(false)`;
 * - two prepares racing: the older one returns normally and exactly one native player survives (core: "A newer prepare replaces
 *   this one"); a cancelled prepare leaves no native player alive and the state `Idle`;
 * - the consumer pattern "when the clip completes, play again" written as a state collector: every completion is answered inside the
 *   collector, the player keeps its speed, and every round restarts from 0.
 */
class ConsumerReactionsR15ModelTest {

    private fun FxModelScope.prepare(player: VideoPlayer, vararg sources: VideoSource): VideoPlayerState {
        // timing-hygiene-ignore: TH2 bound the real-time hang bound of a prepare whose IO hop is real
        val deadline: Long = System.nanoTime() + BOUND_MS * NANOS_PER_MILLI
        runBlocking {
            val jobs = sources.map { async { player.prepare(it) } }
            while (!jobs.all { it.isCompleted }) {
                host.runUntilIdle()
                yield()
                // timing-hygiene-ignore: TH2 bound the real-time hang bound of a prepare whose IO hop is real
                if (!jobs.all { it.isCompleted } && System.nanoTime() - deadline > 0L) {
                    jobs.forEach { it.cancel() }
                    throw AssertionError("prepare did not finish within $BOUND_MS ms of real time ($host)")
                }
                Thread.onSpinWait()
            }
            jobs.forEach { it.await() }
        }
        return player.stateFlow.value
    }

    private fun FxModelScope.rig(
        clip: ClipModel = ClipModel(),
        body: (ComposedBackend, JavaFxVideoEngine, VideoPlayer) -> Unit,
    ) {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host, clip = clip))
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        val player: VideoPlayer = videoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = ONE_HOUR_MS))
        try {
            body(backend, engine, player)
        } finally {
            player.release()
            advanceBy(50)
        }
    }

    @Test
    fun settingsGivenBeforeALoadReachTheNativePlayerOfEveryNewSource() = fxModel {
        rig(clip = ClipModel(durationMs = 60_000.0)) { backend, _, player ->
            player.setVolume(0.3f)
            player.setRepeatMode(RepeatMode.One)
            player.setPlaybackSpeed(2f)
            prepare(player, VideoSource.Asset(TestClip.ASSET))
            advanceBy(50)
            val first: ScriptedNativePlayer = backend.last.player
            assertEquals(0.3f.toDouble(), first.volume, "volume of the first source")
            assertEquals(-1, first.cycleCount, "looping of the first source")
            prepare(player, VideoSource.Asset(TestClip.ASSET))
            advanceBy(50)
            val second: ScriptedNativePlayer = backend.last.player
            assertTrue(second !== first, "the second prepare reused the first native player")
            assertEquals(0.3f.toDouble(), second.volume, "volume of the second source")
            assertEquals(-1, second.cycleCount, "looping of the second source")
            player.play()
            advanceBy(1_500)
            assertEquals(2.0, backend.last.time.effectiveRate, "the speed reaches the second source's decoder")
        }
    }

    @Test
    fun aMutedPlayerKeepsItsNativeVolumeAtZeroWhateverSetVolumeSaysUntilUnmuted() = fxModel {
        rig { backend, _, player ->
            prepare(player, VideoSource.Asset(TestClip.ASSET))
            advanceBy(50)
            val native: ScriptedNativePlayer = backend.last.player
            player.setMuted(true)
            advanceBy(10)
            assertEquals(0.0, native.volume, "muted")
            for (asked: Float in listOf(0.7f, 5f, -1f, Float.NaN, 0.2f)) {
                player.setVolume(asked)
                advanceBy(10)
                assertEquals(0.0, native.volume, "volume $asked while muted reached the native player")
            }
            assertEquals(0.2f, player.volumeFlow.value)
            player.setMuted(false)
            advanceBy(10)
            assertEquals(0.2f.toDouble(), native.volume, "unmuting restores the last volume")
            player.setVolume(Float.NaN)
            player.setVolume(9f)
            advanceBy(10)
            assertEquals(1.0, native.volume, "an out-of-range volume is clamped before it reaches the native player")
        }
    }

    @Test
    fun twoPreparesRacingLeaveExactlyOneLiveNativePlayerAndTheOlderCallReturnsNormally() = fxModel {
        rig { backend, _, player ->
            val settled: VideoPlayerState = prepare(
                player,
                VideoSource.Asset(TestClip.ASSET),
                VideoSource.Asset("/${TestClip.ASSET}"),
                VideoSource.Asset(TestClip.ASSET),
            )
            advanceBy(100)
            assertTrue(settled is VideoPlayerState.Ready, "state $settled")
            val live: List<ScriptedNativePlayer> = backend.players.map { it.player }.filter { it.log.calls(NativeCall.DISPOSE).isEmpty() }
            assertEquals(1, live.size, "live native players after three racing prepares: ${backend.players.size} built, ${live.size} alive")
            for (built: ComposedPlayer in backend.players) {
                assertTrue(built.player.log.calls(NativeCall.DISPOSE).size <= 1, "a native player disposed twice")
            }
        }
    }

    @Test
    fun aCancelledPrepareLeavesNoLiveNativePlayerAndTheStateIdle() = fxModel {
        rig { backend, _, player ->
            runBlocking {
                val preparing = async { player.prepare(VideoSource.Asset(TestClip.ASSET)) }
                // let the load start on its IO hop, then cancel it before the FX queue is drained
                yield()
                preparing.cancel()
                // timing-hygiene-ignore: TH2 bound the real-time hang bound of a cancellation that crosses the IO hop
                val deadline: Long = System.nanoTime() + BOUND_MS * NANOS_PER_MILLI
                while (!preparing.isCompleted) {
                    host.runUntilIdle()
                    yield()
                    // timing-hygiene-ignore: TH2 bound the real-time hang bound of a cancellation that crosses the IO hop
                    check(System.nanoTime() - deadline < 0L) { "the cancelled prepare did not end ($host)" }
                }
            }
            advanceBy(200)
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
            val live: Int = backend.players.count { it.player.log.calls(NativeCall.DISPOSE).isEmpty() }
            assertEquals(0, live, "a cancelled prepare left $live live native player(s)")
        }
    }

    @Test
    fun aCollectorThatPlaysAgainOnEveryCompletionRestartsFromZeroAtTheSameSpeedEveryRound() = fxModel {
        rig(clip = ClipModel(durationMs = 3_000.0)) { backend, engine, player ->
            prepare(player, VideoSource.Asset(TestClip.ASSET))
            advanceBy(50)
            player.setPlaybackSpeed(2f)
            val scope = CoroutineScope(Dispatchers.Unconfined)
            var completions = 0
            val restarts: MutableList<Long> = mutableListOf()
            try {
                scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    player.stateFlow.collect { state: VideoPlayerState ->
                        if (state is VideoPlayerState.Completed) {
                            completions++
                            player.play()
                        }
                    }
                }
                player.play()
                var rounds = 0
                while (completions < 6 && rounds++ < 400) {
                    advanceBy(100)
                    if (completions in 1..6 && player.stateFlow.value is VideoPlayerState.Playing && engine.positionMs() in 0L..400L) {
                        if (restarts.size < completions) restarts += engine.positionMs()
                    }
                }
                assertTrue(completions >= 6, "only $completions completions in $rounds x 100 ms: state ${player.stateFlow.value}")
                assertTrue(player.stateFlow.value is VideoPlayerState.Playing || player.stateFlow.value is VideoPlayerState.Completed, "state ${player.stateFlow.value}")
                assertEquals(2f, player.playbackSpeedFlow.value)
                assertTrue(restarts.size >= 5, "a completion was not followed by a restart from the start: restarts $restarts")
                advanceBy(1_500)
                assertTrue(player.stateFlow.value is VideoPlayerState.Playing || player.stateFlow.value is VideoPlayerState.Completed, "state ${player.stateFlow.value}")
                if (player.stateFlow.value is VideoPlayerState.Playing) {
                    assertEquals(2.0, backend.last.time.effectiveRate, "the manual loop lost the speed")
                }
            } finally {
                scope.cancel()
            }
        }
    }


    @Test
    fun theLastAskedSpeedWinsAfterRandomPublicSequencesWithEndsWrapsAndRestarts() {
        val failures: MutableList<String> = mutableListOf()
        for (seed: Int in 0 until SEQUENCES) fxModel {
            val rng = kotlin.random.Random(SEED_BASE + seed)
            val log: MutableList<String> = mutableListOf()
            // a 12 s clip: at the speeds below it ends or wraps every 3 to 24 s, so the sequences cross ends, wraps and restarts
            rig(clip = ClipModel(durationMs = 12_000.0)) { backend, engine, player ->
                prepare(player, VideoSource.Asset(TestClip.ASSET))
                advanceBy(50)
                val speeds: List<Float> = listOf(0.5f, 1f, 1.5f, 2f, 3f, 4f)
                val gaps: List<Long> = listOf(0L, 0L, 1L, 5L, 40L, 120L, 190L, 205L, 300L, 700L, 1_500L, 2_500L)
                repeat(OPS) {
                    when (rng.nextInt(13)) {
                        0, 1, 2 -> { player.play(); log += "play" }
                        3 -> { player.pause(); log += "pause" }
                        4 -> { player.stop(); log += "stop" }
                        5 -> { val t: Long = rng.nextLong(0L, 12_000L); player.seekTo(t); log += "seekTo($t)" }
                        6 -> { player.seekForward(1_000L); log += "seekForward" }
                        7, 8 -> { val s: Float = speeds[rng.nextInt(speeds.size)]; player.setPlaybackSpeed(s); log += "speed($s)" }
                        9 -> { player.replay(); log += "replay" }
                        10 -> { player.setRepeatMode(RepeatMode.One); log += "loopOn" }
                        11 -> { player.setRepeatMode(RepeatMode.Off); log += "loopOff" }
                        else -> { player.setPlaybackSpeed(player.playbackSpeedFlow.value); log += "speed(same)" }
                    }
                    val gap: Long = gaps[rng.nextInt(gaps.size)]
                    advanceBy(gap)
                    log += "+$gap"
                }
                // settle: loop on, a playing player; whatever the sequence ended in, a play brings it back
                player.setRepeatMode(RepeatMode.One)
                if (player.stateFlow.value !is VideoPlayerState.Playing) player.play()
                log += "loopOn play(settle)"
                val asked: Double = player.playbackSpeedFlow.value.toDouble()
                var waited = 0L
                // mid-cycle sample: at least 1.6 s of wall time past any wrap (the speed returns by 1.2 s on a loaded machine)
                var sampleAt: Long = -1L
                while (waited < 40_000L) {
                    advanceBy(25)
                    waited += 25
                    val position: Long = engine.positionMs()
                    // the sample must come 1.6 s of wall time after the wrap: 1.6 s x the speed of media
                    if (waited > 2_000L && position >= (1_600.0 * asked).toLong() && position <= 11_000L) { sampleAt = position; break }
                }
                val native: TimeModel = backend.last.time
                val problem: String? = when {
                    sampleAt < 0L -> "no mid-cycle instant within 40 s (asked $asked): position ${engine.positionMs()} state ${player.stateFlow.value}"
                    player.stateFlow.value !is VideoPlayerState.Playing -> "state ${player.stateFlow.value}"
                    native.effectiveRate != asked -> "decoder at ${native.effectiveRate} at position $sampleAt, last asked $asked"
                    native.isHeld -> "playhead held (frozen)"
                    else -> null
                }
                if (problem != null) failures += "seed ${SEED_BASE + seed}: $problem after ${log.joinToString(" ")}"
            }
        }
        assertTrue(failures.isEmpty(), "${failures.size} of $SEQUENCES sequences broke 'the last asked speed wins':\n" + failures.take(5).joinToString("\n"))
    }

    private companion object {
        const val SEQUENCES: Int = 200
        const val OPS: Int = 24
        const val SEED_BASE: Int = 15_800
        const val ONE_HOUR_MS: Long = 3_600_000L
        const val BOUND_MS: Long = 10_000L
        const val NANOS_PER_MILLI: Long = 1_000_000L
    }
}
