package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.testsupport.TestPaths
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeDirectory
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.symlink

/**
 * B round 9, finding F-1 (D-131, ruling reviews/b-r9-f1-ruling-opus.md), iOS twin of AndroidOpaqueDirectoryRound9Test.
 * Contract: DownloaderStorage.isResourceAvailable ("a plain file must exist (a symbolic link to one counts; a directory at
 * that path does not)") and 05-platform-notes ("A directory found at a plain-file resource's path reads as unavailable and
 * is moved into the trash by the commit"). A kill inside the commit cannot be injected on iOS; the observable outcome is
 * pinned: the path holds the file, the trash is empty, the archive unit no longer reads as available.
 */
@OptIn(ExperimentalForeignApi::class)
class IosOpaqueDirectoryRound9Test {
    private val config: DownloaderStorageConfig = DownloaderStorageConfig(baseDirectoryName = "opaque_dir_round9")
    private val manager: NSFileManager = NSFileManager.defaultManager

    private val baseDirectory: String
        get() {
            val appSupport: String = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
                .first() as String
            return TestPaths.join(appSupport, config.baseDirectoryName)
        }

    // path-hygiene-ignore: PH4 UnitFileLayout.TRASH_DIRECTORY is a logical '/'-separated layout name; it is handed to TestPaths.join
    private val trashDirectory: String get() = TestPaths.join(baseDirectory, UnitFileLayout.TRASH_DIRECTORY)

    private val payload: ByteArray = ByteArray(300) { b: Int -> (b % 11 + 1).toByte() }

    @BeforeTest
    fun setUp() {
        manager.removeItemAtPath(baseDirectory, error = null)
        LegacyTempMigrations.resetForTests()
    }

    @AfterTest
    fun cleanUp() {
        manager.removeItemAtPath(baseDirectory, error = null)
        LegacyTempMigrations.resetForTests()
    }

    private class Group(override val key: String) : ResourceGroup {
        override var units: List<DownloadUnit> = emptyList()
    }

    private class FormatUnit(
        override val group: ResourceGroup,
        override val relativePath: String,
        override val format: ResourceFormat,
    ) : DownloadUnit {
        override val id: String = "u"
        override val apiPath: String = "test/u"
    }

    private fun opaque(path: String): FormatUnit = FormatUnit(Group("g"), path, ResourceFormat.Opaque)

    private fun archive(path: String): FormatUnit = FormatUnit(Group("g"), path, ResourceFormat.ZipArchive("marker.txt"))

