package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** Sources whose path needs URI escaping, through the real JavaFX Media. */
@OptIn(ToolkitInternalApi::class)
class JavaFxOddPathAdversarialTest {

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
    }

    private val names: List<String> = listOf(
        "with space", "umläut-ü", "hash#tag", "percent%20sign", "plus+sign", "amp&ersand", "quote'single", "br[ack]et", "semi;colon", "日本語",
    )

    private fun copyClipTo(dir: File): File {
        dir.mkdirs()
        val target = File(dir, "clip.mp4")
        TestClip.copyToTempFile().copyTo(target, overwrite = true)
        return target
    }

    @Test
    fun `a file source under a directory whose name needs escaping loads and reports its duration`() {
        val base: File = Files.createTempDirectory("kmptoolkit-odd").toFile()
        val failures: MutableList<String> = mutableListOf()
        try {
            for (name: String in names) {
                val engine = JavaFxVideoEngine(readyTimeoutMs = 10_000L)
                try {
                    val clip: File = copyClipTo(File(base, name))
                    runBlocking { withTimeout(15_000L) { engine.load(VideoSource.File(clip.path)) } }
                    awaitTrue(timeoutMs = 3_000L, message = { "no duration" }) { engine.durationMs() > 0L }
                    if (engine.durationMs() != TestClip.DURATION_MS) failures += "$name: duration ${engine.durationMs()}"
                } catch (e: Throwable) {
                    failures += "$name: $e"
                } finally {
                    engine.release()
                }
            }
        } finally {
            base.deleteRecursively()
        }
        assertEquals(emptyList(), failures, "file sources that did not load")
    }

    @Test
    fun `a classpath asset under a directory whose name needs escaping loads`() {
        val base: File = Files.createTempDirectory("kmptoolkit-odd-cp").toFile()
        val failures: MutableList<String> = mutableListOf()
        try {
            for (name: String in names) {
                val root = File(base, name)
                copyClipTo(File(root, "video"))
                val loader = URLClassLoader(arrayOf(root.toURI().toURL()), null)
                val engine = JavaFxVideoEngine(classLoader = loader, readyTimeoutMs = 10_000L)
                try {
                    runBlocking { withTimeout(15_000L) { engine.load(VideoSource.Asset("video/clip.mp4")) } }
                    awaitTrue(timeoutMs = 3_000L, message = { "no duration" }) { engine.durationMs() > 0L }
                } catch (e: Throwable) {
                    failures += "$name: $e"
                } finally {
                    engine.release()
                    loader.close()
                }
            }
        } finally {
            base.deleteRecursively()
        }
        assertEquals(emptyList(), failures, "assets that did not load")
    }
}
