package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.video.player.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.io.File
import java.io.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The checks the engine makes before it touches JavaFX at all, so they hold on a machine where the
 * toolkit cannot start — none of these tests is skipped headless.
 */
@OptIn(ToolkitInternalApi::class)
class SourceResolutionTest {

    private val noJavaFx = JavaFxRuntime { error("the toolkit must not be needed for this source") }

    @Test
    fun remoteHeadersAreRejectedNotDropped() {
        val engine = JavaFxVideoEngine(runtime = noJavaFx)
        val error = assertFailsWith<JavaFxVideoPlayerException.HeadersNotSupported> {
            runBlocking {
                engine.load(
                    VideoSource.Remote(
                        url = "https://example.com/video.mp4",
                        headers = mapOf("Authorization" to "Bearer token", "X-Trace" to "1"),
                    ),
                )
            }
        }
        assertEquals(setOf("Authorization", "X-Trace"), error.headerNames)
        assertEquals(0L, engine.durationMs())
        assertEquals(null, engine.frames.value)
    }

    @Test
    fun missingAssetFailsWithFileNotFound() {
        val engine = JavaFxVideoEngine(runtime = noJavaFx)
        assertFailsWith<FileNotFoundException> {
            runBlocking { engine.load(VideoSource.Asset("no/such/clip.mp4")) }
        }
    }

    @Test
    fun missingFileFailsWithFileNotFound() {
        val engine = JavaFxVideoEngine(runtime = noJavaFx)
        val missing = File(System.getProperty("java.io.tmpdir"), "kmptoolkit-no-such-${System.nanoTime()}.mp4")
        assertFailsWith<FileNotFoundException> {
            runBlocking { engine.load(VideoSource.File(missing.absolutePath)) }
        }
    }

    @Test
    fun directoryIsNotAPlayableFile() {
        val engine = JavaFxVideoEngine(runtime = noJavaFx)
        assertFailsWith<FileNotFoundException> {
            runBlocking { engine.load(VideoSource.File(System.getProperty("java.io.tmpdir"))) }
        }
    }

    @Test
    fun blankPathsAndUrlsAreRejected() {
        val engine = JavaFxVideoEngine(runtime = noJavaFx)
        assertFailsWith<IllegalArgumentException> { runBlocking { engine.load(VideoSource.Asset("")) } }
        assertFailsWith<IllegalArgumentException> { runBlocking { engine.load(VideoSource.File(" ")) } }
        assertFailsWith<IllegalArgumentException> { runBlocking { engine.load(VideoSource.Remote("")) } }
    }

    @Test
    fun assetResolvesToAClasspathUrlWithOrWithoutLeadingSlash() {
        val loader: ClassLoader = javaClass.classLoader
        val plain: String = resolveSourceUri(VideoSource.Asset(TestClip.ASSET), loader)
        val slashed: String = resolveSourceUri(VideoSource.Asset("/${TestClip.ASSET}"), loader)
        assertEquals(plain, slashed)
        assertTrue(plain.endsWith(TestClip.ASSET), plain)
    }

    @Test
    fun runtimeThatCannotStartIsReportedAsTyped() {
        val cause = UnsupportedOperationException("Unable to open DISPLAY")
        val engine = JavaFxVideoEngine(
            runtime = { throw JavaFxVideoPlayerException.RuntimeUnavailable(cause) },
        )
        val error = assertFailsWith<JavaFxVideoPlayerException.RuntimeUnavailable> {
            runBlocking { engine.load(VideoSource.Asset(TestClip.ASSET)) }
        }
        assertEquals(cause, error.cause)
        // Nothing half-loaded is left behind, and release is still safe.
        assertEquals(0L, engine.durationMs())
        engine.release()
        engine.release()
    }

    @Test
    fun transportAndSettingsCallsBeforeAnyLoadAreIgnored() {
        val engine = JavaFxVideoEngine(runtime = noJavaFx)
        engine.start()
        engine.pause()
        engine.seekTo(1_000L)
        engine.setSpeed(2f)
        engine.setVolume(0.5f)
        engine.setLooping(true)
        engine.release()
        assertEquals(0L, engine.positionMs())
        assertEquals(0L, engine.bufferedPositionMs())
    }
}
