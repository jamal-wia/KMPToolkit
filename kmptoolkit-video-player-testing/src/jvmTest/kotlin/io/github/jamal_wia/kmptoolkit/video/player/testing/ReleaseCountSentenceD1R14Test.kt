package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler

/**
 * D1 round 14: the sentence of `06-testing.md` about the fake's counters, derived from it and nothing else: "`releaseCount`
 * goes up once per unload, per cancelled load, per `prepare` that replaces a loaded, in-flight, failed or `Error` source, and
 * for the player's own release (a first `prepare` adds none). `disposeCount` is `1` once the player is released, `0` before
 * - never raised by an unload." Written as the killer of the round's mutants J2 (the "engine holds a source" flag is not
 * cleared by a release) and J5 (a first `prepare` frees an engine that holds nothing).
 */
class ReleaseCountSentenceD1R14Test {

    private val source: VideoSource = VideoSource.Remote("https://example.test/v.mp4")
    private val engine: FakeVideoPlaybackEngine = FakeVideoPlaybackEngine(durationMs = 10_000L)
    private val player: VideoPlayer =
        createVideoPlayer(engine, VideoPlayerConfig(positionUpdateIntervalMs = 100L), StandardTestDispatcher(TestCoroutineScheduler()))

    private fun prepare() = runBlocking { player.prepare(source) }

    @Test
    fun `releaseCount follows the sentence of the testing guide through every way a source ends`() {
        // A first prepare adds none.
        prepare()
        assertEquals(0, engine.releaseCount, "first prepare")
        assertEquals(0, engine.disposeCount)
        // A prepare that replaces a loaded source adds one.
        prepare()
        assertEquals(1, engine.releaseCount, "prepare over a loaded source")
        // An unload adds one, and the prepare after it (nothing loaded) adds none.
        player.unload()
        assertEquals(2, engine.releaseCount, "unload")
        assertEquals(0, engine.disposeCount, "an unload never raises disposeCount")
        prepare()
        assertEquals(2, engine.releaseCount, "prepare after an unload")
        // A cancelled load adds one, and the prepare after it adds none.
        engine.suspendLoads = true
        val caller: Job = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) { player.prepare(source) }
        assertEquals(VideoPlayerState.Preparing, player.stateFlow.value)
        // The prepare above replaced a loaded source: one release by its start step.
        assertEquals(3, engine.releaseCount, "prepare over a loaded source, in flight")
        runBlocking { caller.cancelAndJoin() }
        assertEquals(4, engine.releaseCount, "a cancelled load")
        engine.suspendLoads = false
        prepare()
        assertEquals(4, engine.releaseCount, "prepare after a cancelled load")
        // A prepare that replaces an in-flight load adds one (the newer call's start step), not two.
        engine.suspendLoads = true
        val first: Job = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) { player.prepare(source) }
        val afterFirst: Int = engine.releaseCount
        assertEquals(5, afterFirst, "prepare over a loaded source")
        val second: Job = CoroutineScope(Dispatchers.Unconfined).launch(start = CoroutineStart.UNDISPATCHED) { player.prepare(source) }
        assertEquals(6, engine.releaseCount, "prepare over an in-flight load")
        engine.finishLoad()
        runBlocking { first.join(); second.join() }
        assertEquals(VideoPlayerState.Ready(10_000L), player.stateFlow.value)
        engine.suspendLoads = false
        // A failed source: the next prepare adds one.
        engine.loadFailure = IOException("down")
        prepare()
        assertTrue(player.stateFlow.value is VideoPlayerState.Error)
        assertEquals(7, engine.releaseCount, "prepare over a loaded source whose replacement then failed")
        engine.loadFailure = null
        prepare()
        assertEquals(8, engine.releaseCount, "prepare over a failed source")
        // An Error after a successful load (a failure during playback): the next prepare adds one.
        player.play()
        engine.failPlayback(IOException("died"))
        assertTrue(player.stateFlow.value is VideoPlayerState.Error)
        assertEquals(8, engine.releaseCount, "a failure alone frees nothing")
        prepare()
        assertEquals(9, engine.releaseCount, "prepare over an Error source")
        // The player's own release adds one, and disposes exactly once.
        player.release()
        assertEquals(10, engine.releaseCount, "release")
        assertEquals(1, engine.disposeCount)
        player.release()
        assertEquals(10, engine.releaseCount, "a second release adds nothing")
        assertEquals(1, engine.disposeCount)
    }
}
