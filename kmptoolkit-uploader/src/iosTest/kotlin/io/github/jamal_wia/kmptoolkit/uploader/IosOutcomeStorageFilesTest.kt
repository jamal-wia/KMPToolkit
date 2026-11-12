package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.files.Durability
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileProtection
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSNumber
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.writeToFile

/**
 * The iOS journal writes its entries through `kmptoolkit-files`: which options it asks for, where the temporary files
 * go, that leftovers there are swept, and that the entries stay plain files a 4.x build reads and writes. Also the
 * journal directory name of [BackgroundUploadConfig].
 */
@OptIn(ExperimentalForeignApi::class)
class IosOutcomeStorageFilesTest {

    private val root: String = NSTemporaryDirectory() + "/kmptoolkit-ios-files-" + NSUUID().UUIDString()
    private val dir: String = "$root/outcomes"
    private val partial: String = "$root/outcomes-partial"
    private val manager: NSFileManager = NSFileManager.defaultManager

    @AfterTest
    fun cleanUp() {
        manager.removeItemAtPath(root, error = null)
    }

    private class RecordingFiles(private val real: FileAccess) : FileAccess by real {
        val writes: MutableList<Pair<String, WriteOptions>> = mutableListOf()

        override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> {
            writes += path to options
            return real.writeAtomically(path, bytes, options)
        }
    }

    private fun names(path: String): List<String> = manager.contentsOfDirectoryAtPath(path, error = null).orEmpty().filterIsInstance<String>()

    private fun setAge(path: String, seconds: Double) {
        manager.setAttributes(mapOf(NSFileModificationDate to NSDate.dateWithTimeIntervalSinceNow(-seconds)), ofItemAtPath = path, error = null)
    }

    @Test
    fun `an entry is written synced with the first-unlock class through a temporary file in the sibling directory`() {
        val recording = RecordingFiles(createFileAccess())
        val storage = IosOutcomeStorage(dir, files = recording)

        assertTrue(storage.write("item-1", byteArrayOf(1, 2, 3)))

        val (path: String, options: WriteOptions) = recording.writes.single()
        assertEquals("$dir/item-1", path)
        assertEquals(Durability.Synced, options.durability)
        assertEquals(FileProtection.CompleteUntilFirstUserAuthentication, options.protection)
        assertEquals(partial, options.tempDirectory)
    }

    @Test
    fun `a finished write leaves only the entry and no temporary file`() {
        val storage = IosOutcomeStorage(dir)
        assertTrue(storage.write("item-1", byteArrayOf(1)))
        assertTrue(storage.write("item-1", byteArrayOf(2)))

        assertEquals(listOf("item-1"), names(dir))
        assertEquals(emptyList(), names(partial))
        assertContentEquals(byteArrayOf(2), storage.read("item-1"))
    }

    @Test
    fun `an entry is a plain file that Foundation reads and a 4x entry written by Foundation is read`() {
        val storage = IosOutcomeStorage(dir)
        assertTrue(storage.write("item-1", byteArrayOf(7, 8, 9)))
        val data: NSData? = NSData.create(contentsOfFile = "$dir/item-1")
        assertNotNull(data)
        assertEquals(3, data.length.toInt())

        val raw: ByteArray = byteArrayOf(4, 5, 6)
        raw.usePinned { NSData.create(bytes = it.addressOf(0), length = raw.size.convert()).writeToFile("$dir/item-4x", atomically = true) }
        assertContentEquals(raw, storage.read("item-4x"))
        assertEquals(listOf("item-1", "item-4x"), storage.itemIds())
    }

    @Test
    fun `old temporary files in the sibling directory are swept and young ones kept`() {
        val storage = IosOutcomeStorage(dir)
        assertTrue(storage.write("item-1", byteArrayOf(1)))
        for (name in listOf("old-leftover", ".kmptoolkit-123.tmp", "young-leftover")) {
            byteArrayOf(1).usePinned { NSData.create(bytes = it.addressOf(0), length = 1.convert()).writeToFile("$partial/$name", atomically = false) }
        }
        setAge("$partial/old-leftover", 3_600.0)
        setAge("$partial/.kmptoolkit-123.tmp", 3_600.0)

        storage.purgeLeftovers()

        assertEquals(listOf("young-leftover"), names(partial))
        assertEquals(listOf("item-1"), names(dir), "an entry is never swept")
    }

