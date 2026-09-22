package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.io.File
import java.io.FileNotFoundException
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Source resolution, which needs no VLC: every failure the library can detect before handing a
 * source to libvlc must surface with a precise type, and an asset must be openable whether it sits
 * in a build directory or inside a jar.
 */
class VlcMediaResolverTest {

    private val loader: ClassLoader = VlcMediaResolverTest::class.java.classLoader
    private val scratch: File = Files.createTempDirectory("vlcj-resolver-test").toFile()

    @AfterTest
    fun cleanUp() {
        scratch.deleteRecursively()
    }

    @Test
    fun `an existing file resolves to its absolute path as a local source`() {
        val file = File(scratch, "clip.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val media: ResolvedMedia = VlcMediaResolver.resolve(VideoSource.File(file.path), loader)

        assertEquals(file.absolutePath, media.mrl)
        assertTrue(media.isLocal)
        assertTrue(media.options.isEmpty())
        assertNull(media.temporaryFile)
    }

    @Test
    fun `a missing file fails with FileNotFoundException`() {
        assertFailsWith<FileNotFoundException> {
            VlcMediaResolver.resolve(VideoSource.File(File(scratch, "absent.mp4").path), loader)
        }
    }

    @Test
    fun `an empty file path fails with FileNotFoundException`() {
        assertFailsWith<FileNotFoundException> { VlcMediaResolver.resolve(VideoSource.File(""), loader) }
    }

    @Test
    fun `a directory is not a playable file`() {
        assertFailsWith<FileNotFoundException> { VlcMediaResolver.resolve(VideoSource.File(scratch.path), loader) }
    }

    @Test
    fun `an asset in a build directory is opened in place`() {
        val media: ResolvedMedia = VlcMediaResolver.resolve(VideoSource.Asset(TestClip.ASSET_PATH), loader)

        assertTrue(File(media.mrl).isFile)
        assertTrue(media.isLocal)
        assertNull(media.temporaryFile, "a file on disk must not be copied")
    }

    @Test
    fun `a leading slash on an asset path is tolerated`() {
        val media: ResolvedMedia = VlcMediaResolver.resolve(VideoSource.Asset("/" + TestClip.ASSET_PATH), loader)

        assertTrue(File(media.mrl).isFile)
    }

    @Test
    fun `an asset inside a jar is extracted to a temporary file that discard deletes`() {
        val content = byteArrayOf(9, 8, 7, 6)
        val jar = File(scratch, "assets.jar")
        JarOutputStream(jar.outputStream()).use { out ->
            out.putNextEntry(JarEntry("media/intro.mp4"))
            out.write(content)
            out.closeEntry()
        }
        URLClassLoader(arrayOf(jar.toURI().toURL()), null).use { jarLoader ->
            val media: ResolvedMedia = VlcMediaResolver.resolve(VideoSource.Asset("media/intro.mp4"), jarLoader)

            val extracted: File = assertNotNull(media.temporaryFile)
            assertEquals(extracted.absolutePath, media.mrl)
            assertTrue(extracted.name.endsWith(".mp4"), "VLC picks a demuxer by extension too")
            assertTrue(content.contentEquals(extracted.readBytes()))

            media.discard()
            assertFalse(extracted.exists())
        }
    }

    @Test
    fun `a missing asset fails with FileNotFoundException`() {
        assertFailsWith<FileNotFoundException> {
            VlcMediaResolver.resolve(VideoSource.Asset("video/absent.mp4"), loader)
        }
    }

    @Test
    fun `an empty asset path fails with FileNotFoundException`() {
        assertFailsWith<FileNotFoundException> { VlcMediaResolver.resolve(VideoSource.Asset(""), loader) }
    }

    @Test
    fun `a remote source keeps its url and is not local`() {
        val media: ResolvedMedia = VlcMediaResolver.resolve(VideoSource.Remote("https://example.test/a.mp4"), loader)

        assertEquals("https://example.test/a.mp4", media.mrl)
        assertFalse(media.isLocal)
        assertTrue(media.options.isEmpty())
    }

    @Test
    fun `a blank url fails with IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> { VlcMediaResolver.resolve(VideoSource.Remote(" "), loader) }
    }

    @Test
    fun `User-Agent and Referer become VLC options whatever their case`() {
        val options: List<String> = VlcMediaResolver.headerOptions(
            mapOf("user-agent" to "Toolkit/1.0", "REFERER" to "https://app.test/"),
        )

        assertEquals(listOf(":http-user-agent=Toolkit/1.0", ":http-referrer=https://app.test/"), options)
    }

    @Test
    fun `a header VLC cannot send is rejected rather than silently dropped`() {
        val error: IllegalArgumentException = assertFailsWith {
            VlcMediaResolver.resolve(
                VideoSource.Remote("https://example.test/a.mp4", mapOf("Authorization" to "Bearer x")),
                loader,
            )
        }
        assertTrue("Authorization" in error.message.orEmpty())
    }

    @Test
    fun `a header value with a line break is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            VlcMediaResolver.headerOptions(mapOf("User-Agent" to "a\n:sout=#file"))
        }
    }
}
