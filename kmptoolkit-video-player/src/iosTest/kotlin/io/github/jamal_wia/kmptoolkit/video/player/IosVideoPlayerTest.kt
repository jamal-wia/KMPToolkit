package io.github.jamal_wia.kmptoolkit.video.player

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.currentItem
import platform.AVFoundation.rate
import platform.AVFoundation.volume
import platform.Foundation.NSBundle
import platform.Foundation.NSTemporaryDirectory

/**
 * The iOS [VideoPlayer] end to end: the real `createVideoPlayer()` factory, the shared state machine
 * and [AvPlayerVideoEngine] together, on a one-second H.264 movie written at test time. Derived from
 * the contract on [VideoPlayer] and from `docs/kmptoolkit-video-player/05-platform-notes.md` § iOS.
 *
 * The player runs with the factory's default coroutine context, as an app's would; only the
 * audio session is left alone, because it is process-wide.
 */
@OptIn(ToolkitInternalApi::class)
class IosVideoPlayerTest {

    private val movie: String by lazy { writeTestMovie("kmptoolkit-video-player-ios-test") }

    private fun newPlayer(
        config: VideoPlayerConfig = VideoPlayerConfig(positionUpdateIntervalMs = FAST_POSITION_UPDATES_MS),
    ): VideoPlayer = createVideoPlayer(config = config, managesAudioSession = false)

    private fun VideoPlayer.avPlayer(): AVPlayer = assertNotNull(avPlayerOrNull(), "no AVPlayer behind the player")

    // --- State walk ---------------------------------------------------------------------------

    @Test
    fun `prepare settles on Ready with the movie duration and picture size`() {
        val path: String = movie
        val player: VideoPlayer = newPlayer()
        runOnMainLoop {
            player.prepare(VideoSource.File(path))

            val ready: VideoPlayerState.Ready = assertIs(player.stateFlow.value)
            assertTrue(abs(ready.duration - MOVIE_DURATION_MS) <= DURATION_TOLERANCE_MS, "duration ${ready.duration}")
            assertEquals(0L, player.playbackPositionFlow.value)
            assertTrue(
                awaitCondition(2_000) { player.videoSizeFlow.value != null },
                "no picture size reported",
            )
            assertEquals(VideoSize(TEST_MOVIE_WIDTH, TEST_MOVIE_HEIGHT), player.videoSizeFlow.value)
            player.release()
        }
    }

    @Test
    fun `the state walks Ready Playing Paused Playing Completed`() {
        val path: String = movie
        val player: VideoPlayer = newPlayer()
        runOnMainLoop {
            player.prepare(VideoSource.File(path))

            player.play()
            assertIs<VideoPlayerState.Playing>(player.stateFlow.value)
            assertTrue(
                awaitCondition(1_500) { player.playbackPositionFlow.value >= 200L },
                "the playhead did not advance: ${player.stateFlow.value}",
            )

            player.pause()
            val paused: VideoPlayerState.Paused = assertIs(player.stateFlow.value)
            delay(300)
            assertEquals(paused, player.stateFlow.value, "a paused player moved on")
            assertEquals(0f, player.avPlayer().rate)
            assertTrue(
                player.bufferedPositionFlow.value >= paused.currentPosition,
                "buffered ${player.bufferedPositionFlow.value} behind the playhead ${paused.currentPosition}",
            )

            player.play()
            assertIs<VideoPlayerState.Playing>(player.stateFlow.value)
            assertTrue(
                awaitCondition(3_000) { player.stateFlow.value is VideoPlayerState.Completed },
                "never completed: ${player.stateFlow.value}",
            )
            val completed: VideoPlayerState.Completed = assertIs(player.stateFlow.value)
            assertEquals(completed.duration, player.playbackPositionFlow.value)
            assertEquals(false, player.isBufferingFlow.value)
            assertEquals(0f, player.avPlayer().rate, "AVPlayer still running at the end")

            // Completed is terminal until the app acts: nothing moves it on by itself.
            delay(500)
            assertEquals(completed, player.stateFlow.value)
            player.release()
        }
    }

