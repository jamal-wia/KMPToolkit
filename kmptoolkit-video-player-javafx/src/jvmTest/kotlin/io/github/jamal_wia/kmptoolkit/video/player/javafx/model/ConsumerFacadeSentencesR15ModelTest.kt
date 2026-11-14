package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.javafx.JavaFxVideoEngine
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus
import io.github.jamal_wia.kmptoolkit.video.player.javafx.TestClip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield

/**
 * D2 round 15, Q1: sentences of `docs/kmptoolkit-video-player-javafx/` (03-guide "Sources on desktop", "Lifecycle", 05-platform-notes
 * "Behaviour notes") and of the core API reference that a consumer reads as promises, each derived from the sentence and run through
 * the shared `VideoPlayer` over the real engine on the scripted player (virtual time). Sentence per test:
 * - "Unloading frees the native JavaFX player ... the next source needs a new one" (guide, Lifecycle);
 * - "A `pause()` followed by `play()` is not a seek: the clip resumes from exactly where it was paused, at any speed" (05);
 * - "Looping uses `cycleCount = INDEFINITE` ... `Completed` is never reported while it is on" (05) and "a player already `Completed`
 *   when `One` is set stays there until the next `play()`" (core API reference);
 * - "a seek from `Completed` stays `Paused` - in the state and in JavaFX - until `play()`" (05, End of media);
 * - "A seek still queued behind a newer seek is skipped, because the newer one decides the position" (05, Threading);
 * - "Buffering is reported from JavaFX's `STALLED` status" (05) and the picture size sentence ("Media.width / height");
 * - the sources table of the guide: a leading `/` of an asset is optional, a remote URL is passed "as is", `headers` empty is no
 *   header, a blank URL is an `IllegalArgumentException`.
 */
class ConsumerFacadeSentencesR15ModelTest {

    private class Rig(val scripted: ScriptedBackend, val backend: ComposedBackend, val engine: JavaFxVideoEngine, val player: VideoPlayer) {
        val composed: ComposedPlayer get() = backend.last
        val native: ScriptedNativePlayer get() = composed.player
        val time: TimeModel get() = composed.time
    }

    private fun FxModelScope.withPlayer(
        clip: ClipModel = ClipModel(),
        config: VideoPlayerConfig = VideoPlayerConfig(positionUpdateIntervalMs = ONE_HOUR_MS),
        source: VideoSource? = VideoSource.Asset(TestClip.ASSET),
        body: Rig.() -> Unit,
    ) {
        val scripted = ScriptedBackend(host, clip = clip)
        val backend: ComposedBackend = composeAll(scripted)
        val engine = JavaFxVideoEngine(runtime = {}, backend = { backend })
        val player: VideoPlayer = videoPlayer(engine, config)
        try {
            if (source != null) {
                prepare(player, source)
                advanceBy(0)
            }
            Rig(scripted, backend, engine, player).body()
        } finally {
            player.release()
            advanceBy(50)
        }
    }

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

    @Test
    fun unloadingFreesTheNativePlayerOnceAndTheNextSourceGetsANewOne() = fxModel {
        withPlayer {
            player.play()
            advanceBy(500)
            player.unload()
            advanceBy(100)
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
            assertEquals(1, native.log.calls(NativeCall.DISPOSE).size, "unload disposes the native player once: ${native.log.tail(6)}")
            assertEquals(1, backend.players.size)
            val settled: VideoPlayerState = prepare(player, VideoSource.Asset(TestClip.ASSET))
            advanceBy(0)
            assertIs<VideoPlayerState.Ready>(settled)
            assertEquals(2, backend.players.size, "the next source needs a new native player")
            assertEquals(1, backend.players.first().player.log.calls(NativeCall.DISPOSE).size, "the old one stays disposed once")
            assertEquals(0, native.log.calls(NativeCall.DISPOSE).size, "the new one is alive")
        }
    }

