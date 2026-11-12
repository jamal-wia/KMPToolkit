package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 9 (hostile media): `VideoSource.Asset` names that carry characters with a meaning in a URL, a character outside the
 * Basic Multilingual Plane, a decomposed letter and an unusual extension, resolved through a class path directory and through a
 * jar, for a class loader that encodes its URLs and one that does not. What the resolver hands to libvlc must be a file
 * that holds the asset's bytes, whatever the name; a jar copy keeps the extension and is deleted by `discard()`.
 */
class VlcjNonBmpNameAndLongExtensionR9AdversarialTest {

    private val names: List<String> = listOf(
        "a#b.mp4", "a?b.mp4", "100%25.mp4", "%41.mp4", "a+b.mp4", "a&b=c.mp4", "a;b.mp4", "[x].mp4", "{y}.mp4", "a b  c.mp4",
        "😀 emoji.mp4", "été.mp4", "été.mp4", "UPPER.MP4", "no-extension-clip", "dots.in.name.v2.mp4",
        "日本語.mp4", "𠀋.mp4",
    )

    @Test
    fun `an asset with an odd name resolves to a file with the asset's bytes from a directory and from a jar`() {
        val root: File = Files.createTempDirectory("kmptoolkit-r9-vlc-names").toFile()
        try {
            val bytes = ByteArray(2_048) { (it * 31 + 7).toByte() }
            val failures: MutableList<String> = mutableListOf()
            var attacked = 0
            for (name: String in names) {
                val dir = File(root, "res-${name.hashCode()}").apply { File(this, "media").mkdirs() }
                val jar = File(root, "jar-${name.hashCode()}.jar")
                val inDir: Boolean = runCatching { File(dir, "media/$name").writeBytes(bytes) }.isSuccess
                val inJar: Boolean = runCatching {
                    JarOutputStream(jar.outputStream()).use { o -> o.putNextEntry(JarEntry("media/$name")); o.write(bytes); o.closeEntry() }
                }.isSuccess
                val loaders: MutableList<Pair<String, ClassLoader>> = mutableListOf()
                if (inDir) loaders += "directory" to URLClassLoader(arrayOf(dir.toURI().toURL()), null)
                @Suppress("DEPRECATION")
                if (inDir) loaders += "plain directory" to URLClassLoader(arrayOf(dir.toURL()), null)
                if (inJar) loaders += "jar" to URLClassLoader(arrayOf(jar.toURI().toURL()), null)
                @Suppress("DEPRECATION")
                if (inJar) loaders += "plain jar" to URLClassLoader(arrayOf(jar.toURL()), null)
                for ((label: String, loader: ClassLoader) in loaders) {
                    if (loader.getResource("media/$name") == null) continue
                    attacked++
                    try {
                        val media: ResolvedMedia = VlcMediaResolver.resolve(VideoSource.Asset("media/$name"), loader)
                        try {
                            val file = File(media.mrl)
                            if (!file.isFile) failures += "'$name' ($label): the locator ${media.mrl} is not a file"
                            else if (!file.readBytes().contentEquals(bytes)) failures += "'$name' ($label): the file differs from the asset"
                            if (media.temporaryFile != null && !file.name.endsWith(name.substringAfterLast('.', "").let { if (it.isEmpty() || it == name) ".tmp" else ".$it" })) {
                                failures += "'$name' ($label): the copy ${file.name} lost the extension"
                            }
                        } finally {
                            media.discard()
                        }
                    } catch (t: Throwable) {
                        failures += "'$name' ($label): ${t::class.java.simpleName}: ${t.message?.take(120)}"
                    } finally {
                        (loader as URLClassLoader).close()
                    }
                }
            }
            assertTrue(attacked >= 40, "too few combinations to mean anything: $attacked")
            assertEquals(emptyList(), failures, "of $attacked combinations")
            assertEquals(0, AssetCopies.liveCount, "copies left behind")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a jar asset with a very long extension is copied or refused with FileNotFound and never with another exception`() {
        val root: File = Files.createTempDirectory("kmptoolkit-r9-vlc-ext").toFile()
        try {
            val bytes = ByteArray(64) { it.toByte() }
            val jar = File(root, "long.jar")
            val entry = "media/clip." + "x".repeat(300)
            JarOutputStream(jar.outputStream()).use { o -> o.putNextEntry(JarEntry(entry)); o.write(bytes); o.closeEntry() }
            URLClassLoader(arrayOf(jar.toURI().toURL()), null).use { loader ->
                val media: ResolvedMedia = VlcMediaResolver.resolve(VideoSource.Asset(entry), loader)
                try {
                    assertContentEquals(bytes, File(media.mrl).readBytes())
                } finally {
                    media.discard()
                }
            }
            assertEquals(0, AssetCopies.liveCount)
        } finally {
            root.deleteRecursively()
        }
    }
}
