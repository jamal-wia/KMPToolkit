package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.text.Normalizer
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Round 9 (hostile media): REAL playback of a clip whose name carries every character that means something in a URL
 * (`#`, `?`, `%25`, `%41`, `+`, `&`, `;`, brackets, an emoji, a decomposed letter), as a `File` and as an `Asset` inside a
 * jar, for both a class loader that percent-encodes its URLs and one that does not. Round 7 only resolved such names; JavaFX
 * has to open the URI the engine builds, and a wrongly encoded name fails there. Combinations the file system or the
 * jar refuses to create are skipped (a name is only attacked where it can exist).
 */
@OptIn(ToolkitInternalApi::class)
class JavaFxOddNamesPlaybackR9AdversarialTest {

    private val cleanup: MutableList<() -> Unit> = mutableListOf()

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
    }

    @AfterTest
    fun tearDown() {
        cleanup.forEach { runCatching(it) }
    }

    private val names: List<String> = listOf(
        "a#b.mp4", "a?b.mp4", "100%25.mp4", "%41.mp4", "a+b.mp4", "a&b=c.mp4", "a;b.mp4", "[x].mp4", "{y}.mp4", "a b  c.mp4",
        "😀 emoji.mp4", Normalizer.normalize("été.mp4", Normalizer.Form.NFD), Normalizer.normalize("été.mp4", Normalizer.Form.NFC),
        "a'b.mp4", "UPPER.MP4", "no-extension-clip.m4v",
    )

    private fun plays(engine: JavaFxVideoEngine, source: VideoSource): String? {
        return try {
            runBlocking { withTimeout(20_000L) { engine.load(source) } }
            engine.setSpeed(1f)
            engine.start()
            val until: Long = System.nanoTime() + 6_000_000_000L
            while (System.nanoTime() < until && engine.positionMs() <= 300L) Thread.sleep(10L)
            if (engine.positionMs() > 300L) null else "loaded but the playhead never moved (${engine.positionMs()})"
        } catch (t: Throwable) {
            "${t::class.java.simpleName}: ${t.message?.take(160)}"
        } finally {
            engine.release()
        }
    }

    @Test
    fun `a clip with a url meaningful name plays as a file and out of a jar`() {
        val root: File = Files.createTempDirectory("kmptoolkit-r9-names").toFile()
        cleanup += { root.deleteRecursively() }
        val bytes: ByteArray = TestClip.copyToTempFile().readBytes()
        val failures: MutableList<String> = mutableListOf()
        var attacked = 0
        for (name: String in names) {
            // As a file.
            val file = File(root, name)
            if (runCatching { file.writeBytes(bytes) }.isSuccess && file.isFile) {
                attacked++
                plays(JavaFxVideoEngine(readyTimeoutMs = 15_000L), VideoSource.File(file.path))?.let { failures += "File '$name': $it" }
            }
            // Out of a jar, through a loader that encodes and one that does not.
            val jar = File(root, "jar-${name.hashCode()}.jar")
            val made: Boolean = runCatching {
                JarOutputStream(jar.outputStream()).use { out ->
                    out.putNextEntry(JarEntry("media/$name"))
                    out.write(bytes)
                    out.closeEntry()
                }
            }.isSuccess
            // A name outside the Basic Multilingual Plane is attacked as a File only: through a class loader the JDK encodes its
            // surrogates one by one (suspect r9-S14, adversarial/D2/suspects).
            if (name.any { it.isSurrogate() }) continue
            // Out of a class path DIRECTORY (an IDE or a build directory run): a resource is a file: URL.
            val resources = File(root, "res-${name.hashCode()}").apply { File(this, "media").mkdirs() }
            if (runCatching { File(resources, "media/$name").writeBytes(bytes) }.isSuccess) {
                val dirLoader = URLClassLoader(arrayOf(resources.toURI().toURL()), null)
                cleanup += { dirLoader.close() }
                if (dirLoader.getResource("media/$name") != null) {
                    attacked++
                    plays(JavaFxVideoEngine(classLoader = dirLoader, readyTimeoutMs = 15_000L), VideoSource.Asset("media/$name"))
                        ?.let { failures += "Asset '$name' (directory loader): $it" }
                }
            }
            if (!made) continue
            val encoding = URLClassLoader(arrayOf(jar.toURI().toURL()), null)
            @Suppress("DEPRECATION")
            val plain = URLClassLoader(arrayOf(jar.toURL()), null)
            cleanup += { encoding.close(); plain.close() }
            for ((label: String, loader: ClassLoader) in listOf("encoding loader" to encoding, "plain loader" to plain)) {
                if (loader.getResource("media/$name") == null) continue
                attacked++
                plays(JavaFxVideoEngine(classLoader = loader, readyTimeoutMs = 15_000L), VideoSource.Asset("media/$name"))
                    ?.let { failures += "Asset '$name' ($label): $it" }
            }
        }
        assertTrue(attacked >= 20, "too few combinations could be created to mean anything: $attacked")
        assertEquals(emptyList(), failures, "of $attacked combinations")
    }

    private fun assertTrue(condition: Boolean, message: String) = kotlin.test.assertTrue(condition, message)
}
