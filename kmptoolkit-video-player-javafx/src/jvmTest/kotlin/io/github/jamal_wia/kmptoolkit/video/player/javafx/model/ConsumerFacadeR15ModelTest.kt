package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerReleasedException
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoPlayerException
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import java.io.FileNotFoundException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
 * D2 round 15, character THE CONSUMER'S SIDE: everything here goes through the shared [VideoPlayer] (the core facade), never
 * through the engine, over the real `JavaFxVideoEngine` and `FxPlayback` on the composed, watched scripted player (virtual
 * time, no media), so a violated fragile window fails the test as in every model class.
 *
 * - Q1/Q2/Q3, the script: one consumer script with the documented expected trace (the core guide and API reference, "Settings",
 *   "Transport", "Lifecycle"; the javafx 04 "Sources" table); the same table, with the same literal, runs over the vlcj engine on its
 *   fake runtime (`VlcjConsumerFacadeR15AdversarialTest`) and over the fake engine (`ConsumerScriptDifferentialD2R15Test` in the
 *   testing module), so a divergence is a red test in one of the three.
 * - Q2 misuse: calls after `release()` reach no native player; a speed outside the range reaches the decoder clamped; a
 *   release from a state collector; every bad source of the sources table arrives as `Error` and the player prepares again.
 * - Q4, the holder machinery through the public API only: seeded random sequences whose oracle is "the last speed asked wins"
 *   (the explorer's oracle, read here off `playbackSpeedFlow` and the decoder's rate), the end of a clip played over and over
 *   at a speed, and a looping clip at a speed over many wraps.
 */
class ConsumerFacadeR15ModelTest {

    private class Rig(val engine: JavaFxVideoEngine, val backend: ComposedBackend, val player: VideoPlayer) {
        val composed: ComposedPlayer get() = backend.last
        val native: ScriptedNativePlayer get() = composed.player
        val time: TimeModel get() = composed.time
    }

