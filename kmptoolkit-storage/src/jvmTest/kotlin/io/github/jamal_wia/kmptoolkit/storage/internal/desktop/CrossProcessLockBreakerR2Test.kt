package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import io.github.jamal_wia.kmptoolkit.testsupport.TestFs
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.net.URL
import java.net.URLClassLoader
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield

/**
 * Breaker round 2 of group sto-desktop (phase A): the cross-process lock after the in-process claim (plan S8, 4.3, D-3,
 * 6c; the class KDoc: "closing ANY channel of the file in this JVM silently drops a lock another thread holds").
 *
 * - The claim set lives in a companion object, so it is per class loader. A second copy of the library in this JVM (a
 *   plug-in host, an IDE, an application server, a test runner that isolates modules) has its own claim set: its contender
 *   opens the lock file, meets the JDK's JVM-wide `OverlappingFileLockException`, closes its channel, and POSIX drops the
 *   holder's lock. Another process then writes while the holder believes it holds the lock: a lost update.
 * - The no-hard-link creation path opens the lock file with the default mode: plan 4.3 says the lock file is 0600, and
 *   "operation not permitted" / "not supported" link failures of permission-bearing mounts (sshfs, some SMB/FUSE) also
 *   land there, not only FAT.
 * - Every failure path inside an attempt must release the claim and the descriptor (an `Error` from `tryLock`, an
 *   `IOException` from `tryLock`, a cancelled waiter), and a storm over two lock files with cancellations must leave no
 *   claim and no descriptor behind.
 */
class CrossProcessLockBreakerR2Test {

    private val directory: File = Files.createTempDirectory("kmptoolkit-xlock-breaker2").toFile()
    private val lockFile: File = File(directory, "store.properties.lock")
    private val prefix = "store.properties"
    private val children: MutableList<Process> = CopyOnWriteArrayList()

    @AfterTest
    fun cleanUp() {
        children.forEach { it.destroyForcibly().waitFor(HANG_BOUND_SECONDS, TimeUnit.SECONDS) }
        directory.deleteRecursively()
    }

    @Test
    fun `a contender from a second copy of the library in this JVM never drops the holder's lock for other processes`() = runBlocking {
        val holder: LockAcquisition.Held = assertIs(CrossProcessLock().acquire(lockFile, prefix))
        try {
            assertEquals("BUSY", probeFromAnotherProcess(), "control: another process sees the lock held")

            isolatedLoader().use { loader: URLClassLoader ->
                assertNotSame(
                    CrossProcessLock::class.java,
                    loader.loadClass(CrossProcessLock::class.java.name),
                    "control: the second loader has its own copy of the library",
                )
                val contended: String = loader.loadClass(IsolatedLockContender::class.java.name)
                    .getMethod("contend", String::class.java, String::class.java)
                    .invoke(null, lockFile.path, prefix) as String
                assertEquals("TimedOut", contended, "control: the second copy found the lock busy")
            }

            assertEquals(
                "BUSY",
                probeFromAnotherProcess(),
                "after a second copy of the library in this JVM failed to lock and closed its channel, another process " +
                    "can take the lock the holder still believes it has (POSIX locks die with any closed descriptor)",
            )
        } finally {
            holder.close()
        }
        assertEquals("FREE", probeFromAnotherProcess(), "control: free once the holder lets go")
    }

    @Test
    fun `a lock file created on a volume without hard links is still owner-only where the volume has permissions`() = runBlocking {
        TestHost.assumePosixFileSemantics("a POSIX mode; the Windows ACL is the files module's test")
        val probe = File(directory, "umask-probe")
        Files.createFile(probe.toPath())
        // path-hygiene-ignore: PH2 reads the mode a plain create gets under this process's umask, on a POSIX host only (assumed above)
        val plainMode: Set<PosixFilePermission> = Files.getPosixFilePermissions(probe.toPath())
        org.junit.Assume.assumeTrue(
            "the umask already hides group and other bits, so the test could not tell",
            // path-hygiene-ignore: PH2 compares POSIX bits, on a POSIX host only (assumed above)
            plainMode.any { it != PosixFilePermission.OWNER_READ && it != PosixFilePermission.OWNER_WRITE },
        )
        val real: FileAccess = createFileAccess()
        val noLinks = object : FileAccess by real {
            override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> =
                if (path == lockFile.absolutePath) FileResult.Failure(FileError.Unsupported(path)) else real.writeAtomically(path, bytes, options)
        }

        assertIs<LockAcquisition.Held>(CrossProcessLock(noLinks).acquire(lockFile, prefix)).close()

        assertTrue(lockFile.isFile, "control: the fallback created the lock file")
        // path-hygiene-ignore: PH2 reads the mode the library set, on a POSIX host only (assumed above)
        val actual: Set<PosixFilePermission> = Files.getPosixFilePermissions(lockFile.toPath())
        assertEquals(
            // path-hygiene-ignore: PH2 the mode plan 4.3 gives the lock file, on a POSIX host only (assumed above)
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            actual,
            "the lock file of the no-hard-link path got the default mode (plan 4.3: 0600); another user can open it and " +
                "hold a shared lock that blocks every write",
        )
    }

