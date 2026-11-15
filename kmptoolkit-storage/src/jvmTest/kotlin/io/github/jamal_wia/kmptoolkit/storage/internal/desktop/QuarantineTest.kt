package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Plan D-5 and 4.4: no-replace quarantine with a counter suffix, never deleted, restorable. Group `sto-desktop`, phase B (6d). */
class QuarantineTest {
    private val directory: File = Files.createTempDirectory("kmptoolkit-quarantine").toFile()
    private val file: File = File(directory, "s.kmptoolkit.securestorage.bin")
    private val files = createFileAccess()
    private val at: Instant = Instant.parse("2026-11-15T13:04:05Z")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun `the name carries a UTC timestamp and a counter only on collision`() {
        assertEquals("s.kmptoolkit.securestorage.bin.undecryptable-20261115T130405Z", Quarantine.nameOf(file, at, 0).name)
        assertEquals("s.kmptoolkit.securestorage.bin.undecryptable-20261115T130405Z-2", Quarantine.nameOf(file, at, 2).name)
        assertFailsWith<IllegalArgumentException> { Quarantine.nameOf(file, at, -1) }
    }

    @Test
    fun `three quarantines in one second keep all three files and never replace one`() {
        val contents: List<ByteArray> = listOf(byteArrayOf(1), byteArrayOf(2, 2), byteArrayOf(3, 3, 3))
        val moved: List<File> = contents.map { bytes: ByteArray ->
            file.writeBytes(bytes)
            Quarantine.moveAside(files, file, at)
        }
        assertEquals(3, moved.toSet().size, "three different names")
        assertFalse(file.exists(), "the store file is gone")
        for ((index: Int, target: File) in moved.withIndex()) assertContentEquals(contents[index], target.readBytes(), "quarantine $index")
        assertEquals(listOf("", "-1", "-2"), moved.map { it.name.removePrefix("s.kmptoolkit.securestorage.bin.undecryptable-20261115T130405Z") })
    }

    @Test
    fun `restore puts the bytes back byte for byte and refuses to replace a file`() {
        val original: ByteArray = ByteArray(300) { it.toByte() }
        file.writeBytes(original)
        val quarantined: File = Quarantine.moveAside(files, file, at)
        Quarantine.restore(files, quarantined, file)
        assertContentEquals(original, file.readBytes())
        assertFalse(quarantined.exists())

        val other: File = Quarantine.moveAside(files, file, at)
        file.writeBytes(byteArrayOf(9))
        assertFailsWith<IOException> { Quarantine.restore(files, other, file) }
        assertContentEquals(byteArrayOf(9), file.readBytes(), "a file that appeared meanwhile is never overwritten")
        assertTrue(other.exists())
    }

    @Test
    fun `quarantining a file that is not there fails typed and creates nothing`() {
        assertFailsWith<IOException> { Quarantine.moveAside(files, file, at) }
        assertEquals(emptyList(), directory.list()!!.toList())
    }
}
