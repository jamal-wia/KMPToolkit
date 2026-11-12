package io.github.jamal_wia.kmptoolkit.audio.player.testing

import io.github.jamal_wia.kmptoolkit.audio.player.AudioSource
import io.github.jamal_wia.kmptoolkit.audio.player.PlaybackEngineListener
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * What the fake does to the playhead and the playing flag when a test drives it the way a platform
 * would: a load and a release stop and rewind, a completion lands on the end, a failure stops.
 */
class FakePlaybackEngineDriveR8Test {

    private class Recorded : PlaybackEngineListener {
        var completed: Int = 0
        val failures: MutableList<Throwable> = mutableListOf()
        override fun onCompleted() { completed++ }
        override fun onFailed(cause: Throwable) { failures += cause }
    }

    @Test
    fun `a load stops what was playing and rewinds the playhead`() = runTest {
        val engine = FakePlaybackEngine(durationMs = 10_000L)
        engine.start()
        engine.advancePositionTo(4_000L)

        engine.load(AudioSource.File("/a.mp3"))

        assertEquals(0L, engine.positionMs())
        assertFalse(engine.isPlaying)
    }

    @Test
    fun `a failed load also stops and rewinds`() = runTest {
        val engine = FakePlaybackEngine(durationMs = 10_000L)
        engine.start()
        engine.advancePositionTo(4_000L)
        engine.loadFailure = IllegalStateException("no")

        runCatching { engine.load(AudioSource.File("/a.mp3")) }

        assertEquals(0L, engine.positionMs())
        assertFalse(engine.isPlaying)
    }

    @Test
    fun `release stops rewinds and is counted`() {
        val engine = FakePlaybackEngine(durationMs = 10_000L)
        engine.start()
        engine.advancePositionTo(4_000L)

        engine.release()

        assertEquals(0L, engine.positionMs())
        assertFalse(engine.isPlaying)
        assertEquals(1, engine.releaseCount)
    }

    @Test
    fun `completion lands on the end stops playing and tells the listener once`() {
        val engine = FakePlaybackEngine(durationMs = 10_000L)
        val listener = Recorded()
        engine.setListener(listener)
        engine.start()
        engine.advancePositionTo(100L)

        engine.completePlayback()

        assertEquals(10_000L, engine.positionMs())
        assertFalse(engine.isPlaying)
        assertEquals(1, listener.completed)
    }

    @Test
    fun `a playback failure stops playing and hands the cause over unchanged`() {
        val engine = FakePlaybackEngine()
        val listener = Recorded()
        engine.setListener(listener)
        engine.start()
        val cause = IllegalStateException("boom")

        engine.failPlayback(cause)

        assertFalse(engine.isPlaying)
        assertEquals(1, listener.failures.size)
        assertSame(cause, listener.failures.single())
        assertTrue(engine.hasListener)
    }
}
