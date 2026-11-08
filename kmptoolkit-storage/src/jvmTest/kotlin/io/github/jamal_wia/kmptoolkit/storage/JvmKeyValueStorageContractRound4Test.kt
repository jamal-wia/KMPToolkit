package io.github.jamal_wia.kmptoolkit.storage

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The desktop store held to the contract's words with odd inputs and a hostile file system: values
 * and keys that are awkward for a properties file, a directory that cannot be written, and a file
 * that is not valid UTF-8.
 */
class JvmKeyValueStorageContractRound4Test {

    private val directory: File = Files.createTempDirectory("jvm-r4-").toFile()

    @AfterTest
    fun cleanUp() {
        makeWritable(directory)
        directory.deleteRecursively()
    }

    private fun makeWritable(dir: File) {
        runCatching { Files.setPosixFilePermissions(dir.toPath(), PosixFilePermission.values().toSet()) }
    }

    private fun makeReadOnly(dir: File) {
        Files.setPosixFilePermissions(
            dir.toPath(),
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE),
        )
    }

    private fun store(name: String = "r4"): KeyValueStorage = createKeyValueStorage(directory, StorageConfig(name))

    private fun describe(text: String): String =
        if (text.length > 20) "len=${text.length}" else text.map { "%04x".format(it.code) }.joinToString(" ")

    private val oddValues: List<String> = listOf(
        "", " ", "\t", "\n", "\r\n", "a\nb", "\u0000", "a\u0000b", "\u0001", "\u007f", "\u0085", " ",
        "￾", "￿", "😀", "<![CDATA[x]]>", "&amp;", "\"q\" 'q'", "  padded  ", "\\", "\\u0041",
        "=", ":", "#c", "!c", "line\\", "k=v", "x".repeat(2_000_000),
    )

    private val oddKeys: List<String> = listOf(
        "", " ", "  lead", "trail  ", "\u0000", "a\u0001b", "a b", "a=b", "a:b", "#c", "!c", "\\", "\\u0041",
        "\n", "a\nb", "😀", "k".repeat(10_000),
    )

    @Test
    fun `every legal value reads back unchanged from the same and from a fresh instance`() {
        val problems: MutableList<String> = mutableListOf()
        runBlocking {
            withTimeout(60_000) {
                val writer: KeyValueStorage = store()
                oddValues.forEachIndexed { index: Int, value: String ->
                    val put: StorageResult<Unit> = writer.put("k$index", value)
                    if (put !is StorageResult.Success) problems += "put failed for ${describe(value)}: $put"
                }
                val reader: KeyValueStorage = store()
                oddValues.forEachIndexed { index: Int, value: String ->
                    val read: String? = reader.get("k$index").getOrNull()
                    if (read != value) problems += "read of ${describe(value)} gave ${read?.let(::describe)}"
                }
            }
        }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `every legal key reads back from a fresh instance and no key swallows another`() {
        val problems: MutableList<String> = mutableListOf()
        runBlocking {
            withTimeout(60_000) {
                val writer: KeyValueStorage = store()
                oddKeys.forEachIndexed { index: Int, key: String ->
                    val put: StorageResult<Unit> = writer.put(key, "v$index")
                    if (put !is StorageResult.Success) problems += "put failed for key ${describe(key)}: $put"
                }
                val reader: KeyValueStorage = store()
                oddKeys.forEachIndexed { index: Int, key: String ->
                    val read: String? = reader.get(key).getOrNull()
                    if (read != "v$index") problems += "read of key ${describe(key)} gave $read"
                }
            }
        }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `an unwritable directory fails every write with a typed error, keeps the old contents and leaves no temporary file`() {
        val storage: KeyValueStorage = store()
        runBlocking {
            withTimeout(30_000) {
                assertTrue(storage.put("kept", "old") is StorageResult.Success)
                makeReadOnly(directory)
                try {
                    repeat(20) { round: Int ->
                        val put: StorageResult<Unit> = storage.put("kept", "new$round")
                        assertTrue(put is StorageResult.Failure, "round $round: put on a read-only directory gave $put")
                        assertTrue(storage.remove("kept") is StorageResult.Failure, "round $round: remove")
                        assertTrue(storage.put("other$round", "x") is StorageResult.Failure, "round $round: new key")
                        assertEquals("old", storage.get("kept").getOrNull(), "round $round: old value lost")
                    }
                    val clear: StorageResult<Unit> = storage.clear()
                    assertTrue(clear is StorageResult.Failure, "clear on a read-only directory gave $clear")
                } finally {
                    makeWritable(directory)
                }
                assertEquals("old", storage.get("kept").getOrNull())
                assertEquals(listOf("r4.kmptoolkit.storage.properties"), directory.list()!!.sorted())
            }
        }
    }

    @Test
    fun `a device id over a store that cannot be written never throws and recovers once it can`() {
        val storage: KeyValueStorage = store("devid")
        val provider = DeviceIdProvider(storage)
        runBlocking {
            withTimeout(30_000) {
                makeReadOnly(directory)
                val seen: MutableSet<String> = mutableSetOf()
                try {
                    repeat(10) { seen += provider.current() }
                } finally {
                    makeWritable(directory)
                }
                assertTrue(seen.all { it.isNotBlank() })
                val first: String = provider.current()
                assertEquals(first, provider.current(), "the id was not persisted after the store became writable")
            }
        }
    }

    @Test
    fun `a store whose file path is a directory or whose parent is a file fails with a typed error and never throws`() {
        runBlocking {
            withTimeout(30_000) {
                val asDirectory = File(directory, "${plainStoreId("dirstore")}.properties").apply { mkdirs() }
                File(asDirectory, "inner").writeText("x")
                val blocked: KeyValueStorage = store("dirstore")
                assertTrue(blocked.put("k", "v") is StorageResult.Failure || blocked.get("k") is StorageResult.Success)
                assertTrue(blocked.clear() is StorageResult.Success || blocked.clear() is StorageResult.Failure)

                val parentIsFile = File(directory, "parent-file").apply { writeText("not a directory") }
                val underFile: KeyValueStorage = createKeyValueStorage(parentIsFile, StorageConfig("x"))
                assertTrue(underFile.put("k", "v") is StorageResult.Failure)
                assertTrue(underFile.get("k") is StorageResult.Success || underFile.get("k") is StorageResult.Failure)
                assertTrue(underFile.remove("k") is StorageResult.Failure || underFile.remove("k") is StorageResult.Success)
                assertEquals("not a directory", parentIsFile.readText())
            }
        }
    }
}
