package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * The rig of the D1 round 11 fix2 tests: a player over [ScriptedEngineD1R11] on a hand-stepped scheduler, and
 * collectors on an Unconfined scope, so a collector runs inside the write that wakes it (the "immediate dispatcher" of
 * `04-api-reference.md`). No thread, no sleep: virtual time only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class InlineRigD1R11 {
    val source: VideoSource = VideoSource.Remote("https://example.test/a.mp4")
    val scheduler: TestCoroutineScheduler = TestCoroutineScheduler()
    val engine: ScriptedEngineD1R11 = ScriptedEngineD1R11().apply { buffered = 4_000L }
    val escaped: MutableList<Throwable> = mutableListOf()
    val player: VideoPlayer = createVideoPlayer(
        engine,
        VideoPlayerConfig(positionUpdateIntervalMs = 100L),
        StandardTestDispatcher(scheduler) + CoroutineExceptionHandler { _, thrown -> escaped += thrown },
    )
    val scope: CoroutineScope = CoroutineScope(Job() + Dispatchers.Unconfined)

    fun prepareNow() {
        runBlocking { player.prepare(source) }
    }

    /** One poll tick: more than one interval of virtual time. */
    fun tick() {
        scheduler.advanceTimeBy(150L)
        scheduler.runCurrent()
    }

    /** Prepared, playing at [position], one tick taken, a stall and a picture size reported: every source flow is non-initial. */
    fun playingStalledWithSize(position: Long = 3_000L) {
        prepareNow()
        player.play()
        engine.position = position
        tick()
        val sink = checkNotNull(engine.sink)
        sink.onBufferingChanged(true)
        sink.onVideoSizeChanged(VideoSize(640, 360))
    }

    /** Runs [reaction] inside the first update of [flow] after this call (the current value does not count). */
    fun <T> reactOnce(flow: Flow<T>, reaction: () -> Unit): Fired {
        val fired = Fired()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            flow.drop(1).collect {
                if (!fired.value) {
                    fired.value = true
                    reaction()
                }
            }
        }
        return fired
    }

    /** Records every state the player publishes from now on, the current one first. */
    fun recordStates(): List<VideoPlayerState> {
        val seen: MutableList<VideoPlayerState> = mutableListOf()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { player.stateFlow.collect { seen += it } }
        return seen
    }

    fun close() {
        scope.cancel()
        player.release()
    }

    class Fired {
        var value: Boolean = false
    }
}
