package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.io.File
import java.io.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Assume

/**
 * Round 5, contract lawyer: what 04-api-reference and 03-guide of the VLC engine say about how a source
 * is checked before VLC is involved, asserted over the inputs the earlier rounds did not use.
 */
class VlcResolverContractR5AdversarialTest {

    private val loader: ClassLoader = javaClass.classLoader

    @Test
    fun `an unreadable file fails with FileNotFoundException and a readable one does not`() {
        val file: File = File.createTempFile("kmptoolkit-unreadable", ".mp4").also { it.deleteOnExit() }
        // path-hygiene-ignore: PH2 guarded: the next line assumes the file is no longer readable, so a host without POSIX modes (Windows) skips the test
        file.setReadable(false)
        Assume.assumeFalse("the file is still readable here (root, or a platform without POSIX modes)", file.canRead())
        assertFailsWith<FileNotFoundException> { VlcMediaResolver.resolve(VideoSource.File(file.absolutePath), loader) }
        // path-hygiene-ignore: PH2 guarded: only reached after the assumption above held, so the POSIX modes were honoured; this restores the read bit
        file.setReadable(true)
        assertEquals(file.absolutePath, VlcMediaResolver.resolve(VideoSource.File(file.absolutePath), loader).mrl)
    }

    @Test
    fun `a leading slash or several on an asset path are ignored and a bare slash is blank`() {
        val plain: ResolvedMedia = VlcMediaResolver.resolve(VideoSource.Asset("video"), loader)
        assertEquals(plain.mrl, VlcMediaResolver.resolve(VideoSource.Asset("/video"), loader).mrl)
        assertEquals(plain.mrl, VlcMediaResolver.resolve(VideoSource.Asset("//video"), loader).mrl)
        assertFailsWith<FileNotFoundException> { VlcMediaResolver.resolve(VideoSource.Asset("/"), loader) }
        assertFailsWith<FileNotFoundException> { VlcMediaResolver.resolve(VideoSource.Asset("///"), loader) }
    }

    @Test
    fun `a backslash or a parent step does not reach outside the class path`() {
        assertFailsWith<FileNotFoundException> { VlcMediaResolver.resolve(VideoSource.Asset("\\video"), loader) }
        assertFailsWith<FileNotFoundException> { VlcMediaResolver.resolve(VideoSource.Asset("../"), loader) }
        assertFailsWith<FileNotFoundException> { VlcMediaResolver.resolve(VideoSource.Asset("../../../../../../etc/hosts"), loader) }
    }

    @Test
    fun `headers whose names differ only by case are all sent in the order given and a padded name is refused`() {
        val options: List<String> = VlcMediaResolver.headerOptions(linkedMapOf("user-agent" to "a", "USER-AGENT" to "b", "Referer" to "r"))
        assertEquals(listOf(":http-user-agent=a", ":http-user-agent=b", ":http-referrer=r"), options)
        assertFailsWith<IllegalArgumentException> { VlcMediaResolver.headerOptions(mapOf("User-Agent " to "x")) }
        assertFailsWith<IllegalArgumentException> { VlcMediaResolver.headerOptions(mapOf("User-Agent\r\n:sout" to "x")) }
        assertFailsWith<IllegalArgumentException> { VlcMediaResolver.headerOptions(mapOf("User-Agent" to "a\rb")) }
    }

    @Test
    fun `a remote source is never local and keeps its url and a source with a blank url is refused`() {
        for (url: String in listOf("file:///tmp/x.mp4", "rtsp://h/x", "HTTP://h/x y.mp4")) {
            val media: ResolvedMedia = VlcMediaResolver.resolve(VideoSource.Remote(url), loader)
            assertEquals(url, media.mrl)
            assertTrue(!media.isLocal)
        }
        assertFailsWith<IllegalArgumentException> { VlcMediaResolver.resolve(VideoSource.Remote(" \t"), loader) }
    }
}
