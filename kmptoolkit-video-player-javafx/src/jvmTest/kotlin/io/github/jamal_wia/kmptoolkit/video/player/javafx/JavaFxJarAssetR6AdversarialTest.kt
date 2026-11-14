package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 6, docs versus code: "VideoSource.Asset ... works from a directory and from inside a jar"
 * (03-guide, 05-platform-notes: `jar:` is how a classpath Asset inside a jar plays). A packaged desktop app
 * lives in a folder whose name may hold a space or a non-ASCII letter ("My App.app"), and a plugin class
 * loader may hand out URLs that are not percent-encoded. The engine fixes the second case for `file:`
 * only; these tests play a real clip out of a real jar in such a folder.
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxJarAssetR6AdversarialTest {

    private val cleanup: MutableList<() -> Unit> = mutableListOf()

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
    }

    @AfterTest
    fun tearDown() {
        cleanup.forEach { runCatching(it) }
    }

    /** A jar holding the test clip as `videos/clip.mp4`, in a folder called [folder] under a fresh temp directory. */
    private fun jarOf(folder: String): File {
        val root: File = Files.createTempDirectory("kmptoolkit-r6-").toFile()
        val dir = File(root, folder).apply { mkdirs() }
        val jar = File(dir, "app.jar")
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("videos/clip.mp4"))
            out.write(TestClip.copyToTempFile().readBytes())
            out.closeEntry()
        }
        cleanup += { root.deleteRecursively() }
        return jar
    }

    private fun playsFrom(loader: ClassLoader, path: String) {
        val engine = JavaFxVideoEngine(classLoader = loader, readyTimeoutMs = 15_000L)
        try {
            runBlocking { withTimeout(20_000L) { engine.load(VideoSource.Asset(path)) } }
            engine.start()
            awaitTrue(message = { "the clip from the jar never advanced: ${engine.positionMs()}" }) { engine.positionMs() > 300L }
        } finally {
            engine.release()
        }
    }

    @Test
    fun `an asset inside a jar in a folder with a space and a non ASCII letter plays`() {
        val jar: File = jarOf("My App ü")
        val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), null)
        cleanup += { loader.close() }
        playsFrom(loader, "videos/clip.mp4")
    }

    @Test
    fun `an asset inside a jar plays with a leading slash`() {
        val jar: File = jarOf("plain")
        val loader = URLClassLoader(arrayOf(jar.toURI().toURL()), null)
        cleanup += { loader.close() }
        playsFrom(loader, "/videos/clip.mp4")
    }

    @Test
    fun `an asset inside a jar whose loader does not percent encode its URLs plays`() {
        val jar: File = jarOf("My App")
        @Suppress("DEPRECATION")
        val loader = URLClassLoader(arrayOf(jar.toURL()), null)
        cleanup += { loader.close() }
        playsFrom(loader, "videos/clip.mp4")
    }
}
