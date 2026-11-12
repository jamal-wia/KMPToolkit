package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.net.URLConnection
import java.net.URLStreamHandler
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume

/**
 * Round 7 (hostile sources and a hostile clock) for the VLC engine, over the real resolver and the engine on the
 * fake libvlc: symlinks, a named pipe and a device file (which must not block a check), names with every
 * character that has a meaning in a URL, a path too long for the system, a classpath asset whose stream fails
 * half way, and the seek hold across an injected `nanoTime` that starts anywhere in the `Long` range.
 */
@OptIn(ToolkitInternalApi::class)
class VlcjHostileSourcesR7AdversarialTest {

    private lateinit var dir: Path
    private val loader: ClassLoader = javaClass.classLoader
    private val runtimes: MutableList<FakeVlcRuntime> = mutableListOf()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("kmptoolkit-r7-vlc")
    }

    @AfterTest
    fun tearDown() {
        dir.toFile().deleteRecursively()
        runtimes.forEach { it.shutdown() }
    }

    @Test
    fun `a symlink to a clip resolves to the link path and a dangling one or a loop is FileNotFound`() {
        val real = File(dir.toFile(), "real.mp4").apply { writeBytes(TestClip.file.readBytes()) }
        val link: Path = Files.createSymbolicLink(File(dir.toFile(), "link.mp4").toPath(), real.toPath())
        val chain: Path = Files.createSymbolicLink(File(dir.toFile(), "chain.mp4").toPath(), link)
        assertEquals(link.toFile().absolutePath, VlcMediaResolver.resolve(VideoSource.File(link.toString()), loader).mrl)
        assertEquals(chain.toFile().absolutePath, VlcMediaResolver.resolve(VideoSource.File(chain.toString()), loader).mrl)
        val dangling: Path = Files.createSymbolicLink(File(dir.toFile(), "dangling.mp4").toPath(), File(dir.toFile(), "gone.mp4").toPath())
        assertFailsWith<FileNotFoundException> { VlcMediaResolver.resolve(VideoSource.File(dangling.toString()), loader) }
        val a = File(dir.toFile(), "a.mp4")
        val b = File(dir.toFile(), "b.mp4")
        Files.createSymbolicLink(a.toPath(), b.toPath())
        Files.createSymbolicLink(b.toPath(), a.toPath())
        assertFailsWith<FileNotFoundException> { VlcMediaResolver.resolve(VideoSource.File(a.path), loader) }
        // A symlink to a directory is a directory.
        val dirLink: Path = Files.createSymbolicLink(File(dir.toFile(), "dir-link.mp4").toPath(), dir)
        assertFailsWith<FileNotFoundException> { VlcMediaResolver.resolve(VideoSource.File(dirLink.toString()), loader) }
    }

    @Test
    fun `a named pipe and a device file are refused at once instead of being opened`() {
        val pipe = File(dir.toFile(), "pipe.mp4")
        val made: Boolean = runCatching { ProcessBuilder("mkfifo", pipe.path).start().waitFor() == 0 }.getOrDefault(false)
        Assume.assumeTrue("mkfifo is not available here", made && pipe.exists())
        val began: Long = System.nanoTime()
        assertFailsWith<FileNotFoundException> { VlcMediaResolver.resolve(VideoSource.File(pipe.path), loader) }
        val device = File("/dev/zero")
        if (device.exists()) assertFailsWith<FileNotFoundException> { VlcMediaResolver.resolve(VideoSource.File(device.path), loader) }
        assertTrue((System.nanoTime() - began) / 1_000_000L < 2_000L, "checking a pipe or a device blocked")
    }

    @Test
    fun `names with every character that means something in a url are passed to libvlc as the plain path`() {
        for (name: String in listOf("a#b.mp4", "a?b=c&d.mp4", "100%25.mp4", "100%.mp4", "a b  c.mp4", "a;b,c.mp4", "[x].mp4", "ü ß 日本.mp4", "-v.mp4", "a:b.mp4", "a'b\"c.mp4", " leading.mp4", "tab\there.mp4")) {
            val file = File(dir.toFile(), name)
            val created: Boolean = runCatching { file.writeBytes(byteArrayOf(1)); true }.getOrDefault(false)
            if (!created) continue
            val media: ResolvedMedia = VlcMediaResolver.resolve(VideoSource.File(file.path), loader)
            assertEquals(file.absolutePath, media.mrl, "the locator for '$name'")
            assertTrue(media.isLocal)
            assertEquals(emptyList(), media.options)
        }
    }

    @Test
    fun `a path too long for the system or with a NUL is FileNotFound and never another exception`() {
        for (path: String in listOf(File(dir.toFile(), "x".repeat(5_000)).path, File(dir.toFile(), "a\u0000b.mp4").path, "\u0000", "   ", File(dir.toFile(), "a".repeat(300) + ".mp4").path)) {
            assertFailsWith<FileNotFoundException>("path of ${path.length} characters") { VlcMediaResolver.resolve(VideoSource.File(path), loader) }
        }
    }

    @Test
    fun `an asset whose stream fails half way leaves no copy and reports the stream's own failure`() {
        val before: Int = AssetCopies.liveCount
        val handler = object : URLStreamHandler() {
            override fun openConnection(u: URL): URLConnection = object : URLConnection(u) {
                override fun connect() = Unit
                override fun getInputStream(): InputStream = object : InputStream() {
                    private var left: Int = 4_096
                    override fun read(): Int = if (left-- > 0) 7 else throw IOException("the stream broke half way")
                }
            }
        }
        val failing = object : ClassLoader(null) {
            override fun getResource(name: String): URL? = URL("weird", null, -1, "/$name", handler)
        }
        repeat(20) {
            val error: IOException = assertFailsWith<IOException> { VlcMediaResolver.resolve(VideoSource.Asset("clip.mp4"), failing) }
            assertEquals("the stream broke half way", error.message)
        }
        assertEquals(before, AssetCopies.liveCount, "a copy of a broken stream stayed registered")
        val leftovers: List<String> = File(System.getProperty("java.io.tmpdir")).list { _, n -> n.startsWith("kmptoolkit-video-") }.orEmpty().toList()
        assertEquals(emptyList(), leftovers.filter { name -> File(System.getProperty("java.io.tmpdir"), name).length() in 1L..8_192L }, "a half copy stayed on disk")
    }

    @Test
    fun `an asset with an empty stream is a copy of zero bytes that is discarded with the media`() {
        val handler = object : URLStreamHandler() {
            override fun openConnection(u: URL): URLConnection = object : URLConnection(u) {
                override fun connect() = Unit
                override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
            }
        }
        val empty = object : ClassLoader(null) {
            override fun getResource(name: String): URL? = URL("weird", null, -1, "/$name", handler)
        }
        val before: Int = AssetCopies.liveCount
        val media: ResolvedMedia = VlcMediaResolver.resolve(VideoSource.Asset("empty.mp4"), empty)
        assertEquals(before + 1, AssetCopies.liveCount)
        assertEquals(0L, File(media.mrl).length())
        media.discard()
        media.discard()
        assertEquals(before, AssetCopies.liveCount)
        assertTrue(!File(media.mrl).exists())
    }

    private fun clockCase(start: Long) {
        val rt = FakeVlcRuntime().also { runtimes += it }
        val clock = AtomicLong(start)
        val engine = VlcjVideoEngine(vlcArgs = emptyList(), discover = { true }, createRuntime = { rt }, nanoTime = { clock.get() })
        runBlocking { withTimeout(5_000L) { engine.load(VideoSource.File(TestClip.file.path)) } }
        engine.start()
        rt.player.fire { playing() }
        rt.drainTasks()
        engine.seekTo(1_500L)
        // libvlc applies set_time asynchronously: its clock still reads the old time for a moment.
        rt.player.playhead = 300L
        assertEquals(1_500L, engine.positionMs(), "clock at $start: the target stands right after the seek")
        clock.addAndGet(499_000_000L)
        assertEquals(1_500L, engine.positionMs(), "clock at $start: the target stands for 499 ms of the 500 ms hold")
        clock.addAndGet(2_000_000L)
        assertEquals(300L, engine.positionMs(), "clock at $start: after the hold libvlc's own reading is the playhead")
        engine.release()
        engine.dispose()
    }

    @Test
    fun `the seek hold lasts as long whatever the nanoTime origin, across the wrap of the clock included`() {
        for (start: Long in listOf(0L, 1L, 7_000_000_000L, -10_000_000_000L, Long.MAX_VALUE - 100_000_000L, Long.MAX_VALUE - 250_000_000L, Long.MIN_VALUE + 10_000_000L, Long.MIN_VALUE)) {
            clockCase(start)
        }
    }
}
