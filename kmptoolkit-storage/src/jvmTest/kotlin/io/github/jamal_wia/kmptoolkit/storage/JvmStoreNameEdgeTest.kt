package io.github.jamal_wia.kmptoolkit.storage

import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The desktop store with store names at the edge of what [StorageConfig] accepts, including names
 * that are legal on macOS and Linux but not on Windows (`a:b`, `CON`, a trailing dot): the contract
 * is that an operation never throws, whatever the file system makes of the name, and that a write
 * which reports success can be read back.
 *
 * Unlike the Android edge-name test this one does not drop names the host rejects: the desktop target
 * is the one place where a Windows host is a real deployment, so the unfiltered list is what finds a
 * defect. A name the host accepts must round-trip; a name it rejects must give a typed failure.
 */
class JvmStoreNameEdgeTest {

    private val directory: File = Files.createTempDirectory("jvm-edge-").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun describe(text: String): String =
        if (text.length > 20) "len=${text.length}" else text.map { "%04x".format(it.code) }.joinToString(" ")

    @Test
    fun `store names at the edge of what StorageConfig accepts never make an operation throw`() {
        val names: List<String> = listOf(
            ".", "..", "...", "a:b", "a\nb", "a\tb", "n".repeat(200), "éè", "😀",
            "-", "~", "*", "?", "%", "a;b", "a|b", "a<b", "a>b", "a\"b", "CON", "NUL", "com1", "aux.txt",
            "trailing.", "a\u0001b",
        )
        val problems: MutableList<String> = mutableListOf()
        runBlocking {
            withTimeout(60_000) {
                for (name in names) {
                    try {
                        val storage: KeyValueStorage = createKeyValueStorage(directory, StorageConfig(name))
                        val put: StorageResult<Unit> = storage.put("k", "v")
                        val get: StorageResult<String?> = storage.get("k")
                        // The outcome is part of the contract: a name the host accepts as a file name must
                        // round-trip; one it does not must give a typed Failure (never a throw, never a
                        // success that cannot be read back).
                        val valid: Boolean = TestHost.isValidHostFileName("${plainStoreId(name)}.properties")
                        if (valid) {
                            if (put !is StorageResult.Success) {
                                problems += "${describe(name)}: a host-valid name, but put gave $put"
                            } else if (get.getOrNull() != "v") {
                                problems += "${describe(name)}: put succeeded, get gave $get"
                            }
                        } else if (put !is StorageResult.Failure) {
                            problems += "${describe(name)}: a host-invalid name, but put gave $put"
                        }
                        storage.clear()
                    } catch (error: Throwable) {
                        problems += "${describe(name)}: threw $error"
                    }
                }
            }
        }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun `a store name never makes the store write outside its directory`() {
        val outside: File = Files.createTempDirectory("jvm-edge-outside-").toFile()
        try {
            val before: Set<String> = outside.list()!!.toSet()
            val inner: File = File(directory, "inner").apply { mkdirs() }
            runBlocking {
                withTimeout(30_000) {
                    for (name in listOf("..", "...", "a:b", "x..y")) {
                        runCatching { createKeyValueStorage(inner, StorageConfig(name)).put("k", "v") }
                    }
                }
            }
            assertEquals(before, outside.list()!!.toSet(), "something was written next to the store's directory")
            assertEquals(emptyList(), directory.list()!!.filter { it != "inner" }, "a store escaped into its parent")
        } finally {
            outside.deleteRecursively()
        }
    }
}
