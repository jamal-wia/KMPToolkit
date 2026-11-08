package io.github.jamal_wia.kmptoolkit.storage

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** A desktop store file that is not valid UTF-8 cannot be parsed: it fails every operation and is never rewritten. */
class JvmKeyValueStorageInvalidUtf8Round4Test {

    private val directory: File = Files.createTempDirectory("jvm-utf8-").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun store(name: String): KeyValueStorage = createKeyValueStorage(directory, StorageConfig(name))

    private fun invalidFile(name: String): File {
        val file = File(directory, "${plainStoreId(name)}.properties")
        file.writeBytes("keep=caf".encodeToByteArray() + byteArrayOf(0xE9.toByte()) + "\nother=1\n".encodeToByteArray())
        return file
    }

    private fun assertOperationFailed(result: StorageResult<*>, operation: StorageOperation) {
        assertTrue(
            result is StorageResult.Failure && (result.error as? StorageError.OperationFailed)?.operation == operation,
            "expected OperationFailed($operation), was $result",
        )
    }

    @Test
    fun `a file that is not valid UTF-8 fails a write and is not rewritten`() = runBlocking {
        withTimeout(20_000) {
            val file: File = invalidFile("legacy")
            val before: List<Byte> = file.readBytes().toList()
            val storage: KeyValueStorage = store("legacy")

            assertOperationFailed(storage.put("new", "v"), StorageOperation.PUT)
            assertOperationFailed(storage.remove("other"), StorageOperation.REMOVE)

            assertEquals(before, file.readBytes().toList(), "a failed write changed the file")
        }
    }

    @Test
    fun `a file that is not valid UTF-8 fails a read and clear is the way out`() = runBlocking {
        withTimeout(20_000) {
            invalidFile("legacy2")
            val storage: KeyValueStorage = store("legacy2")

            assertOperationFailed(storage.get("other"), StorageOperation.GET)

            assertTrue(storage.clear() is StorageResult.Success)
            assertTrue(storage.put("k", "v") is StorageResult.Success)
            assertEquals("v", storage.get("k").getOrNull())
        }
    }

    @Test
    fun `a UTF-8 byte order mark still decodes`() = runBlocking {
        withTimeout(20_000) {
            val file = File(directory, "${plainStoreId("bom")}.properties")
            file.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "a=1\n".encodeToByteArray())
            // The BOM is read as a character of the first key, as java.util.Properties always did; the second key is clean.
            file.appendText("b=2\n")
            assertEquals("2", store("bom").get("b").getOrNull())
        }
    }
}
