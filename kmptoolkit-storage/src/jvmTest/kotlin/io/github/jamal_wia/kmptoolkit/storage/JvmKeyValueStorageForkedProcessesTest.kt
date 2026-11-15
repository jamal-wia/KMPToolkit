@file:OptIn(ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.storage

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.CrossProcessLock
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.LockAcquisition
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * T7: two real operating-system processes over one directory. A write waits for a lock another process holds and answers
 * `Unavailable` at the deadline without changing anything, a lock dies with its process, and writers in several processes
 * lose no update. The in-JVM stand-ins of the same rules are in `CrossProcessLockTest`; the Windows half (the rename is never
 * blocked by the lock) is run on the PC by the lead.
 */
class JvmKeyValueStorageForkedProcessesTest {

    private val directory: File = Files.createTempDirectory("kmptoolkit-forked").toFile()
    private val children: MutableList<Process> = ArrayList()
    private val storeName = "forked"
    private val dataFile: File = File(directory, "${plainStoreId(storeName)}.properties")

    @AfterTest
    fun cleanUp() {
        children.forEach { it.destroyForcibly().waitFor(HANG_BOUND_SECONDS, TimeUnit.SECONDS) }
        directory.deleteRecursively()
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

    /** Reads the child's lines on a helper thread so a child that never speaks fails the test instead of hanging it. */
    private fun lines(process: Process): CompletableFuture<List<String>> = CompletableFuture.supplyAsync {
        BufferedReader(InputStreamReader(process.inputStream)).use { it.readLines() }
    }

    private fun awaitExit(process: Process, what: String) {
        assertTrue(process.waitFor(HANG_BOUND_SECONDS, TimeUnit.SECONDS), "$what did not exit within $HANG_BOUND_SECONDS s")
        // The JDK closes a finished child's pipes on a reaper thread, after waitFor may have returned: close them here so a
        // later descriptor-count assertion of another test never sees them go.
        process.outputStream.close()
        process.inputStream.close()
        process.errorStream.close()
    }

    private fun storage(
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
        deadline: kotlin.time.Duration = 3.seconds,
        timeSource: kotlin.time.TimeSource.WithComparableMarks? = null,
    ): JvmKeyValueStorage =
        if (timeSource == null) {
            JvmKeyValueStorage(dataFile, dispatcher, crossProcessLock = CrossProcessLock(deadline = deadline))
        } else {
            JvmKeyValueStorage(dataFile, dispatcher, crossProcessLock = CrossProcessLock(deadline = deadline), timeSource = timeSource)
        }

    private fun awaitHeld(child: Process): java.io.BufferedReader {
        val reader = BufferedReader(InputStreamReader(child.inputStream))
        val first: String? = CompletableFuture.supplyAsync { reader.readLine() }.get(HANG_BOUND_SECONDS, TimeUnit.SECONDS)
        assertEquals("HELD", first, "the child must report that it holds the lock")
        return reader
    }

    @Test
    fun `a write waits for the lock another process holds, answers Unavailable at the deadline and changes nothing`() = runTest {
        val storage: JvmKeyValueStorage = storage(StandardTestDispatcher(testScheduler), deadline = 10.seconds, timeSource = testScheduler.timeSource)
        assertEquals(StorageResult.Success(Unit), storage.put("a", "1"))
        val child: Process = spawn(LockHolderChild::class.java.name, CrossProcessLock.lockFileOf(dataFile).path, "p")
        awaitHeld(child)
        val bytesBefore: ByteArray = dataFile.readBytes()
        val timeBefore: Long = testScheduler.currentTime

        for (attempt: StorageResult<Unit> in listOf(storage.put("b", "2"), storage.remove("a"), storage.clear())) {
            val error: StorageError = assertIs<StorageResult.Failure>(attempt, "a write under a foreign lock").error
            assertIs<StorageError.Unavailable>(error)
        }

        assertEquals(30_000L, testScheduler.currentTime - timeBefore, "each of the three writes waited exactly the 10 s deadline")
        assertTrue(bytesBefore.contentEquals(dataFile.readBytes()), "a write that timed out must not change the file")
        assertEquals("1", storage.get("a").getOrNull(), "a read takes no file lock")
        child.outputStream.write('\n'.code)
        child.outputStream.flush()
        awaitExit(child, "the lock holder")
        assertEquals(StorageResult.Success(Unit), storage.put("b", "2"), "the write goes through once the other process let go")
    }

    private fun probe(lockFile: File): String {
        val child: Process = spawn(LockProbeChild::class.java.name, lockFile.path, "p")
        val answer: List<String> = lines(child).get(HANG_BOUND_SECONDS, TimeUnit.SECONDS)
        awaitExit(child, "the lock probe")
        return answer.single()
    }

    @Test
    fun `waiters of this JVM polling a held lock never drop it for another process`() = runTest {
        // POSIX fcntl locks belong to the process: closing ANY channel of the lock file in this JVM would silently release
        // the lock another thread holds. A waiter of this JVM must therefore never open the file while it is held.
        val lockFile: File = CrossProcessLock.lockFileOf(dataFile)
        val lock = CrossProcessLock(deadline = 10.seconds, pollInterval = 25.milliseconds)
        val holder: LockAcquisition.Held = assertIs(lock.acquire(lockFile, temporaryPrefixOf(dataFile.name)))
        assertEquals("BUSY", probe(lockFile), "control: another process sees the lock held")

        assertEquals(LockAcquisition.TimedOut, lock.acquire(lockFile, temporaryPrefixOf(dataFile.name), budget = 2.seconds), "80 polls of this JVM")

        assertEquals("BUSY", probe(lockFile), "the polls of this JVM must not have released the lock for other processes")
        holder.close()
        assertEquals("FREE", probe(lockFile), "control: the probe does see a free lock")
    }

    @Test
    fun `the lock dies with its process, so a killed holder never wedges the store`() = runTest {
        val storage: JvmKeyValueStorage = storage(StandardTestDispatcher(testScheduler), timeSource = testScheduler.timeSource)
        val child: Process = spawn(LockHolderChild::class.java.name, CrossProcessLock.lockFileOf(dataFile).path, "p")
        awaitHeld(child)
        assertIs<StorageError.Unavailable>(assertIs<StorageResult.Failure>(storage.put("a", "1"), "control: held, so it times out").error)

        child.destroyForcibly()
        awaitExit(child, "the killed holder")

        val timeBefore: Long = testScheduler.currentTime
        assertEquals(StorageResult.Success(Unit), storage.put("a", "1"))
        assertEquals(timeBefore, testScheduler.currentTime, "the lock was free at once")
    }

    @Test
    fun `writers in four processes and this one lose no update and leave no temporary file`() {
        val perWriter = 12
        val writers: List<Process> = (0 until 4).map { id: Int ->
            spawn(StoreWriterChild::class.java.name, directory.path, storeName, "c$id", perWriter.toString())
        }
        val outputs: List<CompletableFuture<List<String>>> = writers.map(::lines)
        val own: JvmKeyValueStorage = storage(kotlinx.coroutines.Dispatchers.IO, deadline = 60.seconds)
        kotlinx.coroutines.runBlocking {
            repeat(perWriter) { index: Int ->
                assertEquals(StorageResult.Success(Unit), own.put("parent-$index", "v$index"), "the parent's put $index")
            }
        }
        writers.forEachIndexed { id: Int, process: Process ->
            awaitExit(process, "writer $id")
            assertEquals(listOf("DONE"), outputs[id].get(HANG_BOUND_SECONDS, TimeUnit.SECONDS), "writer $id output")
            assertEquals(0, process.exitValue(), "writer $id")
        }

        val expected: Set<String> =
            (0 until 4).flatMap { id: Int -> (0 until perWriter).map { "pc$id-$it" } }.toSet() + (0 until perWriter).map { "parent-$it" }
        val present: Set<String> = kotlinx.coroutines.runBlocking {
            expected.filter { key: String -> own.get(key).getOrNull() != null }.toSet()
        }
        assertEquals(emptySet(), expected - present, "updates lost across processes: ${(expected - present).take(5)}")
        assertEquals(emptyList(), directory.list()!!.filter { it.endsWith(".tmp") }, "temporary files left behind")
    }

    private companion object {
        /** A hang bound, never a timing assertion. */
        const val HANG_BOUND_SECONDS: Long = 120
    }
}
