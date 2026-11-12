package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.io.File
import java.io.FileNotFoundException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Assume

/**
 * Round 7 (hostile sources, no toolkit needed): the URI JavaFX is handed for odd files. A check never opens the
 * file (a named pipe or a device would block), a symlink is followed like the file it names, and every name
 * resolves to a valid URI whose path decodes back to the file.
 */
class JavaFxResolverHostileR7AdversarialTest {

    private lateinit var dir: Path
    private val loader: ClassLoader = javaClass.classLoader

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("kmptoolkit-r7-fxresolver")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `a named pipe and a device file are refused at once`() {
        val pipe = File(dir.toFile(), "pipe.mp4")
        val made: Boolean = runCatching { ProcessBuilder("mkfifo", pipe.path).start().waitFor() == 0 }.getOrDefault(false)
        Assume.assumeTrue("mkfifo is not available here", made && pipe.exists())
        val began: Long = System.nanoTime()
        assertFailsWith<FileNotFoundException> { resolveSourceUri(VideoSource.File(pipe.path), loader) }
        val device = File("/dev/zero")
        if (device.exists()) assertFailsWith<FileNotFoundException> { resolveSourceUri(VideoSource.File(device.path), loader) }
        assertTrue((System.nanoTime() - began) / 1_000_000L < 2_000L, "checking a pipe or a device blocked")
    }

    @Test
    fun `dangling and looping symlinks, over-long and NUL paths are FileNotFound and nothing else`() {
        val dangling: Path = Files.createSymbolicLink(File(dir.toFile(), "dangling.mp4").toPath(), File(dir.toFile(), "gone").toPath())
        val a = File(dir.toFile(), "a.mp4")
        val b = File(dir.toFile(), "b.mp4")
        Files.createSymbolicLink(a.toPath(), b.toPath())
        Files.createSymbolicLink(b.toPath(), a.toPath())
        for (path: String in listOf(dangling.toString(), a.path, File(dir.toFile(), "x".repeat(5_000)).path, File(dir.toFile(), "a\u0000b").path, "\u0000")) {
            assertFailsWith<FileNotFoundException>("path of ${path.length} characters") { resolveSourceUri(VideoSource.File(path), loader) }
        }
    }

    @Test
    fun `every character that means something in a url decodes back to the same file`() {
        for (name: String in listOf("a#b.mp4", "a?b=c&d.mp4", "100%25.mp4", "100%.mp4", "a b  c.mp4", "a;b,c.mp4", "[x].mp4", "ü ß 日本.mp4", "-v.mp4", "a:b.mp4", "a'b\"c.mp4", " leading.mp4", "tab\there.mp4", "a+b.mp4", "a@b.mp4", "a{b}.mp4", "a^b|c.mp4")) {
            val file = File(dir.toFile(), name)
            val created: Boolean = runCatching { file.writeBytes(byteArrayOf(1)); true }.getOrDefault(false)
            if (!created) continue
            val uri: String = resolveSourceUri(VideoSource.File(file.path), loader)
            val parsed: URI = URI(uri)
            assertEquals("file", parsed.scheme, "scheme for '$name'")
            assertEquals(file.canonicalFile.absolutePath.let { File(it).path }, File(parsed).canonicalFile.path, "the file '$name' came back as $uri")
            assertTrue(parsed.fragment == null && parsed.query == null, "'$name' leaked into a query or fragment: $uri")
        }
    }
}
