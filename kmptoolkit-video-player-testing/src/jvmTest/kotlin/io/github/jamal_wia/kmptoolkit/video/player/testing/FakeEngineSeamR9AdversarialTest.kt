package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngineListener
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking

/**
 * D1 round 9 (breaker hz), Q5 mutant T3: 06-testing says `completePlayback()` on an engine that was asked to loop
 * goes "back to `0` with no completion, like a real engine". The player above the fake also copes with a stray
 * completion, so only a listener attached to the fake itself can see the difference; this one records every call.
 */
class FakeEngineSeamR9AdversarialTest {

    private class Recorder : VideoPlaybackEngineListener {
        val events: MutableList<String> = mutableListOf()
        override fun onCompleted() { events += "completed" }
        override fun onFailed(cause: Throwable) { events += "failed" }
        override fun onBufferingChanged(isBuffering: Boolean) { events += "buffering($isBuffering)" }
        override fun onVideoSizeChanged(size: VideoSize?) { events += "size($size)" }
    }

    @Test
    fun `completePlayback on a looping engine rewinds to zero and reports no completion`() {
        val engine = FakeVideoPlaybackEngine(60_000L)
        val recorder = Recorder()
        engine.setListener(recorder)
        runBlocking { engine.load(io.github.jamal_wia.kmptoolkit.video.player.VideoSource.Remote("https://example.test/a.mp4")) }
        engine.setLooping(true)
        engine.start()
        engine.advancePositionTo(59_000L)

        engine.completePlayback()

        assertEquals(emptyList(), recorder.events, "a looping engine reported ${recorder.events}")
        assertEquals(0L, engine.positionMs)
        assertEquals(true, engine.isPlaying, "a looping engine keeps playing")
        engine.setLooping(false)
        engine.advancePositionTo(59_000L)
        engine.completePlayback()
        assertEquals(listOf("completed"), recorder.events)
        assertEquals(60_000L, engine.positionMs)
        assertFalse(engine.isPlaying)
    }

    /**
     * `FakeVideoPlaybackEngine.positionMs` documents that a load, a release and a looping `completePlayback` reset
     * it to `0`. The engine is driven directly, so that nothing the player above it does can mask a missing reset
     * (mutants T1 and T2 of round 9 survived the tests that go through the player).
     */
    @Test
    fun `a load and a release each reset the playhead read from the fake itself`() {
        val engine = FakeVideoPlaybackEngine(60_000L)
        val source = io.github.jamal_wia.kmptoolkit.video.player.VideoSource.Remote("https://example.test/a.mp4")
        runBlocking { engine.load(source) }
        engine.advancePositionTo(5_000L)
        assertEquals(5_000L, engine.positionMs, "the harness does not move the playhead")

        runBlocking { engine.load(source) }
        assertEquals(0L, engine.positionMs, "a load did not reset the playhead")

        engine.advancePositionTo(5_000L)
        engine.release()
        assertEquals(0L, engine.positionMs, "a release did not reset the playhead")
    }
}
