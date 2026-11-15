package io.github.jamal_wia.kmptoolkit.storage

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.BindingKind
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.BindingSelection
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.DesktopOs
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.DirectoryKeychainBinding
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.FixedBindingSource
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.NativeExecutor
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.OsKeyCache
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * T7 for the encrypted stores: several real operating-system processes start on an EMPTY directory at once. Exactly one key
 * is created (every other process finds the winner's file and key), and no process's write is lost. The OS key store is a
 * keychain made of files shared by the children (`DirectoryKeychainBinding`), because an in-memory fake lives with one
 * process; the real stores are the lead's real-store suites.
 */
class EncryptedStoreForkedProcessesTest {

    private val root: File = Files.createTempDirectory("kmptoolkit-forked-enc").toFile()
    private val storeDirectory: File = File(root, "store").apply { mkdirs() }
    private val keychainDirectory: File = File(root, "keychain").apply { mkdirs() }
    private val children: MutableList<Process> = ArrayList()

    @AfterTest
    fun cleanUp() {
        children.forEach { it.destroyForcibly().waitFor(HANG_BOUND_SECONDS, TimeUnit.SECONDS) }
        root.deleteRecursively()
    }

    private fun javaCommand(): String =
        ProcessHandle.current().info().command().orElse(null) ?: File(File(System.getProperty("java.home"), "bin"), "java").path

    private fun spawn(mainClass: String, vararg args: String): Process {
        val process: Process = ProcessBuilder(
            listOf(javaCommand(), "-Xmx96m", "-XX:+UseSerialGC", "-cp", System.getProperty("java.class.path"), mainClass) + args,
        ).redirectErrorStream(true).start()
        children += process
        return process
    }

    private fun lines(process: Process): CompletableFuture<List<String>> = CompletableFuture.supplyAsync {
        BufferedReader(InputStreamReader(process.inputStream)).use { it.readLines() }
    }

    private fun runChildren(mainClass: String, argsOf: (Int) -> Array<String>) {
        val running: List<Pair<Process, CompletableFuture<List<String>>>> =
            (0 until PROCESSES).map { index: Int -> spawn(mainClass, *argsOf(index)).let { it to lines(it) } }
        for ((index: Int, child: Pair<Process, CompletableFuture<List<String>>>) in running.withIndex()) {
            val output: List<String> = child.second.get(HANG_BOUND_SECONDS, TimeUnit.SECONDS)
            assertTrue(child.first.waitFor(HANG_BOUND_SECONDS, TimeUnit.SECONDS), "child $index did not exit")
            assertEquals(listOf("DONE"), output, "child $index said: $output")
            assertEquals(0, child.first.exitValue(), "child $index failed")
        }
    }

    @Test
    fun `processes racing the first use of the OS-key store create one key and lose no write`() {
        runChildren(SecureStoreWriterChild::class.java.name) { index: Int ->
            arrayOf(storeDirectory.path, "forked", keychainDirectory.path, "$index", "$WRITES")
        }

        val keychain = DirectoryKeychainBinding(keychainDirectory)
        assertEquals(1, keychain.itemCount(), "exactly one key item in the keychain")
        val executor = NativeExecutor()
        try {
            val storage: SecureKeyValueStorage = JvmSecureKeyValueStorage(
                name = "forked",
                directory = storeDirectory,
                options = DesktopSecureStorageOptions(),
                source = FixedBindingSource(BindingSelection.Selected(keychain, DesktopOs.MACOS, BindingKind.FAKE)),
                executor = executor,
                cache = OsKeyCache(),
            )
            runBlocking {
                for (process: Int in 0 until PROCESSES) {
                    for (index: Int in 0 until WRITES) {
                        assertEquals(StorageResult.Success("v$index"), storage.get("p$process-$index"), "write p$process-$index was lost or unreadable")
                    }
                }
            }
        } finally {
            executor.close()
        }
        assertTrue(storeDirectory.list()!!.none { it.endsWith(".tmp") }, "a temporary file is left")
    }

    @Test
    fun `processes racing the first use of an app-key store create one header and lose no write`() {
        runChildren(AppKeyStoreWriterChild::class.java.name) { index: Int ->
            arrayOf(storeDirectory.path, "forked", "$index", "$WRITES")
        }

        val storage: AppKeyEncryptedKeyValueStorage =
            createAppKeyEncryptedKeyValueStorage(storeDirectory, StorageConfig("forked"), AppEncryptionKey.Raw { ByteArray(32) { 7 } })
        runBlocking {
            for (process: Int in 0 until PROCESSES) {
                for (index: Int in 0 until WRITES) {
                    assertEquals(StorageResult.Success("v$index"), storage.get("p$process-$index"), "write p$process-$index was lost or unreadable")
                }
            }
        }
        assertTrue(storeDirectory.list()!!.none { it.endsWith(".tmp") }, "a temporary file is left")
    }

    private companion object {
        const val PROCESSES: Int = 4
        const val WRITES: Int = 15
        const val HANG_BOUND_SECONDS: Long = 120
    }
}
