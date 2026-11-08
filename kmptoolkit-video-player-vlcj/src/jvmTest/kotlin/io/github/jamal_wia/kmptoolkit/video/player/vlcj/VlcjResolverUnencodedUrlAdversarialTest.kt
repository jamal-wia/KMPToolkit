package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.io.File
import java.net.URL
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A class loader is free to hand back any `file:` URL for a resource, and many do not percent-encode
 * it (`File.toURL()`, plugin loaders, hand-built URLs). The asset must resolve to that file whatever
 * the URL's spelling.
 */
class VlcjResolverUnencodedUrlAdversarialTest {

    private val scratch: File = Files.createTempDirectory("vlcj-r4 resolver").toFile()

    @AfterTest
    fun cleanUp() {
        scratch.deleteRecursively()
    }

    private class FixedLoader(private val url: URL) : ClassLoader(null) {
        override fun getResource(name: String): URL = url
    }

    @Test
    fun `an asset whose class loader hands back an unencoded file url with a space resolves to that file`() {
        val dir: File = File(scratch, "my videos").apply { mkdirs() }
        val clip: File = File(dir, "clip.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val unencoded = URL("file:" + clip.absolutePath)

        val media: ResolvedMedia = VlcMediaResolver.resolve(VideoSource.Asset("clip.mp4"), FixedLoader(unencoded))

        assertEquals(clip.absolutePath, media.mrl)
    }

    @Test
    fun `an asset whose class loader hands back an unencoded file url with non ASCII and percent characters resolves to that file`() {
        val dir: File = File(scratch, "vidéos 100%").apply { mkdirs() }
        val clip: File = File(dir, "clip.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val unencoded = URL("file:" + clip.absolutePath)

        val media: ResolvedMedia = VlcMediaResolver.resolve(VideoSource.Asset("clip.mp4"), FixedLoader(unencoded))

        assertEquals(clip.absolutePath, media.mrl)
    }
}