    @Test
    fun aPauseFollowedByPlayIsNotASeekAtAnySpeed() {
        for (speed: Float in listOf(1f, 2f, 3f)) fxModel {
            withPlayer(clip = ClipModel(durationMs = 600_000.0)) {
                player.setPlaybackSpeed(speed)
                player.play()
                advanceBy(2_500)
                player.pause()
                advanceBy(50)
                val pausedAt: Long = engine.positionMs()
                assertTrue(pausedAt > 500L, "speed $speed: the playhead was at $pausedAt")
                val seeksBefore: Int = native.log.calls(NativeCall.SEEK).size
                advanceBy(1_000)
                player.play()
                advanceBy(60)
                val early: Long = engine.positionMs()
                advanceBy(3_000)
                assertEquals(seeksBefore, native.log.calls(NativeCall.SEEK).size, "speed $speed: a pause then play issued a native seek: ${native.log.tail(8)}")
                assertTrue(early in (pausedAt - 50L)..(pausedAt + 600L), "speed $speed: the clip resumed at $early, paused at $pausedAt")
                assertEquals(speed.toDouble(), time.effectiveRate, "speed $speed reaches the decoder after the resume")
            }
        }
    }

    @Test
    fun loopingIsTheNativeIndefiniteCycleCountAndACompletedPlayerStaysCompletedWhenOneIsSet() = fxModel {
        withPlayer {
            player.play()
            advanceBy(4_000)
            assertEquals(VideoPlayerState.Completed(3_000L), player.stateFlow.value)
            val plays: Int = native.log.calls(NativeCall.PLAY).size
            player.setRepeatMode(RepeatMode.One)
            advanceBy(3_000)
            assertEquals(VideoPlayerState.Completed(3_000L), player.stateFlow.value, "a Completed player stays there when One is set")
            assertEquals(plays, native.log.calls(NativeCall.PLAY).size, "One restarted a completed source by itself")
            assertEquals(-1.0, native.log.calls(NativeCall.SET_CYCLE_COUNT).last().arg, "One is JavaFX's INDEFINITE")
            player.play()
            advanceBy(300)
            assertTrue(player.stateFlow.value is VideoPlayerState.Playing)
            assertTrue(engine.positionMs() < 1_500L, "play from Completed started over: ${engine.positionMs()}")
            repeat(20) {
                advanceBy(1_000)
                assertTrue(player.stateFlow.value is VideoPlayerState.Playing, "Completed was reported while looping: ${player.stateFlow.value}")
            }
            player.setRepeatMode(RepeatMode.Off)
            advanceBy(0)
            assertEquals(1.0, native.log.calls(NativeCall.SET_CYCLE_COUNT).last().arg, "Off is a single cycle")
            advanceBy(4_000)
            assertEquals(VideoPlayerState.Completed(3_000L), player.stateFlow.value, "a loop turned off ends at the end of its cycle")
        }
    }

    @Test
    fun aSeekFromCompletedStaysPausedInTheStateAndInTheNativePlayerUntilPlay() = fxModel {
        withPlayer {
            player.play()
            advanceBy(4_000)
            assertEquals(VideoPlayerState.Completed(3_000L), player.stateFlow.value)
            player.seekTo(1_000L)
            advanceBy(100)
            assertEquals(VideoPlayerState.Paused(3_000L, 1_000L), player.stateFlow.value)
            assertEquals(NativeStatus.PAUSED, native.status, "JavaFX's own status after a seek from Completed")
            val at: Long = engine.positionMs()
            advanceBy(3_000)
            assertEquals(NativeStatus.PAUSED, native.status, "the native player ran on by itself")
            assertEquals(at, engine.positionMs(), "the playhead moved while paused")
            player.play()
            advanceBy(500)
            assertEquals(NativeStatus.PLAYING, native.status)
            assertTrue(engine.positionMs() >= at, "play resumes from the seek target")
        }
    }

    @Test
    fun aSeekQueuedBehindANewerSeekIsSkipped() = fxModel {
        withPlayer(clip = ClipModel(durationMs = 600_000.0)) {
            player.play()
            advanceBy(500)
            val before: Int = native.log.calls(NativeCall.SEEK).size
            for (target: Long in 10_000L..19_000L step 1_000L) player.seekTo(target)
            advanceBy(50)
            val seeks: List<NativeCallEntry> = native.log.calls(NativeCall.SEEK).drop(before)
            assertEquals(1, seeks.size, "a burst of ten seeks queued behind the FX thread issued ${seeks.size} native seeks: $seeks")
            assertEquals(19_000.0, seeks.single().arg, "the newest seek decides the position")
            assertEquals(19_000L, player.playbackPositionFlow.value)
        }
    }

