package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.files.internal.classifyLinkFailure
import java.nio.file.AccessDeniedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileStore
import java.nio.file.FileSystemException
import java.nio.file.attribute.FileAttributeView
import java.nio.file.attribute.FileStoreAttributeView
import kotlin.test.Test
import kotlin.test.assertEquals

/** M2/Q2: a failed createLink is classified by type and file store, never by localized message text. */
class LinkClassificationTest {
    private class Store(private val kind: String) : FileStore() {
        override fun name(): String = kind
        override fun type(): String = kind
        override fun isReadOnly(): Boolean = false
        override fun getTotalSpace(): Long = 0L
        override fun getUsableSpace(): Long = 0L
        override fun getUnallocatedSpace(): Long = 0L
        override fun supportsFileAttributeView(type: Class<out FileAttributeView>?): Boolean = false
        override fun supportsFileAttributeView(name: String?): Boolean = false
        override fun <V : FileStoreAttributeView?> getFileStoreAttributeView(type: Class<V>?): V? = null
        override fun getAttribute(attribute: String?): Any? = null
    }

    private val german: FileSystemException = FileSystemException("a", "b", "Unzul\u00e4ssige Funktion.")

    @Test
    fun `an untyped failure between two different stores is CrossDevice whatever the language`() {
        assertEquals(FileError.CrossDevice("/n"), classifyLinkFailure(german, "/n", { Store("a") }, { Store("b") }))
    }

    @Test
    fun `an untyped failure inside one non-FAT store falls through to Io`() {
        val ntfs: Store = Store("ntfs")
        assertEquals(true, classifyLinkFailure(german, "/n", { ntfs }, { ntfs }) is FileError.Io)
    }

    @Test
    fun `an untyped failure on a FAT family store is Unsupported whatever the language`() {
        val fat: Store = Store("FAT32")
        assertEquals(FileError.Unsupported("/n"), classifyLinkFailure(german, "/n", { fat }, { fat }))
        val exfat: Store = Store("exFAT")
        assertEquals(FileError.Unsupported("/n"), classifyLinkFailure(german, "/n", { exfat }, { exfat }))
    }

    @Test
    fun `a store lookup that fails is ignored and the text mapping is the last resort`() {
        val failing: () -> FileStore? = { throw java.io.IOException("no store") }
        assertEquals(FileError.Unsupported("/n"), classifyLinkFailure(FileSystemException("a", "b", "Operation not supported"), "/n", failing, failing))
        assertEquals(true, classifyLinkFailure(german, "/n", failing, failing) is FileError.Io)
    }

    @Test
    fun `typed exceptions win over every probe`() {
        val a: Store = Store("a")
        val b: Store = Store("b")
        assertEquals(FileError.AlreadyExists("/n"), classifyLinkFailure(FileAlreadyExistsException("x"), "/n", { a }, { b }))
        assertEquals(FileError.AccessDenied("/n"), classifyLinkFailure(AccessDeniedException("x"), "/n", { a }, { b }))
        assertEquals(FileError.Unsupported("/n"), classifyLinkFailure(UnsupportedOperationException(), "/n", { a }, { b }))
    }
}
