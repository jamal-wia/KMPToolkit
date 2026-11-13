package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import java.io.File
import java.io.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import org.junit.Assume

/**
 * SUSPECT r5-D2-S3. The VLC engine documents (04-api-reference) that a [VideoSource.File] that is
 * unreadable fails with FileNotFoundException before the engine is involved, and the JavaFX guide gives
 * FileNotFoundException for a File that is not there or is a directory. Measured on the real toolkit:
 * an unreadable file was handed to JavaFX, which failed it with a MediaException after 4 s.
 * (S4, an Asset naming a directory, was rejected: see the round-5 fixer report.)
 */
class SourceUrisUnreadableFileR5Test {

    private val loader: ClassLoader = javaClass.classLoader

    @Test
    fun `an unreadable file fails with FileNotFoundException before JavaFX is involved`() {
        val file: File = File.createTempFile("kmptoolkit-unreadable", ".mp4").also { it.deleteOnExit() }
        file.writeBytes(TestClip.copyToTempFile().readBytes())
        // path-hygiene-ignore: PH2 guarded: the next line assumes the file is no longer readable, so a host without POSIX modes (Windows) skips the test
        file.setReadable(false)
        Assume.assumeFalse("the file is still readable here (root, or a platform without POSIX modes)", file.canRead())
        assertFailsWith<FileNotFoundException> { resolveSourceUri(VideoSource.File(file.absolutePath), loader) }
    }
}
