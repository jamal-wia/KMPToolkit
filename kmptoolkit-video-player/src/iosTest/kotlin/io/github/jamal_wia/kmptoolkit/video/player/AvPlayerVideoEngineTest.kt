package io.github.jamal_wia.kmptoolkit.video.player

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.currentItem
import platform.AVFoundation.rate
import platform.AVFoundation.volume
import platform.Foundation.NSBundle
import platform.Foundation.NSTemporaryDirectory

/**
 * [AvPlayerVideoEngine] against the engine contract in `VideoPlaybackEngine`'s KDoc, driven
 * directly (the shared state machine above it is tested in common code).
 *
 * Real playback runs on a one-second H.264 movie written at test time with `AVAssetWriter`. What a
 * simulator cannot exercise — a real network, HLS, headers reaching a server, a stall — is covered
 * only through the pure helpers in [AvPlayerMappingTest].
 */
class AvPlayerVideoEngineTest {

    private val movie: String by lazy { writeTestMovie("kmptoolkit-video-engine-test") }

    private fun newEngine(): AvPlayerVideoEngine = AvPlayerVideoEngine(
        assetBundle = NSBundle.mainBundle,
        assetSubdirectories = emptyList(),
        // Tests never touch the process-wide audio session.
        managesAudioSession = false,
    )

    // --- Release ------------------------------------------------------------------------------

    @Test
    fun `release on a never loaded engine is a no op and idempotent`() {
        val engine: AvPlayerVideoEngine = newEngine()

        engine.release()
        engine.release()

        assertEquals(0L, engine.durationMs())
        assertEquals(0L, engine.positionMs())
        assertEquals(0L, engine.bufferedPositionMs())
    }

    @Test
    fun `transport calls before any load are ignored`() {
        val engine: AvPlayerVideoEngine = newEngine()

        engine.start()
        engine.pause()
        engine.seekTo(1_000L)
        engine.setSpeed(2f)
        engine.setVolume(0.5f)
        engine.setLooping(true)

        assertEquals(0L, engine.positionMs())
        assertNull(engine.player.currentItem)
    }

    @Test
    fun `release empties the player and zeroes the getters`() {
        val path: String = movie
        val engine: AvPlayerVideoEngine = newEngine()
        runOnMainLoop {
            engine.load(VideoSource.File(path))
            assertNotNull(engine.player.currentItem)

            engine.release()
            engine.release()

            assertNull(engine.player.currentItem)
            assertEquals(0L, engine.durationMs())
            assertEquals(0L, engine.positionMs())
            assertEquals(0L, engine.bufferedPositionMs())
        }
    }

    @Test
    fun `no listener call arrives after release even while playing to the end`() {
        val path: String = movie
        val engine: AvPlayerVideoEngine = newEngine()
        val listener = RecordingListener()
        engine.setListener(listener)
        runOnMainLoop {
            engine.load(VideoSource.File(path))
            engine.start()
            delay(100)
            engine.release()
            val callsAtRelease: Int = listener.calls.size

            // Longer than the movie: its end, and any monitor tick, would have been reported by now.
            delay(1_800)

            assertEquals(callsAtRelease, listener.calls.size, "calls after release: ${listener.calls}")
        }
    }

    @Test
    fun `a listener that releases the engine gets no further call from the same tick`() {
        val path: String = movie
        val engine: AvPlayerVideoEngine = newEngine()
        val listener = RecordingListener(onEvery = { engine.release() })
        engine.setListener(listener)
        runOnMainLoop {
            // The picture size is reported while loading, so the release may land inside load().
            runCatching { engine.load(VideoSource.File(path)) }
            engine.start()
            delay(1_800)

            assertEquals(1, listener.calls.size, "calls: ${listener.calls}")
        }
    }

