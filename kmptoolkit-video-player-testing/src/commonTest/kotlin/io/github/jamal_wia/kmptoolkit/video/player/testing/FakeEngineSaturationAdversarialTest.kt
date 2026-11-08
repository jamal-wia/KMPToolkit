package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The fake against its own KDoc: a huge step saturates, and the release count follows its description. */
class FakeEngineSaturationAdversarialTest {

    private val source: VideoSource = VideoSource.Asset("clip.mp4")

    @Test
    fun `advancePositionBy never goes past a known duration or below where it was`() {
        val engine = FakeVideoPlaybackEngine(durationMs = 10_000L)
        engine.advancePositionTo(5L)
        engine.advancePositionBy(Long.MAX_VALUE)
        assertEquals(10_000L, engine.positionMs, "a huge step must clamp at the duration, not wrap around")

        val live = FakeVideoPlaybackEngine(durationMs = 0L)
        live.advancePositionTo(5L)
        live.advancePositionBy(Long.MAX_VALUE)
        assertTrue(live.positionMs >= 5L, "a forward step moved the playhead backwards to ${live.positionMs}")
        assertEquals(Long.MAX_VALUE, live.positionMs)
        live.advancePositionTo(-5L)
        live.advancePositionBy(Long.MIN_VALUE)
        assertEquals(Long.MIN_VALUE, live.positionMs)
    }

    @Test
    fun `a re-prepare of a loaded source counts one release and a first prepare none`() = runTest {
        val engine = FakeVideoPlaybackEngine()
        val player: VideoPlayer = createVideoPlayer(engine = engine, coroutineContext = StandardTestDispatcher(testScheduler))
        player.prepare(source)
        assertEquals(0, engine.releaseCount, "a first prepare frees nothing")
        player.prepare(source)
        assertEquals(1, engine.releaseCount, "replacing a loaded source frees it before the next load")
        player.unload()
        assertEquals(2, engine.releaseCount)
        player.prepare(source)
        assertEquals(2, engine.releaseCount, "a prepare after an unload frees nothing again")
        player.release()
    }
}
