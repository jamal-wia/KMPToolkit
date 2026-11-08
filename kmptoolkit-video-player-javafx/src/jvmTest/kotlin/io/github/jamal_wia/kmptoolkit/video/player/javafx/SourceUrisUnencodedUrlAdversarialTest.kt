package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A class loader is free to hand back any `file:` URL for a resource, and many do not percent-encode
 * it (`File.toURL()`, plugin loaders, hand-built URLs). JavaFX `Media` needs a valid URI, so the
 * asset must resolve to a URI of that file whatever the URL's spelling.
 */
class SourceUrisUnencodedUrlAdversarialTest {

    private val scratch: File = Files.createTempDirectory("javafx-r4 resolver").toFile()

    @AfterTest
    fun cleanUp() {
        scratch.deleteRecursively()
    }

    private class FixedLoader(private val url: URL) : ClassLoader(null) {
        override fun getResource(name: String): URL = url
    }

    private fun resolveUnencoded(directory: String): Pair<File, String> {
        val dir: File = File(scratch, directory).apply { mkdirs() }
        val clip: File = File(dir, "clip.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        @Suppress("DEPRECATION")
        val unencoded = URL("file:" + clip.absolutePath)
        return clip to resolveSourceUri(VideoSource.Asset("clip.mp4"), FixedLoader(unencoded))
    }

    @Test
    fun `an asset whose class loader hands back an unencoded file url with a space resolves to that file`() {
        val (clip: File, uri: String) = resolveUnencoded("my videos")
        assertEquals(clip.absoluteFile, File(URI(uri)))
    }

    @Test
    fun `an asset whose class loader hands back an unencoded file url with non ASCII and percent characters resolves to that file`() {
        val (clip: File, uri: String) = resolveUnencoded("vidéos 100%")
        assertEquals(clip.absoluteFile, File(URI(uri)))
    }
}