    @Test
    fun `play from Completed starts over and completes again`() {
        val path: String = movie
        val player: VideoPlayer = newPlayer()
        runOnMainLoop {
            player.prepare(VideoSource.File(path))
            player.play()
            assertTrue(awaitCondition(3_000) { player.stateFlow.value is VideoPlayerState.Completed })

            player.play()

            val playing: VideoPlayerState.Playing = assertIs(player.stateFlow.value)
            assertTrue(playing.currentPosition < 300L, "did not start over: $playing")
            assertTrue(player.avPlayer().rate > 0f, "AVPlayer is not running")
            assertTrue(
                awaitCondition(3_000) { player.stateFlow.value is VideoPlayerState.Completed },
                "the second run never completed: ${player.stateFlow.value}",
            )
            player.release()
        }
    }

    @Test
    fun `seek after completion stays paused until play`() {
        val path: String = movie
        val player: VideoPlayer = newPlayer()
        runOnMainLoop {
            player.prepare(VideoSource.File(path))
            player.play()
            assertTrue(awaitCondition(3_000) { player.stateFlow.value is VideoPlayerState.Completed })
            val duration: Long = assertIs<VideoPlayerState.Completed>(player.stateFlow.value).duration

            player.seekTo(300L)

            assertEquals(VideoPlayerState.Paused(duration, 300L), player.stateFlow.value)
            assertEquals(300L, player.playbackPositionFlow.value)
            delay(500)
            assertEquals(VideoPlayerState.Paused(duration, 300L), player.stateFlow.value)
            assertEquals(0f, player.avPlayer().rate, "a seek from the end started AVPlayer")

            player.play()
            val playing: VideoPlayerState.Playing = assertIs(player.stateFlow.value)
            assertTrue(abs(playing.currentPosition - 300L) <= 100L, "did not resume at the seek target: $playing")
            player.release()
        }
    }

    @Test
    fun `seek while playing stays playing and lands on the target`() {
        val path: String = movie
        val player: VideoPlayer = newPlayer()
        runOnMainLoop {
            player.prepare(VideoSource.File(path))
            player.play()

            player.seekTo(600L)

            val playing: VideoPlayerState.Playing = assertIs(player.stateFlow.value)
            assertEquals(600L, playing.currentPosition)
            delay(150)
            val position: Long = player.playbackPositionFlow.value
            assertTrue(position in 600L..900L, "position after the seek $position")
            assertTrue(player.avPlayer().rate > 0f, "the seek stopped AVPlayer")
            player.release()
        }
    }

    @Test
    fun `stop returns to Ready at the start with the source still loaded`() {
        val path: String = movie
        val player: VideoPlayer = newPlayer()
        runOnMainLoop {
            player.prepare(VideoSource.File(path))
            player.play()
            assertTrue(awaitCondition(1_500) { player.playbackPositionFlow.value >= 200L })

            player.stop()

            assertIs<VideoPlayerState.Ready>(player.stateFlow.value)
            assertEquals(0L, player.playbackPositionFlow.value)
            assertEquals(0f, player.avPlayer().rate)
            assertNotNull(player.avPlayer().currentItem, "stop unloaded the source")
            delay(200)
            assertTrue(player.stateFlow.value is VideoPlayerState.Ready, "stop did not hold: ${player.stateFlow.value}")

            player.play()
            assertIs<VideoPlayerState.Playing>(player.stateFlow.value)
            player.release()
        }
    }

    // --- Settings -----------------------------------------------------------------------------

    @Test
    fun `mute sets the AVPlayer volume to zero and unmute restores the volume`() {
        val path: String = movie
        val player: VideoPlayer = newPlayer()
        runOnMainLoop {
            player.setVolume(0.4f)
            player.prepare(VideoSource.File(path))
            assertEquals(0.4f, player.avPlayer().volume)

            player.setMuted(true)
            assertEquals(0f, player.avPlayer().volume)
            assertEquals(0.4f, player.volumeFlow.value, "muting changed the volume setting")

            player.play()
            assertEquals(0f, player.avPlayer().volume, "play() unmuted the output")

            player.setMuted(false)
            assertEquals(0.4f, player.avPlayer().volume)
            player.release()
        }
    }

