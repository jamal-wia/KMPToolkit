package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 6, resource lifecycle: loading a clip out of a jar and releasing the engine, again and again, must not
 * leave a file in the temporary directory per load (the VLC engine promises its extracted copy is deleted on
 * release; JavaFX plays `jar:` URLs, and the platform notes say nothing about files it may extract).
 * Compared by name before and after, ignoring everything that was already there.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxTempFilesR6AdversarialTest {

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
    }

    private fun tempNames(): Set<String> =
        File(System.getProperty("java.io.tmpdir")).list().orEmpty().toSet()

    @Test
    fun `loading an asset out of a jar and releasing it leaves no file in the temporary directory`() {
        val root: File = Files.createTempDirectory("kmptoolkit-r6-tmp-").toFile()
        val jar = File(root, "app.jar")
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("videos/clip.mp4"))
            out.write(TestClip.copyToTempFile().readBytes())
            out.closeEntry()
        }
        val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), null)
        try {
            // One load first, so the toolkit's own one-off files are not counted.
            runOnce(loader)
            val before: Set<String> = tempNames()
            repeat(6) { runOnce(loader) }
            val created: List<String> = (tempNames() - before).filter { !it.startsWith("kmptoolkit-r6-tmp-") }.sorted()
            assertEquals(emptyList(), created, "files left in the temporary directory by six load and release cycles")
        } finally {
            loader.close()
            root.deleteRecursively()
        }
    }

    private fun runOnce(loader: ClassLoader) {
        val engine = JavaFxVideoEngine(classLoader = loader, readyTimeoutMs = 15_000L)
        try {
            runBlocking { withTimeout(20_000L) { engine.load(VideoSource.Asset("videos/clip.mp4")) } }
            engine.start()
            awaitTrue(message = { "no progress" }) { engine.positionMs() > 100L }
        } finally {
            engine.release()
        }
    }
}
