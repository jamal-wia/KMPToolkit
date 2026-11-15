package io.github.jamal_wia.kmptoolkit.smoke.storage

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume.assumeFalse

/**
 * The cleanup of a killed run (breaker round 2: m2). [OnceRemoval] and [ShutdownCleanup] are checked in this process; the signal itself is
 * checked for real: a separate JVM ([ShutdownChild]) is sent SIGTERM and must remove its marker's item and its directory on the way out,
 * and a SIGKILL control shows that the check can tell, and that the limit the docs state is real. Every wait has a hard bound.
 */
class ShutdownTest {

    private val root: File = File("build/shutdown-test").absoluteFile

    @BeforeTest
    fun setUp() {
        root.deleteRecursively()
        check(root.mkdirs()) { "could not create $root" }
    }

    @AfterTest
    fun tearDown() {
        root.deleteRecursively()
    }

    // --- OnceRemoval ---

    @Test
    fun `the inner removal runs once however many callers ask, and a later caller returns after it`() {
        val calls: AtomicInteger = AtomicInteger()
        val once: OnceRemoval = OnceRemoval(object : KeyItemRemoval {
            override fun remove(expectPresent: Boolean) {
                calls.incrementAndGet()
            }
        })
        once.remove(expectPresent = true)
        once.remove(expectPresent = false)
        once.remove(expectPresent = false)
        assertEquals(1, calls.get())
    }

    @Test
    fun `a second caller waits for the first one to finish instead of returning under it`() {
        val inside: CountDownLatch = CountDownLatch(1)
        val release: CountDownLatch = CountDownLatch(1)
        val once: OnceRemoval = OnceRemoval(object : KeyItemRemoval {
            override fun remove(expectPresent: Boolean) {
                inside.countDown()
                release.await(HARD_TEST_BOUND_SECONDS, TimeUnit.SECONDS)
            }
        })
        val first: CompletableFuture<Unit> = CompletableFuture.supplyAsync { once.remove(expectPresent = true) }
        assertTrue(inside.await(HARD_TEST_BOUND_SECONDS, TimeUnit.SECONDS), "the first caller is inside the removal")
        val second: CompletableFuture<Unit> = CompletableFuture.supplyAsync { once.remove(expectPresent = false) }
        assertFalse(second.isDone, "the second caller has not returned while the first is still removing: no hook may let the process end under the run")
        release.countDown()
        second.get(HARD_TEST_BOUND_SECONDS, TimeUnit.SECONDS)
        first.get(HARD_TEST_BOUND_SECONDS, TimeUnit.SECONDS)
    }

    @Test
    fun `a failure of the first removal is the failure of every later caller, never a silent success`() {
        val once: OnceRemoval = OnceRemoval(object : KeyItemRemoval {
            override fun remove(expectPresent: Boolean): Unit = throw SmokeFailure("could not delete")
        })
        assertEquals("could not delete", assertFailsWith<SmokeFailure> { once.remove(expectPresent = true) }.message)
        assertEquals("could not delete", assertFailsWith<SmokeFailure> { once.remove(expectPresent = false) }.message)
    }

    @Test
    fun `a caller that waits longer than its bound for the first one fails`() {
        val inside: CountDownLatch = CountDownLatch(1)
        val release: CountDownLatch = CountDownLatch(1)
        val once: OnceRemoval = OnceRemoval(
            object : KeyItemRemoval {
                override fun remove(expectPresent: Boolean) {
                    inside.countDown()
                    release.await(HARD_TEST_BOUND_SECONDS, TimeUnit.SECONDS)
                }
            },
            waitMillis = 100L,
        )
        val first: CompletableFuture<Unit> = CompletableFuture.supplyAsync { once.remove(expectPresent = true) }
        try {
            assertTrue(inside.await(HARD_TEST_BOUND_SECONDS, TimeUnit.SECONDS))
            val failure: SmokeFailure = assertFailsWith<SmokeFailure> { once.remove(expectPresent = false) }
            assertTrue("did not finish" in failure.message.orEmpty(), failure.message)
        } finally {
            release.countDown()
            first.get(HARD_TEST_BOUND_SECONDS, TimeUnit.SECONDS)
        }
    }

    // --- ShutdownCleanup, in this process ---

    @Test
    fun `the cleanup removes the armed item without insisting it exists, and deletes the directory`() {
        val directory: File = File(root, "run").apply { mkdirs() }
        File(directory, "f").writeText("x")
        val told: MutableList<Boolean> = mutableListOf()
        val reports: MutableList<String> = mutableListOf()
        val cleanup: ShutdownCleanup = ShutdownCleanup(directory, report = { reports += it })
        cleanup.arm(object : KeyItemRemoval {
            override fun remove(expectPresent: Boolean) {
                told += expectPresent
            }
        })
        cleanup.runNow()
        assertEquals(listOf(false), told, "a killed run may have died before the key was made: no control that the item exists")
        assertFalse(directory.exists())
        assertEquals(emptyList(), reports)
    }