    @Test
    fun `a mute set before prepare is applied to the loaded source`() {
        val path: String = movie
        val player: VideoPlayer = newPlayer()
        runOnMainLoop {
            player.setMuted(true)

            player.prepare(VideoSource.File(path))

            assertEquals(0f, player.avPlayer().volume)
            player.release()
        }
    }

    @Test
    fun `mute survives unload and a new prepare`() {
        val path: String = movie
        val player: VideoPlayer = newPlayer()
        runOnMainLoop {
            player.prepare(VideoSource.File(path))
            player.setMuted(true)

            player.unload()
            player.prepare(VideoSource.File(path))

            assertEquals(true, player.isMutedFlow.value)
            assertEquals(0f, player.avPlayer().volume)
            player.release()
        }
    }

    @Test
    fun `RepeatMode One loops without ever reaching Completed`() {
        val path: String = movie
        val player: VideoPlayer = newPlayer()
        runOnMainLoop {
            player.setRepeatMode(RepeatMode.One)
            player.prepare(VideoSource.File(path))
            val seen: MutableList<VideoPlayerState> = mutableListOf()
            val collecting: Job = launch(start = CoroutineStart.UNDISPATCHED) { player.stateFlow.collect { seen += it } }

            player.play()
            val wrapped: Boolean = awaitPlayheadWrap(player, timeoutMs = 3_000)

            collecting.cancel()
            assertTrue(wrapped, "the playhead never went back to the start")
            assertTrue(seen.none { it is VideoPlayerState.Completed }, "Completed reached in RepeatMode.One: $seen")
            assertIs<VideoPlayerState.Playing>(player.stateFlow.value)
            assertTrue(player.avPlayer().rate > 0f, "AVPlayer stopped after the loop")
            player.release()
        }
    }

    @Test
    fun `RepeatMode One set while playing applies to the loaded source`() {
        val path: String = movie
        val player: VideoPlayer = newPlayer()
        runOnMainLoop {
            player.prepare(VideoSource.File(path))
            player.play()
            delay(100)

            player.setRepeatMode(RepeatMode.One)
            val wrapped: Boolean = awaitPlayheadWrap(player, timeoutMs = 3_000)

            assertTrue(wrapped, "the playhead never went back to the start: ${player.stateFlow.value}")
            assertIs<VideoPlayerState.Playing>(player.stateFlow.value)
            player.release()
        }
    }

    @Test
    fun `RepeatMode Off after One completes at the next end`() {
        val path: String = movie
        val player: VideoPlayer = newPlayer()
        runOnMainLoop {
            player.setRepeatMode(RepeatMode.One)
            player.prepare(VideoSource.File(path))
            player.play()

            player.setRepeatMode(RepeatMode.Off)

            assertTrue(
                awaitCondition(3_000) { player.stateFlow.value is VideoPlayerState.Completed },
                "never completed: ${player.stateFlow.value}",
            )
            player.release()
        }
    }

    @Test
    fun `a speed chosen before play does not start AVPlayer and applies at play`() {
        val path: String = movie
        val player: VideoPlayer = newPlayer()
        runOnMainLoop {
            player.prepare(VideoSource.File(path))

            player.setPlaybackSpeed(2f)
            assertEquals(0f, player.avPlayer().rate)
            assertIs<VideoPlayerState.Ready>(player.stateFlow.value)

            player.play()
            assertEquals(2f, player.avPlayer().rate)
            player.release()
        }
    }

    // --- The AVPlayer accessor ----------------------------------------------------------------

    @Test
    fun `avPlayerOrNull is one AVPlayer for the player's whole life`() {
        val path: String = movie
        val player: VideoPlayer = newPlayer()
        runOnMainLoop {
            // Obtainable before the first prepare, so a surface can attach early.
            val avPlayer: AVPlayer = player.avPlayer()
            assertNull(avPlayer.currentItem)

            player.prepare(VideoSource.File(path))
            assertSame(avPlayer, player.avPlayerOrNull())
            assertNotNull(avPlayer.currentItem)

            player.unload()
            assertSame(avPlayer, player.avPlayerOrNull())
            assertNull(avPlayer.currentItem, "unload left the item in the AVPlayer")

            player.prepare(VideoSource.File(path))
            assertSame(avPlayer, player.avPlayerOrNull())
            assertNotNull(avPlayer.currentItem)

            // The documented contract: release only empties it; the instance itself stays.
            player.release()
            assertSame(avPlayer, player.avPlayerOrNull())
            assertNull(avPlayer.currentItem, "release left the item in the AVPlayer")
        }
    }

