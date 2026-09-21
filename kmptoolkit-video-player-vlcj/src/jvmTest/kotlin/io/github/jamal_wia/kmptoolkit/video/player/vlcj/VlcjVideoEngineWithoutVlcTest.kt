package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.video.player.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.io.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The engine on a machine without VLC — simulated by a discovery that finds nothing, so these run
 * everywhere. A missing VLC must be a typed, recoverable failure, never a crash, and nothing may
 * touch libvlc before a source is loaded.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjVideoEngineWithoutVlcTest {

    private var discoveries = 0
    private val listener = RecordingListener()
    private val engine = VlcjVideoEngine(
        vlcArgs = emptyList(),
        discover = {
            discoveries++
            false
        },
    ).also { it.setListener(listener) }

    @Test
    fun `load fails with VlcUnavailableException when VLC is not found`() = runBlocking<Unit> {
        assertFailsWith<VlcUnavailableException> { engine.load(VideoSource.File(TestClip.file.path)) }

        assertEquals(1, discoveries)
        assertTrue(listener.events.isEmpty(), "a failed load reports by throwing, not through the listener")
        assertEquals(0L, engine.durationMs())
        assertEquals(0L, engine.positionMs())
        assertNull(engine.frames.value)
    }

    @Test
    fun `a missing file is reported as such before VLC is looked for`() = runBlocking<Unit> {
        assertFailsWith<FileNotFoundException> { engine.load(VideoSource.File("/nonexistent/clip.mp4")) }

        assertEquals(0, discoveries)
    }

    @Test
    fun `an unsupported header is reported as such before VLC is looked for`() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> {
            engine.load(VideoSource.Remote("https://example.test/a.mp4", mapOf("Cookie" to "a=b")))
        }

        assertEquals(0, discoveries)
    }

    @Test
    fun `transport, settings and getters before any load are harmless no-ops`() {
        engine.start()
        engine.pause()
        engine.seekTo(1_000L)
        engine.setSpeed(2f)
        engine.setVolume(0.5f)
        engine.setLooping(true)

        assertEquals(0L, engine.durationMs())
        assertEquals(0L, engine.positionMs())
        assertEquals(0L, engine.bufferedPositionMs())
        assertTrue(listener.events.isEmpty())
    }

    @Test
    fun `release is idempotent, before any load and after a failed one`() = runBlocking<Unit> {
        engine.release()
        engine.release()

        assertFailsWith<VlcUnavailableException> { engine.load(VideoSource.File(TestClip.file.path)) }
        engine.release()
        engine.release()

        assertNull(engine.frames.value)
    }

    @Test
    fun `load after release still reports the typed failure`() = runBlocking<Unit> {
        engine.release()

        assertFailsWith<VlcUnavailableException> { engine.load(VideoSource.Asset(TestClip.ASSET_PATH)) }
    }
}