    @Test
    fun `load after release works and keeps the same AVPlayer`() {
        val path: String = movie
        val engine: AvPlayerVideoEngine = newEngine()
        runOnMainLoop {
            val player: AVPlayer = engine.player
            engine.load(VideoSource.File(path))
            engine.release()

            engine.load(VideoSource.File(path))

            assertSame(player, engine.player)
            assertNotNull(engine.player.currentItem)
            assertTrue(engine.durationMs() > 0L)
            engine.release()
        }
    }

    @Test
    fun `a second load replaces the first item`() {
        val path: String = movie
        val engine: AvPlayerVideoEngine = newEngine()
        runOnMainLoop {
            engine.load(VideoSource.File(path))
            val first: AVPlayerItem? = engine.player.currentItem

            engine.load(VideoSource.File(path))

            assertNotNull(engine.player.currentItem)
            assertTrue(first != engine.player.currentItem, "the first item is still loaded")
            engine.release()
        }
    }

    // --- Load failures ------------------------------------------------------------------------

    @Test
    fun `a missing asset fails with IllegalArgumentException and loads nothing`() {
        val engine: AvPlayerVideoEngine = newEngine()
        runOnMainLoop {
            assertFailsWith<IllegalArgumentException> {
                engine.load(VideoSource.Asset("definitely-not-bundled.mp4"))
            }
            assertNull(engine.player.currentItem)
            assertEquals(0L, engine.durationMs())
        }
    }

    @Test
    fun `an empty asset path fails with IllegalArgumentException`() {
        val engine: AvPlayerVideoEngine = newEngine()
        runOnMainLoop {
            assertFailsWith<IllegalArgumentException> { engine.load(VideoSource.Asset("")) }
        }
    }

    @Test
    fun `an empty file path fails with IllegalArgumentException`() {
        val engine: AvPlayerVideoEngine = newEngine()
        runOnMainLoop {
            assertFailsWith<IllegalArgumentException> { engine.load(VideoSource.File("")) }
        }
    }

    @Test
    fun `a URL NSURL refuses fails with IllegalArgumentException`() {
        val engine: AvPlayerVideoEngine = newEngine()
        runOnMainLoop {
            assertFailsWith<IllegalArgumentException> { engine.load(VideoSource.Remote("")) }
            assertFailsWith<IllegalArgumentException> { engine.load(VideoSource.Remote("not a url")) }
            assertNull(engine.player.currentItem)
        }
    }

    @Test
    fun `a missing file fails with the AVPlayerItem error and loads nothing`() {
        val engine: AvPlayerVideoEngine = newEngine()
        val listener = RecordingListener()
        engine.setListener(listener)
        runOnMainLoop {
            val failure: IllegalStateException = assertFailsWith<IllegalStateException> {
                engine.load(VideoSource.File("${NSTemporaryDirectory()}no-such-movie.mp4"))
            }
            assertTrue(failure.message.orEmpty().startsWith("AVPlayerItem failed"), failure.message)
            assertNull(engine.player.currentItem)
            assertEquals(0L, engine.durationMs())
            delay(200)
            assertTrue(listener.calls.isEmpty(), "a load failure is thrown, not reported: ${listener.calls}")
        }
    }

    @Test
    fun `a file that is not media fails and loads nothing`() {
        val engine: AvPlayerVideoEngine = newEngine()
        runOnMainLoop {
            // The test binary itself: a file that exists but is no movie.
            val notMedia: String = NSBundle.mainBundle.executablePath ?: fail("no executable path")
            assertFailsWith<IllegalStateException> { engine.load(VideoSource.File(notMedia)) }
            assertNull(engine.player.currentItem)
        }
    }

    @Test
    fun `a failed load is followed by a working load`() {
        val path: String = movie
        val engine: AvPlayerVideoEngine = newEngine()
        runOnMainLoop {
            assertFailsWith<IllegalStateException> {
                engine.load(VideoSource.File("${NSTemporaryDirectory()}no-such-movie.mp4"))
            }
            engine.release()

            engine.load(VideoSource.File(path))

            assertTrue(engine.durationMs() > 0L)
            engine.release()
        }
    }