    @Test
    fun `avPlayerOrNull is null for a player over another engine`() {
        val player: VideoPlayer = createVideoPlayer(engine = RecordingVideoPlaybackEngine())

        assertNull(player.avPlayerOrNull())
        player.release()
    }

    // --- Lifecycle ----------------------------------------------------------------------------

    @Test
    fun `release while playing settles on Idle and nothing changes afterwards`() {
        val path: String = movie
        val player: VideoPlayer = newPlayer()
        runOnMainLoop {
            player.prepare(VideoSource.File(path))
            player.play()
            delay(200)

            player.release()
            player.release()

            assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
            assertNull(player.avPlayer().currentItem)
            // Longer than the rest of the movie: its end would have been reported by now.
            delay(1_500)
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
            assertEquals(0L, player.playbackPositionFlow.value)
            assertNull(player.videoSizeFlow.value)

            player.prepare(VideoSource.File(path))
            val error: VideoPlayerState.Error = assertIs(player.stateFlow.value)
            assertIs<VideoPlayerReleasedException>(error.cause)
            assertNull(player.avPlayer().currentItem, "a prepare after release loaded something")
        }
    }

    @Test
    fun `a player is reusable after unload`() {
        val path: String = movie
        val player: VideoPlayer = newPlayer()
        runOnMainLoop {
            player.prepare(VideoSource.File(path))
            player.play()
            delay(200)

            player.unload()
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
            assertNull(player.videoSizeFlow.value)
            delay(1_200)
            assertEquals(VideoPlayerState.Idle, player.stateFlow.value, "the unloaded source still reported")

            player.prepare(VideoSource.File(path))
            assertIs<VideoPlayerState.Ready>(player.stateFlow.value)
            player.play()
            assertTrue(
                awaitCondition(3_000) { player.stateFlow.value is VideoPlayerState.Completed },
                "never completed after reuse: ${player.stateFlow.value}",
            )
            player.release()
        }
    }

    @Test
    fun `a failed prepare settles on Error and the next prepare works`() {
        val path: String = movie
        val player: VideoPlayer = newPlayer()
        runOnMainLoop {
            player.prepare(VideoSource.File("${NSTemporaryDirectory()}no-such-movie.mp4"))

            val error: VideoPlayerState.Error = assertIs(player.stateFlow.value)
            assertIs<IllegalStateException>(error.cause)
            assertNull(player.avPlayer().currentItem)

            player.prepare(VideoSource.File(path))
            assertIs<VideoPlayerState.Ready>(player.stateFlow.value)
            player.release()
        }
    }

    @Test
    fun `cancelling a prepare in flight settles on Idle with nothing loaded`() {
        val player: VideoPlayer = newPlayer()
        runOnMainLoop(timeoutSeconds = 10.0) {
            // A non-routable address: the load is certainly still in flight when it is cancelled.
            val preparing: Job = launch(start = CoroutineStart.UNDISPATCHED) {
                player.prepare(VideoSource.Remote("https://10.255.255.1/never.mp4"))
            }
            delay(300)
            assertEquals(VideoPlayerState.Preparing, player.stateFlow.value)

            preparing.cancel()
            preparing.join()

            assertEquals(VideoPlayerState.Idle, player.stateFlow.value)
            assertNull(player.avPlayer().currentItem)
            player.release()
        }
    }

    @Test
    fun `a newer prepare replaces one in flight`() {
        val path: String = movie
        val player: VideoPlayer = newPlayer()
        runOnMainLoop(timeoutSeconds = 10.0) {
            val first: Job = launch(start = CoroutineStart.UNDISPATCHED) {
                player.prepare(VideoSource.Remote("https://10.255.255.1/never.mp4"))
            }
            delay(300)

            player.prepare(VideoSource.File(path))
            first.join()

            val ready: VideoPlayerState.Ready = assertIs(player.stateFlow.value)
            assertTrue(abs(ready.duration - MOVIE_DURATION_MS) <= DURATION_TOLERANCE_MS, "duration ${ready.duration}")
            player.release()
        }
    }