    @Test
    fun `an Error or an IOException from tryLock releases the claim and the descriptor, so the next attempt locks at once`() = runBlocking {
        val before: Long = TestFs.requireOpenDescriptorCount()
        val calls = AtomicInteger()
        val flaky: (FileChannel) -> FileLock? = { channel: FileChannel ->
            when (calls.incrementAndGet()) {
                1 -> throw StackOverflowError("breaker: an Error out of tryLock")
                2 -> throw IOException("breaker: no locks available")
                else -> channel.tryLock()
            }
        }
        val lock = CrossProcessLock(tryLock = flaky)

        assertFailsWith<StackOverflowError> { lock.acquire(lockFile, prefix) }
        assertIs<LockAcquisition.NotSupported>(lock.acquire(lockFile, prefix), "an IOException of tryLock is NotSupported")
        assertEquals(before, TestFs.requireOpenDescriptorCount(), "a failed attempt left a descriptor open")

        val held: LockAcquisition.Held = assertIs(
            CrossProcessLock(deadline = 50.milliseconds, pollInterval = 25.milliseconds, tryLock = flaky).acquire(lockFile, prefix),
            "a claim of a failed attempt was never released: the lock file is busy for this JVM for good",
        )
        held.close()
        assertEquals(before, TestFs.requireOpenDescriptorCount())
    }

    @Test
    fun `a seeded storm over two lock files with cancelled waiters is exclusive and leaves no claim and no descriptor`() = runBlocking {
        val seed = 20_261_116L
        val files: List<File> = listOf(lockFile, File(directory, "other.properties.lock"))
        val inside: List<AtomicInteger> = files.map { AtomicInteger() }
        val problems: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()
        val acquired = AtomicInteger()
        val before: Long = TestFs.requireOpenDescriptorCount()
        withTimeout(STORM_BOUND) {
            (0 until WORKERS).map { worker: Int ->
                launch(Dispatchers.IO) {
                    val random = Random(seed * 1000 + worker)
                    val lock = CrossProcessLock(deadline = 2.seconds, pollInterval = 1.milliseconds)
                    repeat(STEPS) { step: Int ->
                        val index: Int = random.nextInt(files.size)
                        val cancelAfterYields: Int = if (random.nextInt(4) == 0) random.nextInt(3) else -1
                        val job = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                            when (val got: LockAcquisition = lock.acquire(files[index], "p$index")) {
                                is LockAcquisition.Held -> got.use {
                                    acquired.incrementAndGet()
                                    if (inside[index].incrementAndGet() != 1) problems += "seed=$seed w=$worker s=$step two holders of file $index"
                                    try {
                                        repeat(random.nextInt(3)) { yield() }
                                    } finally {
                                        inside[index].decrementAndGet()
                                    }
                                }
                                LockAcquisition.TimedOut -> Unit
                                else -> problems += "seed=$seed w=$worker s=$step unexpected $got"
                            }
                        }
                        if (cancelAfterYields >= 0) {
                            repeat(cancelAfterYields) { yield() }
                            job.cancel()
                        }
                        job.join()
                    }
                }
            }.forEach { it.join() }
        }
        assertEquals(emptyList(), problems.take(5), "seed=$seed")
        assertTrue(acquired.get() > 0, "seed=$seed control: the lock was taken")
        assertEquals(emptySet<String>(), claims(), "seed=$seed: a claim outlived its attempt or its holder")
        assertEquals(before, TestFs.requireOpenDescriptorCount(), "seed=$seed: a descriptor outlived its attempt or its holder")
        files.forEach { file: File ->
            assertIs<LockAcquisition.Held>(
                CrossProcessLock(deadline = 50.milliseconds, pollInterval = 25.milliseconds).acquire(file, "x"),
                "seed=$seed: ${file.name} stayed busy after the storm",
            ).close()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun claims(): Set<String> {
        val field = CrossProcessLock::class.java.getDeclaredField("CLAIMS").apply { isAccessible = true }
        return (field.get(null) as Set<String>).toSet()
    }

    /** A class loader with the test's own class path and no parent but the platform: a second copy of every library class. */
    private fun isolatedLoader(): URLClassLoader {
        val urls: Array<URL> = System.getProperty("java.class.path").split(File.pathSeparator)
            .filter { it.isNotEmpty() }
            .map { File(it).toURI().toURL() }
            .toTypedArray()
        return URLClassLoader(urls, ClassLoader.getPlatformClassLoader())
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
        /** Hang bounds, never timing assertions. */
        const val HANG_BOUND_SECONDS: Long = 60
        val STORM_BOUND = 120.seconds
        const val WORKERS: Int = 8
        const val STEPS: Int = 150
    }
}

/**
 * Loaded by a second class loader in [CrossProcessLockBreakerR2Test]: one bounded acquire of the library's lock through
 * that loader's own copy of [CrossProcessLock], exactly what a second copy of the library in a plug-in host would run.
 */
object IsolatedLockContender {
    @JvmStatic
    fun contend(lockPath: String, prefix: String): String = runBlocking {
        val outcome: LockAcquisition = CrossProcessLock(deadline = 100.milliseconds, pollInterval = 25.milliseconds)
            .acquire(File(lockPath), prefix)
        (outcome as? LockAcquisition.Held)?.close()
        outcome.toString()
    }
}
