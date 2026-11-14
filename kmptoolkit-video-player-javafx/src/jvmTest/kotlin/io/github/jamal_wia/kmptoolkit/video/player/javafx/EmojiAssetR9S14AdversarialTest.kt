package io.github.jamal_wia.kmptoolkit.video.player.javafx


import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 9 (hostile media): the JDK class loader encodes a character outside the Basic Multilingual Plane one surrogate at a time
 * (invalid UTF-8), which JavaFX rejects; an Asset with such a name must still play, as VideoSource.File with it does.
 */
class EmojiAssetR9S14AdversarialTest {
    private val name: String = "😀 emoji.mp4"

    private fun loadAndPlay(loader: ClassLoader): String? {
        val engine = JavaFxVideoEngine(classLoader = loader, readyTimeoutMs = 15_000L)
        return try {
            runBlocking { withTimeout(20_000L) { engine.load(VideoSource.Asset("media/$name")) } }
            engine.start()
            val until = System.nanoTime() + 6_000_000_000L
            while (System.nanoTime() < until && engine.positionMs() <= 300L) Thread.sleep(10L)
            if (engine.positionMs() > 300L) null else "never moved"
        } catch (t: Throwable) { "${t::class.java.simpleName}: ${t.message}" } finally { engine.release() }
    }

    @Test
    fun `an asset with a name outside the basic multilingual plane plays from a jar and from a directory`() {
        assumeJavaFxMedia()
        val root: File = Files.createTempDirectory("r9-s14").toFile()
        try {
            val bytes: ByteArray = TestClip.copyToTempFile().readBytes()
            val jar = File(root, "a.jar")
            JarOutputStream(jar.outputStream()).use { o -> o.putNextEntry(JarEntry("media/$name")); o.write(bytes); o.closeEntry() }
            val dir = File(root, "res").apply { File(this, "media").mkdirs() }
            File(dir, "media/$name").writeBytes(bytes)
            val failures = listOf("jar" to jar, "directory" to dir).mapNotNull { (label, location) ->
                URLClassLoader(arrayOf(location.toURI().toURL()), null).use { loadAndPlay(it)?.let { m -> "$label: $m" } }
            }
            assertEquals(emptyList(), failures)
        } finally { root.deleteRecursively() }
    }
}