    @Test
    fun `the cleanup after the run's own removal does not remove twice and finds nothing left`() {
        val directory: File = File(root, "run").apply { mkdirs() }
        val calls: AtomicInteger = AtomicInteger()
        val cleanup: ShutdownCleanup = ShutdownCleanup(directory)
        val runsRemoval: KeyItemRemoval = cleanup.arm(object : KeyItemRemoval {
            override fun remove(expectPresent: Boolean) {
                calls.incrementAndGet()
            }
        })
        runsRemoval.remove(expectPresent = true)
        assertTrue(cleanup.deleteDirectory())
        cleanup.runNow()
        assertEquals(1, calls.get(), "the hook after a normal end is idempotent")
    }

    @Test
    fun `a failing removal is reported and the directory is deleted anyway`() {
        val directory: File = File(root, "run").apply { mkdirs() }
        val reports: MutableList<String> = mutableListOf()
        val cleanup: ShutdownCleanup = ShutdownCleanup(directory, report = { reports += it })
        cleanup.arm(object : KeyItemRemoval {
            override fun remove(expectPresent: Boolean): Unit = throw SmokeFailure("OSStatus -25293")
        })
        cleanup.runNow()
        assertEquals(1, reports.size, reports.toString())
        assertTrue("-25293" in reports.single() && ITEM_LABEL in reports.single(), "the failure names what may be left: ${reports.single()}")
        assertFalse(directory.exists(), "the data is removed although the item removal failed")
    }

    @Test
    fun `a removal that hangs does not hold the cleanup longer than its bound`() {
        val directory: File = File(root, "run").apply { mkdirs() }
        val release: CountDownLatch = CountDownLatch(1)
        val reports: MutableList<String> = mutableListOf()
        val cleanup: ShutdownCleanup = ShutdownCleanup(directory, boundMillis = 100L, report = { reports += it })
        cleanup.arm(object : KeyItemRemoval {
            override fun remove(expectPresent: Boolean) {
                release.await(HARD_TEST_BOUND_SECONDS, TimeUnit.SECONDS)
            }
        })
        try {
            cleanup.runNow()
            assertTrue(reports.single().contains("time limit"), reports.toString())
            assertFalse(directory.exists(), "the directory is still deleted after a removal that timed out")
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `a cleanup that was never armed only deletes the directory`() {
        val directory: File = File(root, "run").apply { mkdirs() }
        ShutdownCleanup(directory).runNow()
        assertFalse(directory.exists())
    }

    // --- a real signal, in a separate JVM ---

    @Test
    fun `SIGTERM runs the cleanup of a run that is killed from outside`() {
        assumeFalse("Process.destroy() is TerminateProcess on Windows: no shutdown hooks", isWindows())
        val directory: File = File(root, "run-term").apply { mkdirs() }
        val marker: File = File(root, "marker-term.txt")
        val child: Process = startChild(directory, marker)
        try {
            awaitReady(child)
            assertTrue(File(directory, "left-by-the-run.txt").isFile, "control: the child made its data before it was stopped")
            assertFalse(marker.exists(), "control: nothing has been removed while the child runs")
            child.destroy()
            assertTrue(child.waitFor(HARD_TEST_BOUND_SECONDS, TimeUnit.SECONDS), "the child ends after SIGTERM")
            assertEquals(SIGTERM_EXIT, child.exitValue(), "it ended by the signal, not by itself")
            assertEquals("removed expectPresent=false", marker.readText(), "the shutdown hook ran the armed key item removal")
            assertFalse(directory.exists(), "the hook deleted the run's directory")
        } finally {
            child.destroyForcibly()
        }
    }

    @Test
    fun `SIGKILL is the limit and nothing runs, which is what the documentation and the Gradle check say`() {
        assumeFalse("no POSIX signals on Windows", isWindows())
        val directory: File = File(root, "run-kill").apply { mkdirs() }
        val marker: File = File(root, "marker-kill.txt")
        val child: Process = startChild(directory, marker)
        try {
            awaitReady(child)
            child.destroyForcibly()
            assertTrue(child.waitFor(HARD_TEST_BOUND_SECONDS, TimeUnit.SECONDS))
            assertFalse(marker.exists(), "a SIGKILLed JVM runs no hook: the control that the SIGTERM test can fail")
            assertTrue(directory.exists(), "the directory of a SIGKILLed run stays")
        } finally {
            child.destroyForcibly()
        }
    }

    private fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows")

    private fun startChild(directory: File, marker: File): Process {
        val java: String = File(File(System.getProperty("java.home"), "bin"), "java").path
        return ProcessBuilder(java, "-cp", System.getProperty("java.class.path"), ShutdownChild::class.java.name, directory.path, marker.path)
            .redirectErrorStream(true)
            .start()
    }

    /** Waits (bounded) for the child's `ready` line; the child makes its data and arms its cleanup before it prints it. */
    private fun awaitReady(child: Process) {
        val line: CompletableFuture<String?> = CompletableFuture.supplyAsync { child.inputStream.bufferedReader().readLine() }
        assertEquals("ready", line.get(HARD_TEST_BOUND_SECONDS, TimeUnit.SECONDS), "the child must start and arm itself")
    }

    private companion object {
        const val HARD_TEST_BOUND_SECONDS: Long = 30L
        const val SIGTERM_EXIT: Int = 143
    }
}