    // --- Cancellation -------------------------------------------------------------------------

    @Test
    fun `cancelling a load that is still waiting leaves nothing loaded`() {
        val engine: AvPlayerVideoEngine = newEngine()
        runOnMainLoop(timeoutSeconds = 10.0) {
            var thrown: Throwable? = null
            // A non-routable address: the connection neither succeeds nor fails for far longer than
            // this test waits, so the load is certainly still suspended when it is cancelled. The
            // header also proves AVURLAsset accepts the header option (what reaches the server
            // cannot be observed here).
            val loading: Job = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    engine.load(
                        VideoSource.Remote("https://10.255.255.1/never.mp4", mapOf("Authorization" to "Bearer t"))
                    )
                } catch (failure: Throwable) {
                    thrown = failure
                    throw failure
                }
            }
            delay(300)
            assertNotNull(engine.player.currentItem, "the load should be in flight")

            loading.cancel()
            loading.join()

            assertTrue(thrown is CancellationException, "load ended with $thrown instead of cancellation")
            assertNull(engine.player.currentItem)
            assertEquals(0L, engine.durationMs())
        }
    }

    @Test
    fun `release while a load is waiting makes the load fail`() {
        val engine: AvPlayerVideoEngine = newEngine()
        runOnMainLoop(timeoutSeconds = 10.0) {
            var thrown: Throwable? = null
            val loading: Job = launch(start = CoroutineStart.UNDISPATCHED) {
                thrown = runCatching { engine.load(VideoSource.Remote("https://10.255.255.1/never.mp4")) }
                    .exceptionOrNull()
            }
            delay(300)

            engine.release()
            loading.join()

            assertTrue(thrown is IllegalStateException, "load ended with $thrown")
            assertNull(engine.player.currentItem)
        }
    }

    // --- Playback -----------------------------------------------------------------------------

    @Test
    fun `a loaded movie reports its duration picture size and full buffer`() {
        val path: String = movie
        val engine: AvPlayerVideoEngine = newEngine()
        val listener = RecordingListener()
        engine.setListener(listener)
        runOnMainLoop {
            engine.load(VideoSource.File(path))

            assertTrue(abs(engine.durationMs() - 1_000L) <= 50L, "duration ${engine.durationMs()}")
            assertEquals(0L, engine.positionMs())
            assertTrue(
                awaitCondition(2_000) { listener.sizes.isNotEmpty() },
                "no picture size reported: ${listener.calls}",
            )
            assertEquals(VideoSize(TEST_MOVIE_WIDTH, TEST_MOVIE_HEIGHT), listener.sizes.last())
            assertTrue(
                awaitCondition(2_000) { engine.bufferedPositionMs() >= engine.durationMs() - 50L },
                "buffered ${engine.bufferedPositionMs()} of ${engine.durationMs()}",
            )
            engine.release()
        }
    }

    @Test
    fun `playing to the end reports completion exactly once`() {
        val path: String = movie
        val engine: AvPlayerVideoEngine = newEngine()
        val listener = RecordingListener()
        engine.setListener(listener)
        runOnMainLoop {
            engine.load(VideoSource.File(path))
            engine.start()

            assertTrue(awaitCondition(4_000) { listener.completions > 0 }, "never completed: ${listener.calls}")
            delay(500)

            assertEquals(1, listener.completions)
            assertTrue(listener.failures.isEmpty(), "failures: ${listener.failures}")
            engine.release()
        }
    }

    @Test
    fun `looping restarts at the end without reporting completion`() {
        val path: String = movie
        val engine: AvPlayerVideoEngine = newEngine()
        val listener = RecordingListener()
        engine.setListener(listener)
        runOnMainLoop {
            engine.setLooping(true)
            engine.load(VideoSource.File(path))
            engine.start()

            var maxPositionMs = 0L
            var wrapped = false
            repeat(125) {
                delay(20)
                val positionMs: Long = engine.positionMs()
                if (positionMs + 300L < maxPositionMs) wrapped = true
                maxPositionMs = maxOf(maxPositionMs, positionMs)
            }

            assertEquals(0, listener.completions, "completion reported while looping")
            assertTrue(wrapped, "the playhead never went back to the start (max $maxPositionMs)")
            assertTrue(engine.player.rate > 0f, "not playing after the loop")
            engine.release()
        }
    }

    @Test
    fun `turning looping off before the end completes normally`() {
        val path: String = movie
        val engine: AvPlayerVideoEngine = newEngine()
        val listener = RecordingListener()
        engine.setListener(listener)
        runOnMainLoop {
            engine.setLooping(true)
            engine.load(VideoSource.File(path))
            engine.setLooping(false)
            engine.start()

            assertTrue(awaitCondition(4_000) { listener.completions == 1 }, "never completed: ${listener.calls}")
            engine.release()
        }
    }

    @Test
    fun `the playhead advances while playing and holds while paused`() {
        val path: String = movie
        val engine: AvPlayerVideoEngine = newEngine()
        runOnMainLoop {
            engine.load(VideoSource.File(path))
            engine.start()
            assertTrue(awaitCondition(1_000) { engine.positionMs() >= 200L }, "position ${engine.positionMs()}")

            engine.pause()
            delay(100)
            val pausedAtMs: Long = engine.positionMs()
            delay(300)

            assertEquals(pausedAtMs, engine.positionMs())
            engine.release()
        }
    }

    @Test
    fun `seekTo is reflected by the position getter at once and after the seek lands`() {
        val path: String = movie
        val engine: AvPlayerVideoEngine = newEngine()
        runOnMainLoop {
            engine.load(VideoSource.File(path))

            engine.seekTo(600L)
            assertEquals(600L, engine.positionMs())

            delay(300)
            assertTrue(abs(engine.positionMs() - 600L) <= 40L, "position after seek ${engine.positionMs()}")
            engine.release()
        }
    }

    @Test
    fun `speed chosen while paused applies at start and a new speed applies while playing`() {
        val path: String = movie
        val engine: AvPlayerVideoEngine = newEngine()
        runOnMainLoop {
            engine.load(VideoSource.File(path))
            engine.setSpeed(2f)
            assertEquals(0f, engine.player.rate, "setting a speed must not start a paused player")

            engine.start()
            assertEquals(2f, engine.player.rate)

            engine.setSpeed(0.5f)
            assertEquals(0.5f, engine.player.rate)
            engine.release()
        }
    }

    @Test
    fun `volume set before load survives into playback and changes live`() {
        val path: String = movie
        val engine: AvPlayerVideoEngine = newEngine()
        runOnMainLoop {
            engine.setVolume(0.25f)
            engine.load(VideoSource.File(path))
            assertEquals(0.25f, engine.player.volume)

            engine.setVolume(0f)
            assertEquals(0f, engine.player.volume)
            engine.release()
        }
    }

    @Test
    fun `the AVPlayer can be obtained before any load`() {
        val engine: AvPlayerVideoEngine = newEngine()

        val player: AVPlayer = engine.player

        assertSame(player, engine.player)
        assertNull(player.currentItem)
    }
}

/** Records every listener call, on the main thread. */
private class RecordingListener(
    private val onEvery: () -> Unit = {},
) : VideoPlaybackEngineListener {
    val calls: MutableList<String> = mutableListOf()
    val sizes: MutableList<VideoSize?> = mutableListOf()
    val failures: MutableList<Throwable> = mutableListOf()
    var completions: Int = 0

    override fun onCompleted() {
        completions++
        record("completed")
    }

    override fun onFailed(cause: Throwable) {
        failures += cause
        record("failed $cause")
    }

    override fun onBufferingChanged(isBuffering: Boolean) {
        record("buffering $isBuffering")
    }

    override fun onVideoSizeChanged(size: VideoSize?) {
        sizes += size
        record("size $size")
    }

    private fun record(call: String) {
        calls += call
        onEvery()
    }
}
