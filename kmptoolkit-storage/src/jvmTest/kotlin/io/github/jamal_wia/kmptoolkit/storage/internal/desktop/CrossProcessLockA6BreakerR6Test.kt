package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import java.io.File
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Group sto-desktop, phase A breaker round 6 (decisions.md D-23), part A6: the round-7 fix 29a711eb of [CrossProcessLock]
 * (the lock path is checked to be a regular file before it is opened; `ClosedByInterruptException` from `tryLock` is
 * `Attempt.Broke`, a parked channel it closed "is therefore no longer parked") attacked on the paths the round-7 tests do not
 * name:
 *
 * - **A real interrupt on a parked channel and on a fresh one** (the round-7 tests throw `ClosedByInterruptException` from the
 *   seam; here the thread is really interrupted and the JDK's own `tryLock` runs). Whatever the JDK does with the interrupt, the
 *   answer must never be `NotSupported` (05: "it is not taken for a file system without locks"), no channel of a failed attempt
 *   may stay open, and no closed channel may stay in `PARKED`: otherwise every later attempt on that file reuses it, `tryLock`
 *   throws `ClosedChannelException` (an `IOException`), and the lock answers `NotSupported` for good, so the plain store writes
 *   unlocked forever. (Observed on JDK 21: `FileChannelImpl.tryLock` is not interruptible, so the interrupted thread simply
 *   gets the lock; see the round-30 report, m1.)
 * - **A parked channel whose file was replaced by a named pipe.** The parked channel is reused without the guard (it is never
 *   reopened); its file identity must be checked first, so the pipe is refused and never opened, and the stale channel closed.
 * - **A regular lock file replaced by a named pipe between its creation and the open** (through the `FileAccess` seam, after
 *   `writeAtomically` created it): refused at once, never opened.
 *
 * Real files in a directory the test creates and deletes; hang bounds only (a named pipe would block `open(2)`: every call that
 * can meet one runs under [NamedPipes.answersWithinBound]). POSIX only where a pipe is needed.
 */
class CrossProcessLockA6BreakerR6Test {

    private val directory: File = Files.createTempDirectory("kmptoolkit-xlock-breaker6-a6").toFile()
    private val prefix = "store.properties"

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    /** A `tryLock` seam that records every channel it is handed and acts per visit. */
    private class RecordingTryLock(private val act: (visit: Int, channel: FileChannel) -> FileLock?) : (FileChannel) -> FileLock? {
        val channels: MutableList<FileChannel> = CopyOnWriteArrayList()
        val openWhenHanded: MutableList<Boolean> = CopyOnWriteArrayList()
        private val visits = AtomicInteger()

        override fun invoke(channel: FileChannel): FileLock? {
            channels += channel
            openWhenHanded += channel.isOpen
            return act(visits.incrementAndGet(), channel)
        }
    }

    private fun acquire(lock: CrossProcessLock, lockFile: File, label: String, pipes: List<File> = emptyList()): LockAcquisition =
        NamedPipes.answersWithinBound(label, pipes) { lock.acquire(lockFile, prefix) }

    @Test
    fun `a real interrupt during tryLock on a parked channel never degrades the lock and leaves no closed channel parked`() {
        val lockFile = File(directory, "parked-interrupt.lock")
        val seam = RecordingTryLock { visit: Int, channel: FileChannel ->
            when (visit) {
                // A second copy of the library in this JVM holds the file: this channel is parked (never closed, see the KDoc).
                1 -> throw OverlappingFileLockException()
                // The thread is interrupted while it retries on the parked channel; the real JDK call decides what happens.
                2 -> {
                    Thread.currentThread().interrupt()
                    channel.tryLock()
                }
                else -> channel.tryLock()
            }
        }
        val lock = CrossProcessLock(deadline = 10.seconds, pollInterval = 1.milliseconds, tryLock = seam)

        val first: LockAcquisition = acquire(lock, lockFile, "parked-then-interrupted")
        assertEquals(2, seam.channels.size, "control: the second attempt retried on the parked channel")
        assertSame(seam.channels[0], seam.channels[1], "control: the retry reused the parked channel")
        assertFalse(
            first is LockAcquisition.NotSupported,
            "05: an interrupted thread 'is not taken for a file system without locks', so the plain store never writes unlocked; got $first",
        )
        if (first is LockAcquisition.Held) first.close()

        val second: LockAcquisition = acquire(lock, lockFile, "after-interrupt")
        assertEquals(3, seam.channels.size, "control: the next acquisition made one attempt")
        assertTrue(
            seam.openWhenHanded[2],
            "the attempt after the interrupt was handed a closed channel (a closed channel stayed in PARKED): tryLock then " +
                "throws ClosedChannelException and the lock answers NotSupported for good, so the plain store writes unlocked; " +
                "first=$first second=$second",
        )
        val held: LockAcquisition.Held = assertIs<LockAcquisition.Held>(second, "the lock must work after an interrupt: first=$first second=$second")
        held.close()
        println("A6 parked interrupt: first=${first::class.simpleName} (JDK tryLock under an interrupt)")
    }

    @Test
    fun `a real interrupt during tryLock on a fresh channel never degrades the lock, leaks no channel and frees the claim`() {
        val lockFile = File(directory, "fresh-interrupt.lock")
        val seam = RecordingTryLock { visit: Int, channel: FileChannel ->
            if (visit == 1) Thread.currentThread().interrupt()
            channel.tryLock()
        }
        val lock = CrossProcessLock(deadline = 10.seconds, tryLock = seam)

        val first: LockAcquisition = acquire(lock, lockFile, "fresh-interrupted")
        assertFalse(first is LockAcquisition.NotSupported, "an interrupt is not a file system without locks: $first")
        if (first is LockAcquisition.Held) first.close()
        assertFalse(seam.channels.single().isOpen, "the channel of the interrupted attempt must not stay open (a descriptor leak): $first")

        val second: LockAcquisition = acquire(lock, lockFile, "fresh-after-interrupt")
        assertTrue(seam.openWhenHanded[1], "the next attempt must get an open channel")
        assertIs<LockAcquisition.Held>(second, "the claim was left behind by the interrupted attempt: first=$first second=$second").close()
        println("A6 fresh interrupt: first=${first::class.simpleName} (JDK tryLock under an interrupt)")
    }

    @Test
    fun `a parked channel whose lock file was replaced by a named pipe is dropped and the pipe is refused, never opened`() {
        val lockFile = File(directory, "parked-pipe.lock")
        val seam = RecordingTryLock { visit: Int, channel: FileChannel ->
            if (visit == 1) throw OverlappingFileLockException()
            channel.tryLock()
        }
        // The budget is short and the poll long: one attempt parks the channel, the budget ends while it waits.
        val parking = CrossProcessLock(deadline = 50.milliseconds, pollInterval = 10.seconds, tryLock = seam)
        val parked: LockAcquisition = acquire(parking, lockFile, "park")
        assertEquals(LockAcquisition.TimedOut, parked, "control: busy until the budget ended")
        assertTrue(seam.channels.single().isOpen, "control: the channel is parked (kept open)")

        assertTrue(lockFile.delete(), "control: the lock file existed")
        NamedPipes.createOrSkip(lockFile)

        val outcome: LockAcquisition = acquire(CrossProcessLock(deadline = 1.seconds, tryLock = seam), lockFile, "pipe", listOf(lockFile))
        if (outcome is LockAcquisition.Held) outcome.close()
        assertEquals(1, seam.channels.size, "the stale parked channel was reused for a file that is now a named pipe (a lock on a deleted inode)")
        assertIs<LockAcquisition.Failed>(outcome, "a named pipe at the lock path must fail at once: $outcome")
        assertFalse(seam.channels.single().isOpen, "the stale parked channel must be closed when it is dropped (a descriptor leak)")
        assertFalse(Files.isRegularFile(lockFile.toPath()), "the pipe was replaced")
    }

    @Test
    fun `a lock file replaced by a named pipe right after its creation is refused at once and never opened`() {
        val lockFile = File(directory, "swapped.lock")
        val swapped = AtomicBoolean(false)
        // ensureLockFile sees no file, writeAtomically creates it, then another program swaps it for a pipe before the open.
        val files: FileAccess = object : FileAccess by createFileAccess() {
            override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> {
                val result: FileResult<Unit> = createFileAccess().writeAtomically(path, bytes, options)
                if (path == lockFile.absolutePath && swapped.compareAndSet(false, true)) {
                    assertTrue(lockFile.isFile, "control: the lock file was created as a regular file")
                    assertTrue(lockFile.delete(), "control: the regular lock file was deleted")
                    NamedPipes.createOrSkip(lockFile)
                }
                return result
            }
        }

        val outcome: LockAcquisition = acquire(CrossProcessLock(files, deadline = 10.seconds), lockFile, "swapped", listOf(lockFile))
        if (outcome is LockAcquisition.Held) outcome.close()

        assertTrue(swapped.get(), "control: the swap happened during the attempt")
        assertIs<LockAcquisition.Failed>(outcome, "a pipe swapped in after creation must be refused at once: $outcome")
        assertFalse(Files.isRegularFile(lockFile.toPath()), "the pipe was replaced by the library")
    }
}
