package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import java.io.File
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ClosedByInterruptException
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Group sto-desktop, round 7 (breaker finding B4-M1 and observation O12).
 *
 * - A lock path that is not a regular file is refused with [LockAcquisition.Failed] at once, never opened: a named pipe would
 *   block `open(2)` forever (past the budget and past cancellation, while the store's in-process lock is held), and a device or
 *   a socket is no lock file either. The control of every case: the lock still works once the path is repaired (the in-process
 *   claim was released), and nothing was created or changed where the path points.
 * - A `tryLock` that is interrupted ([ClosedByInterruptException]) is a failure of that call, not "this file system has no
 *   locks": the plain store must not degrade to unlocked writes on it.
 *
 * Real files in a directory the test creates and deletes; hang bounds only; POSIX hosts (mkfifo, links).
 */
class CrossProcessLockRound7Test {

    private val directory: File = Files.createTempDirectory("kmptoolkit-xlock-round7").toFile()
    private val prefix = "store.properties"

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    /** After a refusal the path is repaired: the claim must be free, so the next acquisition locks. */
    private fun assertRepairedPathLocks(lockFile: File, what: String) {
        Files.deleteIfExists(lockFile.toPath())
        val outcome: LockAcquisition = NamedPipes.answersWithinBound("repaired-$what", emptyList()) {
            CrossProcessLock(deadline = 10.seconds).acquire(lockFile, prefix)
        }
        assertIs<LockAcquisition.Held>(outcome, "$what: the repaired lock path did not lock (a claim leaked?): $outcome").close()
    }

    @Test
    fun `a named pipe at the lock path fails at once and the repaired path locks`() {
        val lockFile = File(directory, "pipe.lock")
        NamedPipes.createOrSkip(lockFile)

        val outcome: LockAcquisition = NamedPipes.answersWithinBound("pipe", listOf(lockFile)) {
            CrossProcessLock(deadline = 1.seconds).acquire(lockFile, prefix)
        }

        val failed: LockAcquisition.Failed = assertIs<LockAcquisition.Failed>(outcome, "a pipe at the lock path must fail at once: $outcome")
        assertTrue("not a regular file" in failed.cause.message.orEmpty(), "the cause names the problem: ${failed.cause}")
        assertFalse(Files.isRegularFile(lockFile.toPath()), "the pipe was replaced by a file")
        assertRepairedPathLocks(lockFile, "pipe")
    }

    @Test
    fun `a link to a named pipe is refused too and the pipe is not opened`() {
        TestHost.assumePosixFileSemantics("symbolic links without privileges")
        val pipe = File(directory, "target.pipe")
        NamedPipes.createOrSkip(pipe)
        val lockFile = File(directory, "link.lock")
        Files.createSymbolicLink(lockFile.toPath(), pipe.toPath())

        val outcome: LockAcquisition = NamedPipes.answersWithinBound("link-to-pipe", listOf(pipe)) {
            CrossProcessLock(deadline = 1.seconds).acquire(lockFile, prefix)
        }

        assertIs<LockAcquisition.Failed>(outcome, "a link that leads to a pipe must fail at once: $outcome")
        assertTrue(Files.isSymbolicLink(lockFile.toPath()), "control: the link is still there, untouched")
        assertRepairedPathLocks(lockFile, "link-to-pipe")
    }

    @Test
    fun `a link to a device is refused instead of being locked`() {
        TestHost.assumePosixFileSemantics("a device node and links")
        val device = File("/dev/null")
        org.junit.Assume.assumeTrue("no /dev/null here", device.exists() && !Files.isRegularFile(device.toPath()))
        val lockFile = File(directory, "device.lock")
        Files.createSymbolicLink(lockFile.toPath(), device.toPath())

        val outcome: LockAcquisition = NamedPipes.answersWithinBound("device", emptyList()) {
            CrossProcessLock(deadline = 1.seconds).acquire(lockFile, prefix)
        }
        if (outcome is LockAcquisition.Held) outcome.close()

        assertIs<LockAcquisition.Failed>(outcome, "a device is no lock file: $outcome")
        assertRepairedPathLocks(lockFile, "device")
    }

    @Test
    fun `a socket at the lock path is refused at once`() {
        TestHost.assumePosixFileSemantics("unix domain sockets")
        // A short directory name: a Unix domain socket path is limited to about 100 bytes.
        val shortDirectory: File = Files.createTempDirectory("k").toFile()
        val lockFile = File(shortDirectory, "s.lock")
        val server: ServerSocketChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            try {
                server.bind(UnixDomainSocketAddress.of(lockFile.toPath()))
            } catch (error: IOException) {
                org.junit.Assume.assumeTrue("a socket cannot be bound at this path (${error.message})", false)
            }
            assertTrue(lockFile.exists() && !Files.isRegularFile(lockFile.toPath()), "control: a socket sits at the lock path")

            val outcome: LockAcquisition = NamedPipes.answersWithinBound("socket", emptyList()) {
                CrossProcessLock(deadline = 1.seconds).acquire(lockFile, prefix)
            }

            assertIs<LockAcquisition.Failed>(outcome, "a socket at the lock path must fail at once: $outcome")
            server.close()
            assertRepairedPathLocks(lockFile, "socket")
        } finally {
            server.close()
            shortDirectory.deleteRecursively()
        }
    }

    @Test
    fun `an interrupted tryLock is a failure of the call and a file system that refuses locks is still not supported`() {
        val interrupted = File(directory, "interrupted.lock")
        val outcome: LockAcquisition = NamedPipes.answersWithinBound("interrupted", emptyList()) {
            CrossProcessLock(deadline = 1.seconds, tryLock = { throw ClosedByInterruptException() }).acquire(interrupted, prefix)
        }
        val failed: LockAcquisition.Failed = assertIs<LockAcquisition.Failed>(
            outcome,
            "an interrupt is not a file system without locks: the plain store would write unlocked on $outcome",
        )
        assertIs<ClosedByInterruptException>(failed.cause)

        val refused = File(directory, "refused.lock")
        val control: LockAcquisition = NamedPipes.answersWithinBound("refused", emptyList()) {
            CrossProcessLock(deadline = 1.seconds, tryLock = { throw IOException("locks are not supported") }).acquire(refused, prefix)
        }
        assertIs<LockAcquisition.NotSupported>(control, "control: a plain IOException from tryLock is still 'no locks here'")
        assertEquals(true, Files.exists(refused.toPath()), "control: the lock file was created in both cases")
        assertRepairedPathLocks(interrupted, "interrupted")
    }
}
