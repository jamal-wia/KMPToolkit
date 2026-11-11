package io.github.jamal_wia.kmptoolkit.downloader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Round 7 differential contract for the temp-file layout: units whose ids sit on the edge of what a file
 * name can hold (counted in UTF-8 bytes, not characters) take one path on both shipped storages and each
 * runs begin, write, complete, commit and delete without touching another unit's files. Derived from the
 * documented promise that any id is accepted and stored under a hashed name when it is not a plain file name.
 */
abstract class TempLayoutRound7Contract {

    protected abstract fun newStorage(): DownloaderStorage
    protected abstract fun writeFile(path: String, bytes: ByteArray)
    protected abstract fun readFile(path: String): ByteArray?
    protected abstract fun deleteTestDirectory()

    @AfterTest
    fun cleanUp() {
        deleteTestDirectory()
        LegacyTempMigrations.resetForTests()
    }

    private class IdUnit(override val id: String, private val number: Int) : DownloadUnit {
        override val apiPath: String = "/files/$number"
        override val relativePath: String = "files/r7-$number.bin"
        override val group: ResourceGroup = object : ResourceGroup {
            override val key: String = "g$number"
            override val units: List<DownloadUnit> get() = listOf(this@IdUnit)
        }

        override fun toString(): String = "IdUnit(#$number, ${id.length} chars)"
    }

    /** Ids around the 255-byte file-name limit, in 1-, 2-, 3- and 4-byte characters. */
    private fun edgeIds(): List<String> {
        val ids: MutableList<String> = mutableListOf()
        for (count: Int in 240..256) ids += "a".repeat(count)
        for (count: Int in 118..128) ids += "é".repeat(count)
        for (count: Int in 78..86) ids += "あ".repeat(count)
        for (count: Int in 58..64) ids += "😀".repeat(count)
        return ids
    }

    @Test
    fun `ids on the file name limit in every character width run the whole temp lifecycle`() = runTest {
        val storage: DownloaderStorage = newStorage()
        edgeIds().forEachIndexed { number: Int, id: String ->
            val unit = IdUnit(id, number)
            val bytes: ByteArray = ByteArray(64) { (number + it).toByte() }
            storage.beginTempFile(unit, null)
            writeFile(storage.getTempFilePath(unit), bytes)
            storage.markTempFileComplete(unit)
            assertEquals(TempFileState.Complete, storage.tempFileState(unit), "$unit")
            storage.commitResource(unit)
            assertTrue(storage.isResourceAvailable(unit), "$unit")
            assertContentEquals(bytes, readFile(storage.getResourcePath(unit)), "$unit")
            assertEquals(TempFileState.None, storage.tempFileState(unit), "$unit")
            storage.deleteResource(unit)
        }
    }

    @Test
    fun `units whose ids differ only in a lone surrogate or the character it turns into never share a temp file`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val ids: List<String> = listOf("x\uD800", "x\uD801", "x\uDFFF", "x?", "x�")
        val units: List<IdUnit> = ids.mapIndexed { number: Int, id: String -> IdUnit(id, number) }
        val paths: List<String> = units.map { storage.getTempFilePath(it) }
        assertEquals(paths.size, paths.toSet().size, "two ids share one temp path: $paths")

        units.forEachIndexed { number: Int, unit: IdUnit ->
            storage.beginTempFile(unit, null)
            writeFile(storage.getTempFilePath(unit), ByteArray(10) { number.toByte() })
        }
        units.forEachIndexed { number: Int, unit: IdUnit ->
            assertContentEquals(ByteArray(10) { number.toByte() }, readFile(storage.getTempFilePath(unit)), "$unit")
        }
        units.forEach { storage.deleteTempFile(it) }
        units.forEach { assertEquals(TempFileState.None, storage.tempFileState(it), "$it") }
    }
}
