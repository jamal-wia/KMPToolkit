package io.github.jamal_wia.kmptoolkit.storage

import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * B round 10, Q2 and Q3 on the desktop store. Contract: 05-platform-notes "Desktop" (one properties file, every write rewrites
 * the whole file into a temporary sibling and renames it, a temporary file a killed write left behind is removed by `clear()`,
 * instances over one file share a lock that exists only while a cycle holds it) and 04-api-reference "Desktop factory"
 * (`<config.name>.kmptoolkit.storage.properties`). A 3.0.0 store is the same Properties text; the fixture is written the way
 * 3.0.0's `properties.store(OutputStreamWriter(UTF_8), null)` wrote it.
 */
class JvmKeyValueStorageBudgetRound10Test {
    private lateinit var directory: File

    @BeforeTest
    fun setUp() {
        directory = Files.createTempDirectory("kv-r10").toFile()
    }

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun storeFile(name: String): File = File(directory, "$name.kmptoolkit.storage.properties")

    private fun litter(): List<String> = directory.list().orEmpty().filter { it.endsWith(".tmp") }.sorted()

    @Test
    fun `a file of ten thousand entries and a megabyte value reads and writes correctly and every write leaves no temporary file`() = runBlocking<Unit> {
        withTimeout(120_000) {
            val name = "big"
            val properties = Properties()
            for (i: Int in 0 until 10_000) properties.setProperty("key.$i", "value $i ".repeat(3))
            val megabyte: String = buildString { repeat(1 shl 20) { append(('a' + it % 26)) } }
            properties.setProperty("blob", megabyte)
            storeFile(name).writer(Charsets.UTF_8).use { properties.store(it, null) }
            val store: KeyValueStorage = createKeyValueStorage(directory, StorageConfig(name))
            assertEquals(StorageResult.Success<String?>(megabyte), store.get("blob"))
            assertEquals(StorageResult.Success<String?>("value 9999 ".repeat(3)), store.get("key.9999"))
            assertEquals(StorageResult.Success<String?>(null), store.get("key.10000"))
            // Twenty writes into the big file: each one is a whole-file rewrite and ends in a rename, so nothing else is left.
            for (i: Int in 0 until 20) assertEquals(StorageResult.Success(Unit), store.put("key.$i", "changed $i"))
            assertEquals(StorageResult.Success(Unit), store.remove("key.5000"))
            assertEquals(emptyList(), litter())
            val reread: Properties = storeFile(name).reader(Charsets.UTF_8).use { Properties().apply { load(it) } }
            assertEquals(10_000 + 1 - 1, reread.size, "one key removed and the blob kept")
            assertEquals("changed 19", reread.getProperty("key.19"))
            assertEquals(megabyte, reread.getProperty("blob"))
            assertEquals(StorageResult.Success(Unit), store.clear())
            assertEquals(emptyList(), directory.list().orEmpty().toList(), "clear leaves nothing of the store")
        }
    }

    @Test
    fun `a 3_0_0 store with its values and the 3_0_0 leftover of a killed write is read, rewritten and cleaned by this version`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val name = "legacy"
            // 3.0.0 wrote: Properties.store(OutputStreamWriter(UTF_8), null): `#date` comment, `\uXXXX` only for control characters,
            // non-ASCII text raw, `:` `=` `#` `!` and leading spaces escaped.
            val text: String = "#Fri Jan 01 00:00:00 UTC 2021\n" +
                "kmptoolkit.storage.device_id=0b0c7e5f-0000-4000-8000-000000000001\n" +
                "token=a\\:b\\=c\\#d\\!e\n" +
                "\\ lead=\\ spaced\n" +
                "emoji=😀 жук\n" +
                "empty=\n" +
                "multi=line1\\nline2\\r\\n\n"
            storeFile(name).writeText(text, Charsets.UTF_8)
            // createTempFile(file name, ".tmp"): up to 20 digits, plus a neighbour whose name only starts the same way.
            File(directory, "${storeFile(name).name}8736482910.tmp").writeText("half")
            File(directory, "${storeFile(name).name}.bak.tmp").writeText("neighbour")
            val store: KeyValueStorage = createKeyValueStorage(directory, StorageConfig(name))
            assertEquals(StorageResult.Success<String?>("0b0c7e5f-0000-4000-8000-000000000001"), store.get(DeviceIdProvider.DEFAULT_KEY))
            assertEquals("0b0c7e5f-0000-4000-8000-000000000001", DeviceIdProvider(store).current(), "the installed device id survives the upgrade")
            assertEquals(StorageResult.Success<String?>("a:b=c#d!e"), store.get("token"))
            assertEquals(StorageResult.Success<String?>(" spaced"), store.get(" lead"))
            assertEquals(StorageResult.Success<String?>("😀 жук"), store.get("emoji"))
            assertEquals(StorageResult.Success<String?>(""), store.get("empty"))
            assertEquals(StorageResult.Success<String?>("line1\nline2\r\n"), store.get("multi"))
            assertEquals(StorageResult.Success(Unit), store.put("new", "n"))
            assertEquals(StorageResult.Success<String?>("a:b=c#d!e"), store.get("token"), "a write keeps every other key")
            assertEquals(StorageResult.Success(Unit), store.clear())
            assertEquals(listOf("${storeFile(name).name}.bak.tmp"), directory.list().orEmpty().toList(), "clear takes the 3.0.0 leftover and not a neighbour's file")
        }
    }

    @Test
    fun `clear removes the 4_x leftover of a store whose file name is longer than the hashed prefix limit`() = runBlocking<Unit> {
        withTimeout(60_000) {
            // 215 + 30 = 245 bytes of file name: above the 232 byte limit (the 5.x temporary prefix is hashed), and a 4.x leftover
            // `<file name><digits>.tmp` of one digit is still 250 bytes, so it could exist on disk.
            val name: String = "n".repeat(215)
            val fileName: String = storeFile(name).name
            check(fileName.length == 245)
            val leftover = File(directory, "${fileName}5.tmp").apply { writeText("half") }
            val neighbour = File(directory, "${fileName}.bak.tmp").apply { writeText("neighbour") }
            val store: KeyValueStorage = createKeyValueStorage(directory, StorageConfig(name))
            assertEquals(StorageResult.Success(Unit), store.put("k", "v"))
            assertEquals(StorageResult.Success(Unit), store.clear())
            assertFalse(leftover.exists(), "the 4.x leftover of a long store name survived clear()")
            assertTrue(neighbour.exists(), "clear() took a file that is not this store's temporary")
        }
    }

    @Test
    fun `two hundred instances and sixteen racing writers over one file leave a whole file and no temporary file`() = runBlocking<Unit> {
        withTimeout(120_000) {
            val name = "race"
            val stores: List<KeyValueStorage> = (0 until 200).map { createKeyValueStorage(directory, StorageConfig(name)) }
            (0 until 16).map { w: Int ->
                async(Dispatchers.Default) {
                    for (r: Int in 0 until 10) assertEquals(StorageResult.Success(Unit), stores[(w * 10 + r) % stores.size].put("w$w", "r$r"))
                }
            }.awaitAll()
            assertEquals(emptyList(), litter())
            for (w: Int in 0 until 16) assertEquals(StorageResult.Success<String?>("r9"), stores[199].get("w$w"), "the last write of writer $w")
            val reread: Properties = storeFile(name).reader(Charsets.UTF_8).use { Properties().apply { load(it) } }
            assertTrue(reread.size == 16, "lost or extra keys: ${reread.keys}")
        }
    }
}