    private fun writeFile(path: String, bytes: ByteArray) {
        manager.createDirectoryAtPath(
            path = path.substringBeforeLast('/'),
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        val file = fopen(path, "wb") ?: error("could not open $path")
        try {
            if (bytes.isNotEmpty()) {
                bytes.usePinned { pinned -> fwrite(pinned.addressOf(0), 1u.convert(), bytes.size.convert(), file) }
            }
        } finally {
            fclose(file)
        }
    }

    private fun readFile(path: String): ByteArray {
        val data: NSData = NSData.dataWithContentsOfFile(path) ?: error("could not read $path")
        val bytes = ByteArray(data.length.toInt())
        if (bytes.isNotEmpty()) bytes.usePinned { pinned -> platform.posix.memcpy(pinned.addressOf(0), data.bytes, data.length) }
        return bytes
    }

    private suspend fun commit(storage: DownloaderStorage, unit: DownloadUnit, bytes: ByteArray) {
        storage.beginTempFile(unit, null)
        writeFile(storage.getTempFilePath(unit), bytes)
        storage.markTempFileComplete(unit)
        storage.commitResource(unit)
    }

    private fun typeOf(path: String): String? =
        manager.attributesOfItemAtPath(path, error = null)?.get(NSFileType) as? String

    private fun trashEntries(): List<String> =
        manager.contentsOfDirectoryAtPath(trashDirectory, error = null).orEmpty().filterIsInstance<String>()

    @Test
    fun `a directory at the path of a plain file unit does not read as an available resource`() {
        val storage: DownloaderStorage = createDownloaderStorage(config)
        val unit: FormatUnit = opaque("files/u.bin")
        manager.createDirectoryAtPath(storage.getResourcePath(unit), withIntermediateDirectories = true, attributes = null, error = null)
        assertFalse(storage.isResourceAvailable(unit), "a directory reads as an available plain file")
        writeFile(TestPaths.join(storage.getResourcePath(unit), "inside.bin"), payload)
        assertFalse(storage.isResourceAvailable(unit), "a non-empty directory reads as an available plain file")
    }

    @Test
    fun `a plain file and a link to a file read available and a dangling link does not`() {
        val storage: DownloaderStorage = createDownloaderStorage(config)
        val unit: FormatUnit = opaque("files/u.bin")
        val path: String = storage.getResourcePath(unit)
        writeFile(path, payload)
        assertTrue(storage.isResourceAvailable(unit), "a plain file")
        manager.removeItemAtPath(path, error = null)

        val real: String = TestPaths.join(baseDirectory, "elsewhere.bin")
        writeFile(real, payload)
        assertTrue(symlink(real, path) == 0, "could not create the symbolic link")
        assertTrue(storage.isResourceAvailable(unit), "a link to a file")
        manager.removeItemAtPath(path, error = null)

        assertTrue(symlink(TestPaths.join(baseDirectory, "nowhere.bin"), path) == 0, "could not create the dangling link")
        assertFalse(storage.isResourceAvailable(unit), "a dangling link")
    }

    @Test
    fun `an opaque commit onto the committed tree of an archive unit of the same path lands the file and empties the trash`() = runTest {
        val storage: DownloaderStorage = createDownloaderStorage(config)
        val zipUnit: FormatUnit = archive("res/u")
        val opaqueUnit: FormatUnit = opaque("res/u")
        commit(storage, zipUnit, ZipBytesRound6.build(listOf(ZipEntryRound6("marker.txt", "m"), ZipEntryRound6("sub/a.txt", "a"))).bytes)
        assertTrue(storage.isResourceAvailable(zipUnit))
        assertFalse(storage.isResourceAvailable(opaqueUnit), "the archive tree reads as an available plain file")

        commit(storage, opaqueUnit, payload)

        val target: String = storage.getResourcePath(opaqueUnit)
        assertEquals(NSFileTypeRegular, typeOf(target), "the path does not hold a file")
        assertContentEquals(payload, readFile(target))
        assertEquals(emptyList(), trashEntries(), "the detached tree was left in the trash")
        assertFalse(storage.isResourceAvailable(zipUnit))
        assertTrue(storage.isResourceAvailable(opaqueUnit))
        assertEquals(TempFileState.None, storage.tempFileState(opaqueUnit))
    }

    @Test
    fun `a stray non-empty directory at the path is replaced by the committed file`() = runTest {
        val storage: DownloaderStorage = createDownloaderStorage(config)
        val unit: FormatUnit = opaque("res/stray.bin")
        val target: String = storage.getResourcePath(unit)
        writeFile(TestPaths.join(target, "deep/er/x.bin"), ByteArray(40) { 9 })
        writeFile(TestPaths.join(target, "top.bin"), ByteArray(40) { 8 })
        assertEquals(NSFileTypeDirectory, typeOf(target))
        assertFalse(storage.isResourceAvailable(unit))

        commit(storage, unit, payload)

        assertEquals(NSFileTypeRegular, typeOf(target), "the path does not hold a file")
        assertContentEquals(payload, readFile(target))
        assertEquals(emptyList(), trashEntries(), "the stray directory was left in the trash")
        assertTrue(storage.isResourceAvailable(unit))
        assertEquals(TempFileState.None, storage.tempFileState(unit))
    }
}
