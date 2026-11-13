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
}