    // --- Assets: bundle root first, then each subdirectory in order ----------------------------

    @Test
    fun `an asset in the bundle root wins over the same name in a subdirectory`() {
        val bundle: NSBundle = testBundle(
            "root-wins",
            mapOf("" to SHORT_MOVIE, "compose-resources" to LONG_MOVIE),
        )
        assertAssetDuration(bundle, listOf("compose-resources"), SHORT_MOVIE)
    }

    @Test
    fun `an asset missing from the root is found in the first listed subdirectory that has it`() {
        val bundle: NSBundle = testBundle(
            "first-subdirectory-wins",
            mapOf("first" to SHORT_MOVIE, "second" to LONG_MOVIE),
        )
        assertAssetDuration(bundle, listOf("empty", "first", "second"), SHORT_MOVIE)
        assertAssetDuration(bundle, listOf("second", "first"), LONG_MOVIE)
    }

    @Test
    fun `an asset only in an unlisted subdirectory is not found`() {
        val bundle: NSBundle = testBundle("unlisted-subdirectory", mapOf("compose-resources" to SHORT_MOVIE))
        val player: VideoPlayer = createVideoPlayer(assetBundle = bundle, managesAudioSession = false)
        runOnMainLoop {
            player.prepare(VideoSource.Asset(ASSET_NAME))

            val error: VideoPlayerState.Error = assertIs(player.stateFlow.value)
            assertIs<IllegalArgumentException>(error.cause)
            player.release()
        }
    }

    @Test
    fun `the extension is part of the asset lookup`() {
        val bundle: NSBundle = testBundle("extension-matters", mapOf("" to SHORT_MOVIE))
        val player: VideoPlayer = createVideoPlayer(assetBundle = bundle, managesAudioSession = false)
        runOnMainLoop {
            player.prepare(VideoSource.Asset("clip.mp4"))

            val error: VideoPlayerState.Error = assertIs(player.stateFlow.value)
            assertIs<IllegalArgumentException>(error.cause)
            player.release()
        }
    }

    private fun assertAssetDuration(bundle: NSBundle, subdirectories: List<String>, expected: TestClip) {
        val player: VideoPlayer = createVideoPlayer(
            assetBundle = bundle,
            assetSubdirectories = subdirectories,
            managesAudioSession = false,
        )
        runOnMainLoop {
            player.prepare(VideoSource.Asset(ASSET_NAME))

            val ready: VideoPlayerState.Ready = assertIs(player.stateFlow.value, "with $subdirectories")
            assertTrue(
                abs(ready.duration - expected.durationMs) <= DURATION_TOLERANCE_MS,
                "with $subdirectories loaded a ${ready.duration} ms clip, expected the ${expected.durationMs} ms one",
            )
            player.release()
        }
    }

    /** Suspends until the playhead goes back towards the start while playing; `false` on timeout. */
    private suspend fun awaitPlayheadWrap(player: VideoPlayer, timeoutMs: Long): Boolean {
        var maxPositionMs = 0L
        return awaitCondition(timeoutMs) {
            val positionMs: Long = player.playbackPositionFlow.value
            val wrapped: Boolean = positionMs + 300L < maxPositionMs
            maxPositionMs = maxOf(maxPositionMs, positionMs)
            wrapped
        }
    }

    private companion object {
        const val FAST_POSITION_UPDATES_MS: Long = 50L
        const val MOVIE_DURATION_MS: Long = 1_000L
        const val DURATION_TOLERANCE_MS: Long = 50L
        const val ASSET_NAME: String = "clip.mov"

        /** Two clips told apart by their length: which one loaded shows which file the lookup found. */
        val SHORT_MOVIE: TestClip = TestClip(name = "kmptoolkit-video-asset-short", frameCount = 15)
        val LONG_MOVIE: TestClip = TestClip(name = "kmptoolkit-video-asset-long", frameCount = 45)
    }
}