    private fun FxModelScope.withPlayer(
        config: VideoPlayerConfig = VideoPlayerConfig(positionUpdateIntervalMs = ONE_HOUR_MS),
        clip: ClipModel = ClipModel(),
        prepared: Boolean = true,
        body: Rig.() -> Unit,
    ) {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host, clip = clip))
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        val player: VideoPlayer = videoPlayer(engine, config)
        try {
            if (prepared) {
                prepare(player, VideoSource.Asset(TestClip.ASSET))
                advanceBy(0)
                assertTrue(player.stateFlow.value is VideoPlayerState.Ready, "state ${player.stateFlow.value}")
            }
            Rig(engine, backend, player).body()
        } finally {
            player.release()
            advanceBy(50)
        }
    }

    /** `player.prepare` driven the way `FxModelScope.load` drives an engine load; returns the state it settled on. */
    private fun FxModelScope.prepare(player: VideoPlayer, source: VideoSource): VideoPlayerState {
        // timing-hygiene-ignore: TH2 bound the real-time hang bound of a prepare whose IO hop is real
        val deadline: Long = System.nanoTime() + PREPARE_BOUND_MS * NANOS_PER_MILLI
        runBlocking {
            val preparing = async { player.prepare(source) }
            while (!preparing.isCompleted) {
                host.runUntilIdle()
                yield()
                // timing-hygiene-ignore: TH2 bound the real-time hang bound of a prepare whose IO hop is real
                if (!preparing.isCompleted && System.nanoTime() - deadline > 0L) {
                    preparing.cancel()
                    throw AssertionError("prepare did not finish within $PREPARE_BOUND_MS ms of real time ($host)")
                }
                Thread.onSpinWait()
            }
            preparing.await()
        }
        return player.stateFlow.value
    }

    private fun VideoPlayer.line(label: String): String {
        val state: String = when (val s: VideoPlayerState = stateFlow.value) {
            VideoPlayerState.Idle -> "Idle"
            VideoPlayerState.Preparing -> "Preparing"
            is VideoPlayerState.Ready -> "Ready(${s.duration})"
            is VideoPlayerState.Playing -> "Playing(${s.duration})"
            is VideoPlayerState.Paused -> "Paused(${s.duration},${s.currentPosition})"
            is VideoPlayerState.Completed -> "Completed(${s.duration})"
            is VideoPlayerState.Error -> "Error(${s.cause::class.simpleName})"
        }
        return "$label | $state pos=${playbackPositionFlow.value} speed=${playbackSpeedFlow.value} vol=${volumeFlow.value} " +
            "muted=${isMutedFlow.value} repeat=${repeatModeFlow.value}"
    }

    // ---- Q1 / Q2 / Q3: the consumer script ----

    @Test
    fun theConsumerScriptGivesTheDocumentedTraceOnTheJavaFxEngine() = fxModel {
        withPlayer(prepared = false) {
            val trace: MutableList<String> = mutableListOf()
            fun note(label: String) {
                trace += player.line(label)
            }
            note("initial")
            player.play(); player.pause(); player.stop(); player.seekTo(500L); player.seekForward(1_000L)
            player.seekBackward(1_000L); player.replay()
            note("transport before prepare is ignored")
            player.setPlaybackSpeed(2f); player.setVolume(0.5f); player.setMuted(true); player.setRepeatMode(RepeatMode.One)
            note("settings before prepare show at once")
            prepare(player, VideoSource.Asset(TestClip.ASSET))
            advanceBy(0)
            note("prepared")
            player.setPlaybackSpeed(Float.NaN); note("speed NaN is ignored")
            player.setPlaybackSpeed(100f); note("speed 100")
            player.setPlaybackSpeed(0f); note("speed 0")
            player.setPlaybackSpeed(-5f); note("speed -5")
            player.setPlaybackSpeed(Float.POSITIVE_INFINITY); note("speed +Inf")
            player.setPlaybackSpeed(Float.NEGATIVE_INFINITY); note("speed -Inf")
            player.setPlaybackSpeed(1f); note("speed 1")
            player.setVolume(5f); note("volume 5")
            player.setVolume(-1f); note("volume -1")
            player.setVolume(Float.NaN); note("volume NaN is ignored")
            player.setVolume(0.5f); note("volume 0.5")
            player.seekTo(Long.MAX_VALUE); note("seek MAX in Ready")
            player.seekTo(-5L); note("seek -5 in Ready")
            player.seekTo(1_200L); note("seek 1200 in Ready")
            player.play(); advanceBy(0); note("play")
            player.pause(); advanceBy(0); note("pause")
            player.seekTo(1_000L); note("seek 1000 paused")
            player.seekForward(10_000L); note("seekForward 10000 paused")
            player.seekBackward(Long.MAX_VALUE); note("seekBackward MAX paused")
            player.setRepeatMode(RepeatMode.Off)
            player.play(); advanceBy(5_000L); note("play to the end with repeat off")
            player.pause(); note("pause in Completed")
            player.seekTo(1_000L); note("seek 1000 in Completed")
            player.play(); advanceBy(5_000L); note("play to the end again")
            player.stop(); note("stop")
            player.replay(); note("replay")
            advanceBy(5_000L); note("replay to the end")
            player.play(); note("play from Completed")
            player.unload(); note("unload keeps the settings")
            prepare(player, VideoSource.Asset(TestClip.ASSET)); advanceBy(0); note("prepare again")
            player.release(); advanceBy(50L); note("release")
            player.release(); note("release twice")
            player.play(); player.seekTo(10L); note("transport after release is ignored")
            prepare(player, VideoSource.Asset(TestClip.ASSET)); note("prepare after release")
            player.setVolume(0.3f); note("volume after release shows at once")

            // The one documented divergence of the three engines (05-platform-notes, "Seeking": JavaFX snaps to the key frame at or
            // before the target and "may land a little before the requested position"; the model's key frames are 1000 ms apart):
            // a seek to 1200 from Ready, played and paused at once, reads 1000 on this engine and 1200 on the other two.
            val expected: List<String> = EXPECTED_SCRIPT.map { line: String ->
                if (line.startsWith("pause | ")) line.replace("Paused(3000,1200) pos=1200", "Paused(3000,1000) pos=1000") else line
            }
            assertEquals(expected, trace, "the consumer script, trace per documented sentence")
        }
    }

    // ---- Q2: misuse ----

    @Test
    fun aConsumerCallingAfterReleaseReachesNoNativePlayerAndAPrepareSettlesOnTheReleasedError() = fxModel {
        withPlayer {
            val native: ScriptedNativePlayer = native
            player.setPlaybackSpeed(2f)
            player.play()
            advanceBy(500)
            player.release()
            advanceBy(100)
            val before: Int = native.log.size
            val playerCount: Int = backend.players.size
            player.play(); player.pause(); player.stop(); player.seekTo(1L); player.seekForward(); player.seekBackward()
            player.replay(); player.setPlaybackSpeed(3f); player.setVolume(0.1f); player.setMuted(true)
            player.setRepeatMode(RepeatMode.One); player.unload(); player.release()
            advanceBy(2_000)
            assertEquals(before, native.log.size, "a native call arrived after release: ${native.log.tail(8)}")
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value, "unload after release leaves Idle")
            val settled: VideoPlayerState = prepare(player, VideoSource.Asset(TestClip.ASSET))
            assertIs<VideoPlayerState.Error>(settled)
            assertIs<VideoPlayerReleasedException>(settled.cause)
            assertEquals(playerCount, backend.players.size, "a prepare after release built another native player")
            assertEquals(3f, player.playbackSpeedFlow.value, "settings still show at once after release (documented)")
        }
    }

    @Test
    fun aSpeedOutsideTheNativeRangeButInsideTheConfiguredRangeReachesTheDecoderUnchanged() = fxModel {
        // 05-platform-notes: "Any other speed is handed to JavaFX unchanged" and "give the player a playbackSpeedRange within 8x"
        withPlayer(
            clip = ClipModel(durationMs = 600_000.0),
            config = VideoPlayerConfig(positionUpdateIntervalMs = ONE_HOUR_MS, minPlaybackSpeed = 0.1f, maxPlaybackSpeed = 100f),
        ) {
            player.play()
            advanceBy(1_000)
            player.setPlaybackSpeed(100f)
            advanceBy(2_500)
            assertEquals(100f, player.playbackSpeedFlow.value)
            assertTrue(100.0 in native.log.rates(), "the decoder never got 100: rates ${native.log.rates()}")
            player.setPlaybackSpeed(1_000f)
            assertEquals(100f, player.playbackSpeedFlow.value, "clamped into the configured range")
            player.setPlaybackSpeed(0.1f)
            advanceBy(2_500)
            assertTrue(0.1f.toDouble() in native.log.rates(), "the decoder never got 0.1: rates ${native.log.rates()}")
        }
    }

    @Test
    fun aStateCollectorThatReleasesThePlayerOnTheFirstPlayingStateEndsWithOneDisposeAndNoLaterNativeCall() = fxModel {
        val backend: ComposedBackend = composeAll(ScriptedBackend(host))
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        val player: VideoPlayer = videoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = ONE_HOUR_MS))
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            prepare(player, VideoSource.Asset(TestClip.ASSET))
            advanceBy(0)
            var released = 0
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                player.stateFlow.collect { state: VideoPlayerState ->
                    if (state is VideoPlayerState.Playing) {
                        released++
                        player.release()
                    }
                }
            }
            player.setPlaybackSpeed(2f)
            player.play()
            advanceBy(2_000)
            assertEquals(1, released, "the collector saw Playing once")
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
            val disposes: Int = backend.last.player.log.calls(NativeCall.DISPOSE).size
            assertEquals(1, disposes, "one native dispose: ${backend.last.player.log.tail(10)}")
            val total: Int = backend.last.player.log.size
            player.play(); player.setPlaybackSpeed(3f); player.release()
            advanceBy(1_000)
            assertEquals(total, backend.last.player.log.size, "native call after release")
        } finally {
            scope.cancel()
            advanceBy(50)
        }
    }

    @Test
    fun everyBadSourceOfTheSourcesTableArrivesAsAnErrorAndThePlayerPreparesAgainWithItsSettingsKept() = fxModel {
        withPlayer(prepared = false) {
            player.setPlaybackSpeed(2f)
            player.setVolume(0.4f)
            val bad: List<Pair<VideoSource, Class<out Throwable>>> = listOf(
                VideoSource.File("") to IllegalArgumentException::class.java,
                VideoSource.Asset("   ") to IllegalArgumentException::class.java,
                VideoSource.Remote("") to IllegalArgumentException::class.java,
                VideoSource.File(java.io.File(java.io.File("build").absoluteFile, "r15-no-such-file-é中.mp4").path) to FileNotFoundException::class.java,
                VideoSource.Asset("r15/no-such-asset.mp4") to FileNotFoundException::class.java,
                VideoSource.Remote("https://example.test/a.mp4", mapOf("Authorization" to "secret")) to JavaFxVideoPlayerException.HeadersNotSupported::class.java,
            )
            for ((source: VideoSource, expected: Class<out Throwable>) in bad) {
                val settled: VideoPlayerState = prepare(player, source)
                assertIs<VideoPlayerState.Error>(settled, "source $source settled on $settled")
                assertTrue(expected.isInstance(settled.cause), "source $source: ${settled.cause} is not a ${expected.simpleName}")
                player.play(); player.seekTo(5L)
                assertTrue(player.stateFlow.value is VideoPlayerState.Error, "a transport call woke a failed player: ${player.stateFlow.value}")
            }
            val headerCause: Throwable = (prepare(player, bad.last().first) as VideoPlayerState.Error).cause
            assertTrue("secret" !in headerCause.toString() && "secret" !in (headerCause.message ?: ""), "header value leaked: $headerCause")
            assertEquals(setOf("Authorization"), (headerCause as JavaFxVideoPlayerException.HeadersNotSupported).headerNames)
            assertEquals(0, backend.players.size, "a rejected source built a native player")
            val ok: VideoPlayerState = prepare(player, VideoSource.Asset(TestClip.ASSET))
            advanceBy(0)
            assertIs<VideoPlayerState.Ready>(ok)
            assertEquals(2f, player.playbackSpeedFlow.value)
            assertEquals(0.4f, player.volumeFlow.value)
        }
    }

    // ---- Q4: the holder machinery through the public API ----

    @Test
    fun theEndOfAClipPlayedOverAndOverAtASpeedAlwaysRestartsFromZeroAtThatSpeed() = fxModel {
        withPlayer(config = VideoPlayerConfig(positionUpdateIntervalMs = ONE_HOUR_MS, maxPlaybackSpeed = 4f)) {
            player.setPlaybackSpeed(2f)
            repeat(4) { round: Int ->
                player.play()
                advanceBy(300)
                assertTrue(player.stateFlow.value is VideoPlayerState.Playing, "round $round: ${player.stateFlow.value}")
                val early: Long = engine.positionMs()
                assertTrue(early < 1_500L, "round $round: the playhead $early ms after a play from the end did not restart")
                advanceBy(3_000)
                assertTrue(player.stateFlow.value is VideoPlayerState.Completed, "round $round: ${player.stateFlow.value}")
                assertEquals(2f, player.playbackSpeedFlow.value, "round $round: the asked speed survives the end")
            }
            // a resume after the end at the asked speed reaches the decoder at that speed
            player.setPlaybackSpeed(3f)
            player.play()
            advanceBy(2_000)
            assertEquals(3.0, time.effectiveRate, "after the end and a new speed the decoder runs at ${time.effectiveRate}")
        }
    }

    @Test
    fun aLoopingClipAtAskedSpeedsKeepsTheLastAskedSpeedAcrossManyWraps() = fxModel {
        withPlayer(clip = ClipModel(durationMs = 12_000.0), config = VideoPlayerConfig(positionUpdateIntervalMs = ONE_HOUR_MS, maxPlaybackSpeed = 4f)) {
            player.setRepeatMode(RepeatMode.One)
            player.setPlaybackSpeed(2f)
            player.play()
            for (asked: Float in listOf(2f, 4f, 3f, 1f, 2f)) {
                player.setPlaybackSpeed(asked)
                var guard = 0
                // a sample mid-cycle: more than 2400 ms of media past the wrap, where the speed must be back
                while (!(engine.positionMs() in 5_000L..9_000L) && guard++ < 800) advanceBy(25)
                assertTrue(guard < 800, "the playhead never reached mid-cycle at speed $asked (at ${engine.positionMs()})")
                assertEquals(asked.toDouble(), time.effectiveRate, "speed $asked asked, decoder at ${time.effectiveRate} mid-cycle at ${engine.positionMs()}")
                assertTrue(player.stateFlow.value is VideoPlayerState.Playing, "a looping clip left Playing: ${player.stateFlow.value}")
                // let the wrap pass
                guard = 0
                while (engine.positionMs() > 1_000L && guard++ < 800) advanceBy(25)
                while (engine.positionMs() <= 1_000L && guard++ < 1_600) advanceBy(25)
            }
        }
    }

    @Test
    fun theLastAskedSpeedWinsAfterSeededRandomPublicCallSequences() {
        val failures: MutableList<String> = mutableListOf()
        for (seed: Int in 0 until SEQUENCES) fxModel {
            val rng = Random(SEED_BASE + seed)
            val log: MutableList<String> = mutableListOf()
            val backend: ComposedBackend = composeAll(ScriptedBackend(host, clip = ClipModel(durationMs = 600_000.0)))
            val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
            val player: VideoPlayer = videoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = ONE_HOUR_MS, maxPlaybackSpeed = 4f))
            prepare(player, VideoSource.Asset(TestClip.ASSET))
            advanceBy(0)
            val speeds: List<Float> = listOf(0.5f, 1f, 1.5f, 2f, 3f, 4f, 2f, 1f)
            val gaps: List<Long> = listOf(0L, 0L, 1L, 5L, 30L, 100L, 190L, 205L, 250L, 600L, 1_200L)
            repeat(OPS) {
                val op: Int = rng.nextInt(12)
                when (op) {
                    0, 1, 2 -> { player.play(); log += "play" }
                    3 -> { player.pause(); log += "pause" }
                    4 -> { player.stop(); log += "stop" }
                    5 -> { val t: Long = rng.nextLong(0L, 600_000L); player.seekTo(t); log += "seekTo($t)" }
                    6 -> { player.seekForward(1_000L); log += "seekForward" }
                    7 -> { player.seekBackward(1_000L); log += "seekBackward" }
                    8, 9 -> { val s: Float = speeds[rng.nextInt(speeds.size)]; player.setPlaybackSpeed(s); log += "speed($s)" }
                    10 -> { player.replay(); log += "replay" }
                    else -> { val s: Float = player.playbackSpeedFlow.value; player.setPlaybackSpeed(s); log += "speed(same $s)" }
                }
                val gap: Long = gaps[rng.nextInt(gaps.size)]
                advanceBy(gap)
                log += "+$gap"
            }
            // settle: a playing player must run at the last asked speed; any other state is resumed first
            if (player.stateFlow.value !is VideoPlayerState.Playing) {
                player.play(); log += "play(settle)"
            }
            advanceBy(2_500)
            val before: Long = engine.positionMs()
            advanceBy(600)
            val after: Long = engine.positionMs()
            val asked: Double = player.playbackSpeedFlow.value.toDouble()
            val native: TimeModel = backend.last.time
            val problem: String? = when {
                player.stateFlow.value !is VideoPlayerState.Playing -> "state ${player.stateFlow.value}"
                native.effectiveRate != asked -> "decoder at ${native.effectiveRate}, last asked $asked"
                !native.isRunning -> "decoder not running"
                native.isHeld -> "playhead held (frozen)"
                after <= before -> "position did not advance ($before then $after)"
                else -> null
            }
            if (problem != null) failures += "seed ${SEED_BASE + seed}: $problem after ${log.joinToString(" ")}"
            player.release()
            advanceBy(50)
        }
        assertTrue(failures.isEmpty(), "${failures.size} of $SEQUENCES sequences broke 'the last asked speed wins':\n" + failures.take(5).joinToString("\n"))
    }

    private companion object {
        const val ONE_HOUR_MS: Long = 3_600_000L
        const val NANOS_PER_MILLI: Long = 1_000_000L
        const val PREPARE_BOUND_MS: Long = 10_000L
        const val SEQUENCES: Int = 150
        const val OPS: Int = 14
        const val SEED_BASE: Int = 15_000

        val EXPECTED_SCRIPT: List<String> = listOf(
            "initial | Idle pos=0 speed=1.0 vol=1.0 muted=false repeat=Off",
            "transport before prepare is ignored | Idle pos=0 speed=1.0 vol=1.0 muted=false repeat=Off",
            "settings before prepare show at once | Idle pos=0 speed=2.0 vol=0.5 muted=true repeat=One",
            "prepared | Ready(3000) pos=0 speed=2.0 vol=0.5 muted=true repeat=One",
            "speed NaN is ignored | Ready(3000) pos=0 speed=2.0 vol=0.5 muted=true repeat=One",
            "speed 100 | Ready(3000) pos=0 speed=3.0 vol=0.5 muted=true repeat=One",
            "speed 0 | Ready(3000) pos=0 speed=0.25 vol=0.5 muted=true repeat=One",
            "speed -5 | Ready(3000) pos=0 speed=0.25 vol=0.5 muted=true repeat=One",
            "speed +Inf | Ready(3000) pos=0 speed=3.0 vol=0.5 muted=true repeat=One",
            "speed -Inf | Ready(3000) pos=0 speed=0.25 vol=0.5 muted=true repeat=One",
            "speed 1 | Ready(3000) pos=0 speed=1.0 vol=0.5 muted=true repeat=One",
            "volume 5 | Ready(3000) pos=0 speed=1.0 vol=1.0 muted=true repeat=One",
            "volume -1 | Ready(3000) pos=0 speed=1.0 vol=0.0 muted=true repeat=One",
            "volume NaN is ignored | Ready(3000) pos=0 speed=1.0 vol=0.0 muted=true repeat=One",
            "volume 0.5 | Ready(3000) pos=0 speed=1.0 vol=0.5 muted=true repeat=One",
            "seek MAX in Ready | Ready(3000) pos=3000 speed=1.0 vol=0.5 muted=true repeat=One",
            "seek -5 in Ready | Ready(3000) pos=0 speed=1.0 vol=0.5 muted=true repeat=One",
            "seek 1200 in Ready | Ready(3000) pos=1200 speed=1.0 vol=0.5 muted=true repeat=One",
            "play | Playing(3000) pos=1200 speed=1.0 vol=0.5 muted=true repeat=One",
            "pause | Paused(3000,1200) pos=1200 speed=1.0 vol=0.5 muted=true repeat=One",
            "seek 1000 paused | Paused(3000,1000) pos=1000 speed=1.0 vol=0.5 muted=true repeat=One",
            "seekForward 10000 paused | Paused(3000,3000) pos=3000 speed=1.0 vol=0.5 muted=true repeat=One",
            "seekBackward MAX paused | Paused(3000,0) pos=0 speed=1.0 vol=0.5 muted=true repeat=One",
            "play to the end with repeat off | Completed(3000) pos=3000 speed=1.0 vol=0.5 muted=true repeat=Off",
            "pause in Completed | Completed(3000) pos=3000 speed=1.0 vol=0.5 muted=true repeat=Off",
            "seek 1000 in Completed | Paused(3000,1000) pos=1000 speed=1.0 vol=0.5 muted=true repeat=Off",
            "play to the end again | Completed(3000) pos=3000 speed=1.0 vol=0.5 muted=true repeat=Off",
            "stop | Ready(3000) pos=0 speed=1.0 vol=0.5 muted=true repeat=Off",
            "replay | Playing(3000) pos=0 speed=1.0 vol=0.5 muted=true repeat=Off",
            "replay to the end | Completed(3000) pos=3000 speed=1.0 vol=0.5 muted=true repeat=Off",
            "play from Completed | Playing(3000) pos=0 speed=1.0 vol=0.5 muted=true repeat=Off",
            "unload keeps the settings | Idle pos=0 speed=1.0 vol=0.5 muted=true repeat=Off",
            "prepare again | Ready(3000) pos=0 speed=1.0 vol=0.5 muted=true repeat=Off",
            "release | Idle pos=0 speed=1.0 vol=0.5 muted=true repeat=Off",
            "release twice | Idle pos=0 speed=1.0 vol=0.5 muted=true repeat=Off",
            "transport after release is ignored | Idle pos=0 speed=1.0 vol=0.5 muted=true repeat=Off",
            "prepare after release | Error(VideoPlayerReleasedException) pos=0 speed=1.0 vol=0.5 muted=true repeat=Off",
            "volume after release shows at once | Error(VideoPlayerReleasedException) pos=0 speed=1.0 vol=0.3 muted=true repeat=Off",
        )
    }
}
