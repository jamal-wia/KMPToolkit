package io.github.jamal_wia.kmptoolkit.video.player

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionRouteChangeNotification
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeErrorKey
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.currentItem
import platform.Foundation.NSBundle
import platform.Foundation.NSError
import platform.Foundation.NSNotificationCenter

/**
 * D1 round 11 (breaker ip), Q2: platform notifications the real AVPlayer engine can receive in orders and numbers a
 * healthy item does not produce, posted on the real item of a real one-second movie: `AVPlayerItemDidPlayToEndTime`
 * twice, `AVPlayerItemFailedToPlayToEndTime` twice (a failure after ready), the two `AVAudioSession` notifications the
 * `05-platform-notes.md` says are not observed, and a leak probe for the observers of a released session (the open
 * mutant I4 of round 8: `tearDown` without `removeObserver`).
 *
 * Every body runs inside `runOnMainLoop` (hard 20 s bound, its own failure message).
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class, NativeRuntimeApi::class, ToolkitInternalApi::class)
class IosHostileEventsD1R11AdversarialTest {

    private val movie: String by lazy { writeTestMovie("kmptoolkit-video-player-ios-r11-test") }

    private class CountingListener : VideoPlaybackEngineListener {
        var completions: Int = 0
        val failures: MutableList<Throwable> = mutableListOf()

        override fun onCompleted() {
            completions += 1
        }

        override fun onFailed(cause: Throwable) {
            failures += cause
        }

        override fun onBufferingChanged(isBuffering: Boolean) = Unit

        override fun onVideoSizeChanged(size: VideoSize?) = Unit
    }

    private fun newEngine(): AvPlayerVideoEngine = AvPlayerVideoEngine(
        assetBundle = NSBundle.mainBundle,
        assetSubdirectories = emptyList(),
        managesAudioSession = false,
    )

    private fun post(name: String, item: AVPlayerItem, userInfo: Map<Any?, *>? = null) {
        NSNotificationCenter.defaultCenter.postNotificationName(name, `object` = item, userInfo = userInfo)
    }

    @Test
    fun `the failed to play to end notification twice reports one failure carrying the item error and none after release`() {
        val path: String = movie
        val engine: AvPlayerVideoEngine = newEngine()
        val listener = CountingListener()
        engine.setListener(listener)
        try {
            runOnMainLoop {
                engine.load(VideoSource.File(path))
                val item: AVPlayerItem = assertNotNull(engine.player.currentItem)
                val error: NSError = NSError.errorWithDomain("r11.test", code = 77, userInfo = null)
                val info: Map<Any?, *> = mapOf(AVPlayerItemFailedToPlayToEndTimeErrorKey to error)
                post(AVPlayerItemFailedToPlayToEndTimeNotification!!, item, info)
                post(AVPlayerItemFailedToPlayToEndTimeNotification!!, item, info)
                assertTrue(awaitCondition(5_000L) { listener.failures.isNotEmpty() }, "no failure reached the listener")
                delay(150)
                assertEquals(1, listener.failures.size, "a failure after ready is reported once, however often the platform repeats it")
                val failure: Throwable = listener.failures.single()
                assertIs<IllegalStateException>(failure)
                assertTrue(
                    failure.message.orEmpty().contains("r11.test") && failure.message.orEmpty().contains("77"),
                    "05: the error carries the domain and the code: ${failure.message}",
                )
                engine.release()
                delay(100)
                post(AVPlayerItemFailedToPlayToEndTimeNotification!!, item, info)
                post(AVPlayerItemDidPlayToEndTimeNotification!!, item)
                delay(200)
                assertEquals(1, listener.failures.size, "nothing reaches the listener after release")
                assertEquals(0, listener.completions, "nothing reaches the listener after release")
            }
        } finally {
            engine.release()
        }
    }

    @Test
    fun `the did play to end notification twice completes the player once and the playhead stays at the end`() {
        val path: String = movie
        val player: VideoPlayer = createVideoPlayer(
            config = VideoPlayerConfig(positionUpdateIntervalMs = 50L),
            managesAudioSession = false,
        )
        try {
            runOnMainLoop {
                player.prepare(VideoSource.File(path))
                player.play()
                val item: AVPlayerItem = assertNotNull(player.avPlayerOrNull()?.currentItem)
                post(AVPlayerItemDidPlayToEndTimeNotification!!, item)
                post(AVPlayerItemDidPlayToEndTimeNotification!!, item)
                assertTrue(awaitCondition(5_000L) { player.stateFlow.value is VideoPlayerState.Completed }, "never completed: ${player.stateFlow.value}")
                val first: VideoPlayerState = player.stateFlow.value
                delay(200)
                assertEquals(first, player.stateFlow.value, "a duplicate end changes nothing")
                assertEquals((first as VideoPlayerState.Completed).duration, player.playbackPositionFlow.value)
            }
        } finally {
            player.release()
        }
    }

    @Test
    fun `05 audio interruptions and route changes are not observed so the player neither pauses nor fails`() {
        val path: String = movie
        val player: VideoPlayer = createVideoPlayer(
            config = VideoPlayerConfig(positionUpdateIntervalMs = 50L),
            managesAudioSession = false,
        )
        try {
            runOnMainLoop {
                player.prepare(VideoSource.File(path))
                player.play()
                // AVAudioSessionInterruptionTypeKey = 1 (began); posted without an object, as the system does.
                NSNotificationCenter.defaultCenter.postNotificationName(
                    AVAudioSessionInterruptionNotification,
                    `object` = null,
                    userInfo = mapOf<Any?, Any?>("AVAudioSessionInterruptionTypeKey" to 1uL),
                )
                NSNotificationCenter.defaultCenter.postNotificationName(
                    AVAudioSessionRouteChangeNotification,
                    `object` = null,
                    userInfo = mapOf<Any?, Any?>("AVAudioSessionRouteChangeReasonKey" to 2uL),
                )
                delay(100)
                val state: VideoPlayerState = player.stateFlow.value
                assertTrue(
                    state is VideoPlayerState.Playing || state is VideoPlayerState.Completed,
                    "05: interruptions are not observed, yet the player moved to $state",
                )
            }
        } finally {
            player.release()
        }
    }

    /** Builds, loads and releases an engine in a frame of its own and hands back only a weak reference to it. */
    private fun loadedAndReleasedEngineWeakly(path: String): WeakReference<AvPlayerVideoEngine> {
        val engine: AvPlayerVideoEngine = newEngine()
        runOnMainLoop {
            engine.load(VideoSource.File(path))
            engine.release()
            delay(100)
        }
        return WeakReference(engine)
    }

    @Test
    fun `a released session leaves nothing that keeps the engine alive`() {
        val path: String = movie
        val weak: WeakReference<AvPlayerVideoEngine> = loadedAndReleasedEngineWeakly(path)
        var collected = false
        for (attempt: Int in 1..30) {
            GC.collect()
            pumpMainLoopUntil(0.05) { false }
            if (weak.value == null) {
                collected = true
                break
            }
        }
        assertTrue(collected, "the engine of a released session was still reachable after 30 collections: its notification observers outlive tearDown")
        assertNull(weak.value)
    }
}