    @Test
    fun `every name written is ASCII and each id reads back its own bytes for NFC NFD emoji long accented and upper-case ids`() {
        val ids: List<String> = listOf(
            "caf\u00e9", "cafe\u0301", "\uD83D\uDE00-id", "\u00e9".repeat(200), "Photo-AbC",
        )
        val storage = IosOutcomeStorage(dir)
        for ((index, id) in ids.withIndex()) assertTrue(storage.write(id, byteArrayOf(index.toByte(), 42)), "write of id #$index")

        for (directory in listOf(dir, partial)) {
            for (name in names(directory)) assertTrue(name.all { it.code < 0x80 }, "non-ASCII name '$name' in $directory")
        }
        for ((index, id) in ids.withIndex()) assertContentEquals(byteArrayOf(index.toByte(), 42), storage.read(id), "id #$index reads its own bytes")
    }

    @Test
    fun `a write whose temporary directory cannot be made reports it and leaves the earlier entry`() {
        val storage = IosOutcomeStorage(dir)
        assertTrue(storage.write("item-1", byteArrayOf(1)))
        // A regular file where the sibling directory should be: the entry directory is fine, the temporary one is not.
        manager.removeItemAtPath(partial, error = null)
        byteArrayOf(9).usePinned { NSData.create(bytes = it.addressOf(0), length = 1.convert()).writeToFile(partial, atomically = false) }

        assertFalse(storage.write("item-2", byteArrayOf(2)))

        assertEquals(null, storage.read("item-2"))
        assertContentEquals(byteArrayOf(1), storage.read("item-1"))
        assertEquals(listOf("item-1"), names(dir))
    }

    @Test
    fun `the sibling temporary directory is excluded from backup like the entry directory`() {
        val storage = IosOutcomeStorage(dir)
        assertTrue(storage.write("item-1", byteArrayOf(1)))

        for (path in listOf(dir, partial)) {
            val excluded: Any? = memScoped {
                val value = alloc<ObjCObjectVar<Any?>>()
                NSURL.fileURLWithPath(path).getResourceValue(value.ptr, forKey = NSURLIsExcludedFromBackupKey, error = null)
                value.value
            }
            assertEquals(true, (excluded as? NSNumber)?.boolValue, "$path is not excluded from backup")
        }
    }

    @Test
    fun `the journal directory name defaults to the one earlier versions used and is validated`() {
        assertEquals("kmptoolkit-uploader", BackgroundUploadConfig().journalDirectoryName)
        assertEquals("kmptoolkit-uploader", BackgroundUploadConfig.DEFAULT_JOURNAL_DIRECTORY_NAME)
        for (bad in listOf("", ".", "..", "a/b", "a\u0000b")) {
            assertFailsWith<IllegalArgumentException>("'$bad'") { BackgroundUploadConfig(journalDirectoryName = bad) }
        }
    }

    @Test
    fun `the five-argument constructor of 4x keeps the default directory and the name takes part in equality`() {
        val five = BackgroundUploadConfig(null, 60.minutes, 3.minutes, BackgroundUploadConfig.DEFAULT_REQUEST_TIMEOUT, BackgroundUploadConfig.DEFAULT_RESOURCE_TIMEOUT)

        assertEquals("kmptoolkit-uploader", five.journalDirectoryName)
        assertEquals(five, BackgroundUploadConfig(null, 60.minutes, 3.minutes))
        assertFalse(five == BackgroundUploadConfig(null, 60.minutes, 3.minutes, journalDirectoryName = "second"))
        assertTrue(BackgroundUploadConfig(journalDirectoryName = "second").toString().contains("journalDirectoryName=second"))
    }

    @Test
    fun `journals of two directory names do not share entries`() {
        val name = "kmptoolkit-uploader-files-test-${NSUUID().UUIDString()}"
        val base: String = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
        val second: IosOutcomeStorage = IosOutcomeStorage.default(name)
        try {
            assertTrue(second.write("shared-id", byteArrayOf(2)))
            assertTrue(manager.fileExistsAtPath("$base/$name/outcomes/shared-id"))
            assertEquals(null, IosOutcomeStorage.default().read("shared-id"), "the default journal does not hold the second journal's entry")
        } finally {
            manager.removeItemAtPath("$base/$name", error = null)
        }
    }
}