    @Test
    fun bufferingFollowsTheStalledStatusAndIsNotShownBeforeAPlayerIsLoaded() = fxModel {
        withPlayer {
            assertEquals(false, player.isBufferingFlow.value)
            player.play()
            advanceBy(300)
            native.forceStatus(NativeStatus.STALLED)
            advanceBy(0)
            assertEquals(true, player.isBufferingFlow.value, "STALLED is buffering")
            assertTrue(player.stateFlow.value is VideoPlayerState.Playing, "a stalled stream is still Playing")
            native.forceStatus(NativeStatus.PLAYING)
            advanceBy(0)
            assertEquals(false, player.isBufferingFlow.value, "PLAYING ends buffering")
            native.forceStatus(NativeStatus.STALLED)
            advanceBy(0)
            player.unload()
            advanceBy(50)
            assertEquals(false, player.isBufferingFlow.value, "unload resets buffering")
        }
    }

    @Test
    fun theVideoSizeIsWhatJavaFxReportsAndIsGoneAfterUnloadAndAFailedPrepare() = fxModel {
        withPlayer {
            assertEquals(VideoSize(64, 48), player.videoSizeFlow.value)
            player.unload()
            advanceBy(50)
            assertNull(player.videoSizeFlow.value)
            prepare(player, VideoSource.Asset(TestClip.ASSET))
            advanceBy(0)
            assertEquals(VideoSize(64, 48), player.videoSizeFlow.value)
            prepare(player, VideoSource.Remote("https://example.test/a.mp4", mapOf("X" to "y")))
            assertTrue(player.stateFlow.value is VideoPlayerState.Error)
            assertNull(player.videoSizeFlow.value, "a failed prepare leaves no size")
        }
    }

    @Test
    fun theSourcesTableAnAssetLeadingSlashIsOptionalARemoteUrlIsPassedAsIsAndEmptyHeadersAreNoHeaders() = fxModel {
        withPlayer(source = null) {
            assertIs<VideoPlayerState.Ready>(prepare(player, VideoSource.Asset("/${TestClip.ASSET}")))
            val withSlash: String = scripted.probed.last()
            assertIs<VideoPlayerState.Ready>(prepare(player, VideoSource.Asset(TestClip.ASSET)))
            assertEquals(withSlash, scripted.probed.last(), "a leading slash changes the resolved asset")
            val url = "https://example.test/dir/a%20b.mp4?token=x%2By&y=1#frag"
            assertIs<VideoPlayerState.Ready>(prepare(player, VideoSource.Remote(url)))
            assertEquals(url, scripted.probed.last(), "the URL is passed to JavaFX as is")
            assertIs<VideoPlayerState.Ready>(prepare(player, VideoSource.Remote(url, emptyMap())))
            assertEquals(url, scripted.probed.last())
            val blank: VideoPlayerState = prepare(player, VideoSource.Remote("   "))
            assertIs<VideoPlayerState.Error>(blank)
            assertIs<IllegalArgumentException>(blank.cause, "a blank URL is an IllegalArgumentException, was ${blank.cause}")
        }
    }


    @Test
    fun aPlayWithThePlayheadOnTheEndStartsOverFromZeroFromReadyAndFromPaused() = fxModel {
        // core API reference, play(): "with the playhead on the end of the source, it starts over from 0"
        withPlayer {
            player.seekTo(Long.MAX_VALUE)
            assertEquals(3_000L, player.playbackPositionFlow.value)
            player.play()
            advanceBy(300)
            assertTrue(player.stateFlow.value is VideoPlayerState.Playing, "state ${player.stateFlow.value}")
            assertTrue(engine.positionMs() < 1_500L, "play from a Ready player on the end read ${engine.positionMs()}")
            assertEquals(NativeStatus.PLAYING, native.status)
            player.pause()
            advanceBy(50)
            player.seekTo(Long.MAX_VALUE)
            advanceBy(50)
            assertEquals(VideoPlayerState.Paused(3_000L, 3_000L), player.stateFlow.value)
            player.play()
            advanceBy(300)
            assertTrue(player.stateFlow.value is VideoPlayerState.Playing, "state ${player.stateFlow.value}")
            assertTrue(engine.positionMs() < 1_500L, "play from a Paused player on the end read ${engine.positionMs()}")
        }
    }

    private companion object {
        const val ONE_HOUR_MS: Long = 3_600_000L
        const val NANOS_PER_MILLI: Long = 1_000_000L
        const val PREPARE_BOUND_MS: Long = 10_000L
    }
}
