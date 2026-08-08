package io.github.jamal_wia.kmptoolkit.platform.files

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PickedFileTest {

    private fun file(
        name: String = "a.pdf",
        mimeTypeHint: String = "application/pdf",
        bytes: ByteArray = byteArrayOf(1, 2, 3),
    ) = PickedFile(name, mimeTypeHint, bytes)

    @Test
    fun `two files with the same content are equal`() {
        assertEquals(file(), file())
        assertEquals(file().hashCode(), file().hashCode())
    }

    @Test
    fun `files differing only in bytes are not equal`() {
        assertFalse(file(bytes = byteArrayOf(1)) == file(bytes = byteArrayOf(2)))
    }

    @Test
    fun `files differing only in name are not equal`() {
        assertFalse(file(name = "a.pdf") == file(name = "b.pdf"))
    }

    @Test
    fun `files differing only in mime type hint are not equal`() {
        assertFalse(file(mimeTypeHint = "application/pdf") == file(mimeTypeHint = "image/png"))
    }

    @Test
    fun `an empty file is equal to another empty file`() {
        assertEquals(file(bytes = ByteArray(0)), file(bytes = ByteArray(0)))
    }

    @Test
    fun `size reports the byte count`() {
        assertEquals(3, file().sizeBytes)
        assertEquals(0, file(bytes = ByteArray(0)).sizeBytes)
    }

    @Test
    fun `toString reports the metadata but never the content`() {
        val text: String = file(bytes = byteArrayOf(72, 73)).toString()

        assertTrue("a.pdf" in text, text)
        assertTrue("sizeBytes=2" in text, text)
        assertFalse("72" in text, "toString must not leak file content: $text")
    }
}

class FilePickerConfigTest {

    @Test
    fun `defaults to a 25 MiB cap`() {
        assertEquals(25L * 1024 * 1024, FilePickerConfig().maxBytes)
    }

    @Test
    fun `rejects a zero cap`() {
        assertFailsWith<IllegalArgumentException> { FilePickerConfig(maxBytes = 0) }
    }

    @Test
    fun `rejects a negative cap`() {
        assertFailsWith<IllegalArgumentException> { FilePickerConfig(maxBytes = -1) }
    }
}
