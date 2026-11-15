@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileMetadata
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest

/**
 * Breaker round 1 of group sto-desktop (phase A): attacks on the cross-process lock (plan S8, 4.3, D-3, 6c).
 *
 * - Creation race with another process's `clear()`: the lock-file creation runs outside the lock and uses the store's
 *   temp prefix, so another process that creates the lock file and then clears the store sweeps this process's in-flight
 *   creation temp. The lock file then exists; the write must take it, not fail with `OperationFailed`.
 * - POSIX record locks belong to the process and die with ANY descriptor of the file the process closes (the `FileLock`
 *   KDoc warns of exactly this). A second contender in this JVM that does not share the in-process key (a second copy of
 *   the library in another class loader: plug-in hosts) opens the lock file, meets `OverlappingFileLockException` and
 *   closes its channel. The holder's lock must still exclude another process afterwards.
 */
class CrossProcessLockBreakerR1Test {

    private val directory: File = Files.createTempDirectory("kmptoolkit-xlock-breaker").toFile()
    private val lockFile: File = File(directory, "store.properties.lock")
    private val prefix = "store.properties"
    private val children: MutableList<Process> = ArrayList()

    @AfterTest
    fun cleanUp() {
        children.forEach { it.destroyForcibly().waitFor(HANG_BOUND_SECONDS, TimeUnit.SECONDS) }
        directory.deleteRecursively()
    }

    @Test
    fun `a creation whose temporary file another process's clear swept still takes the lock file that process created`() = runBlocking {
        val real: FileAccess = createFileAccess()
        val firstLook = AtomicBoolean(true)
        val racing = object : FileAccess by real {
            override fun metadata(path: String, followLinks: Boolean): FileMetadata? =
                if (path == lockFile.absolutePath && firstLook.getAndSet(false)) null else real.metadata(path, followLinks)

            override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> {
                if (path != lockFile.absolutePath) return real.writeAtomically(path, bytes, options)
                // The other process creates the lock file exactly as the library does ...
                assertIs<FileResult.Success<Unit>>(real.writeAtomically(path, ByteArray(0), options), "control: the other creation")
                // ... and its clear() then removes this process's temporary file before the link, which fails.
                return FileResult.Failure(FileError.NotFound(File(directory, "${prefix}4711.tmp").path))
            }
        }

        val outcome: LockAcquisition = CrossProcessLock(racing).acquire(lockFile, prefix)

        assertTrue(lockFile.isFile, "control: the lock file exists")
        assertIs<LockAcquisition.Held>(outcome, "the lock file exists and is free, yet the write would answer OperationFailed: $outcome").close()
    }

    @Test
    fun `a second contender in this JVM never drops the holder's lock at the operating-system level`() = runTest {
        val holder: LockAcquisition.Held = assertIs(CrossProcessLock().acquire(lockFile, prefix))
        assertEquals("BUSY", probeFromAnotherProcess(), "control: another process sees the lock held")

        val contender: LockAcquisition =
            CrossProcessLock(deadline = 100.milliseconds, pollInterval = 25.milliseconds).acquire(lockFile, prefix)
        assertEquals(LockAcquisition.TimedOut, contender, "control: the contender in this JVM found the lock busy")

        assertEquals(
            "BUSY",
            probeFromAnotherProcess(),
            "after the in-JVM contender closed its handles, another process can take the lock the holder still believes it has",
        )
        holder.close()
        assertEquals("FREE", probeFromAnotherProcess(), "control: free once the holder lets go")
    }

    private fun probeFromAnotherProcess(): String {
        val javaCommand: String =
            ProcessHandle.current().info().command().orElse(null) ?: File(File(System.getProperty("java.home"), "bin"), "java").path
        val process: Process = ProcessBuilder(
            listOf(
                javaCommand,
                "-Xmx64m",
                "-XX:+UseSerialGC",
                "-cp",
                System.getProperty("java.class.path"),
                LockProbeChild::class.java.name,
                lockFile.path,
            ),
        ).redirectErrorStream(true).start()
        children += process
        val output: List<String> = CompletableFuture.supplyAsync {
            BufferedReader(InputStreamReader(process.inputStream)).use { it.readLines() }
        }.get(HANG_BOUND_SECONDS, TimeUnit.SECONDS)
        assertTrue(process.waitFor(HANG_BOUND_SECONDS, TimeUnit.SECONDS), "the probe did not exit")
        return output.lastOrNull().orEmpty()
    }

    private companion object {
        /** A hang bound, never a timing assertion. */
        const val HANG_BOUND_SECONDS: Long = 60
    }
}

/** A real second process: one `tryLock` on the file `args[0]`, prints `FREE` (and lets go) or `BUSY`. */
object LockProbeChild {
    @JvmStatic
    fun main(args: Array<String>) {
        FileChannel.open(File(args[0]).toPath(), StandardOpenOption.WRITE).use { channel: FileChannel ->
            val lock: FileLock? = channel.tryLock()
            println(if (lock == null) "BUSY" else "FREE")
            lock?.release()
        }
        System.out.flush()
    }
}
