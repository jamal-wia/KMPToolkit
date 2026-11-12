package io.github.jamal_wia.kmptoolkit.storage

import io.github.jamal_wia.kmptoolkit.files.FileAccessConfig
import io.github.jamal_wia.kmptoolkit.files.FileNames
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume

/** Temporary-sibling names of the jvm store: 4.x leftovers, long store names, and neighbour isolation (T3a-2). */
class JvmKeyValueStorageTemporaryNamesTest {
    private val directory: File = Files.createTempDirectory("kmptoolkit-tmpnames").toFile()

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun store(name: String): KeyValueStorage = createKeyValueStorage(directory, StorageConfig(name))

    private fun fileNameOf(name: String): String = "${plainStoreId(name)}.properties"

    private fun bytes(text: String): Int = text.toByteArray(StandardCharsets.UTF_8).size

    private fun touch(name: String): File = File(directory, name).apply { writeText("x") }

    @Test
    fun `clear removes a 20 digit 4x leftover and keeps a 21 digit one and the neighbour shapes`() {
        val own: String = fileNameOf("app")
        val twenty: File = touch("${own}12345678901234567890.tmp")
        val nineteen: File = touch("${own}1234567890123456789.tmp")
        val twentyOne: File = touch("${own}123456789012345678901.tmp")
        val neighbours: List<File> = listOf(
            "$own.x123.tmp",
            "$own.tmp",
            "${own}123abc.tmp",
            "${own}123",
        ).map(::touch)

        runBlocking { store("app").clear() }

        assertTrue(!twenty.exists(), "a 20-digit 4.x leftover must be removed")
        assertTrue(!nineteen.exists(), "a 19-digit leftover must be removed")
        assertTrue(twentyOne.exists(), "a 21-digit sibling is not this store's shape")
        neighbours.forEach { assertTrue(it.exists(), "${it.name} was removed") }
    }

    @Test
    fun `stores with file names of 233 240 and 255 ASCII bytes and a multi-byte one round trip and leave no leftover`() = runBlocking {
        withTimeout(60_000) {
            val suffixBytes: Int = bytes(fileNameOf(""))
            val names: List<String> =
                listOf(233, 240, 255).map { target: Int -> "n".repeat(target - suffixBytes) } +
                    "\u00e9".repeat((250 - suffixBytes) / 2)
            for (name: String in names) {
                val storage: KeyValueStorage = store(name)
                assertTrue(bytes(fileNameOf(name)) <= 255, "test setup: the name must fit a file name")
                assertTrue(storage.put("k", "v") is StorageResult.Success, "put for a ${bytes(fileNameOf(name))}-byte file name")
                assertEquals("v", storage.get("k").getOrNull())
                assertEquals("v", store(name).get("k").getOrNull(), "a second instance reads the same bytes")
                assertEquals(1, directory.listFiles()!!.size, "only the value file remains: ${directory.list()!!.toList()}")
                assertTrue(storage.clear() is StorageResult.Success)
                assertEquals(0, directory.listFiles()!!.size)
            }
        }
    }

    @Test
    fun `temporaryPrefixOf is the identity up to 232 bytes and bounded stable and code point safe above`() {
        val max: Int = FileAccessConfig.MAX_TEMP_PREFIX_BYTES
        assertEquals("a".repeat(max), temporaryPrefixOf("a".repeat(max)))
        assertEquals("\u00e9".repeat(max / 2), temporaryPrefixOf("\u00e9".repeat(max / 2)))
        for (name: String in listOf("a".repeat(max + 1), "a".repeat(255), "\u00e9".repeat(125), "\uD83D\uDE00".repeat(60), "\u4e2d".repeat(84))) {
            val prefix: String = temporaryPrefixOf(name)
            assertTrue(bytes(prefix) <= max, "${bytes(prefix)} bytes for ${bytes(name)}")
            assertEquals(prefix, temporaryPrefixOf(name), "stable")
            assertTrue('~' in prefix)
            // Cut at a code point boundary: the kept part decodes back to a prefix of the name, no half surrogate.
            val kept: String = prefix.substringBeforeLast('~')
            assertTrue(name.startsWith(kept))
            assertTrue(kept.isEmpty() || !Character.isHighSurrogate(kept.last()))
            // The prefix is a valid temp prefix: the files module accepts it.
            FileAccessConfig(prefix)
            assertTrue(FileNames.isTempName(prefix + "123.tmp", prefix))
        }
    }

    @Test
    fun `two names sharing their first 232 bytes of a longer name get different prefixes`() {
        val shared: String = "s".repeat(240)
        assertNotEquals(temporaryPrefixOf(shared + "a"), temporaryPrefixOf(shared + "b"))
        assertNotEquals(temporaryPrefixOf(shared + "a"), temporaryPrefixOf(shared + "a" + "a"))
    }

    @Test
    fun `clearing one long-named store keeps the in-flight temporary file of another that shares its first 215 bytes`() {
        // A store file name ends in a fixed 30-byte suffix, so two names of at most 255 bytes can share at most 226 bytes (224 in
        // fact), never the 232 of the prefix cap; this test shares 215, the start the derived prefix keeps.
        val common: String = "c".repeat(215)
        val a: String = fileNameOf(common + "A")
        val b: String = fileNameOf(common + "B")
        assertEquals(a.substring(0, 215), b.substring(0, 215))
        val tempA: File = touch(temporaryPrefixOf(a) + "123456789.tmp")
        val tempB: File = touch(temporaryPrefixOf(b) + "123456789.tmp")

        runBlocking { store(common + "A").clear() }

        assertTrue(!tempA.exists(), "A's own temporary file must be removed")
        assertTrue(tempB.exists(), "B's temporary file must stay")
    }

    @Test
    fun `on Windows a store named like a device cannot be written but its value file is still read`() = runBlocking {
        Assume.assumeTrue("needs a Windows host", System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true))
        val storage: KeyValueStorage = store("con")
        assertTrue(storage.put("k", "v").errorOrNull() is StorageError.OperationFailed)
        // A value file that exists beforehand (written through the \\?\ form, which skips the device-name mapping) reads back.
        val legacy: File = File("\\\\?\\" + File(directory, fileNameOf("con")).absolutePath)
        legacy.writeText("k=v\n")
        assertEquals("v", storage.get("k").getOrNull())
    }
}
