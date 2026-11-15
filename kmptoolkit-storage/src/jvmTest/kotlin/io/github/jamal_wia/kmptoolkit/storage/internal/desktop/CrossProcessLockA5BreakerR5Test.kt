package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileMetadata
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * Group sto-desktop, phase A breaker round 5 (decisions.md D-23), part A5: the round-6 rule for a lock path that is a
 * symbolic link (D-P11: "a NoSuchFileException on a path that is a symbolic link is structural, not a delete behind our back;
 * the link is never opened with CREATE") attacked beyond the plain dangling link the round-4 tests cover:
 *
 * - a link chain whose last link dangles, a link whose target's directory does not exist, a link loop, a link to a directory:
 *   each fails at once (KDoc: "every other failure ... is answered at once"), creates nothing where the link points, and leaves
 *   the claim free (the next attempt on a repaired path locks at once);
 * - a link re-pointed between acquisitions (dangling, then valid, then dangling again): each acquisition answers for the path
 *   as it is then, and a held lock is released normally;
 * - a dangling link that appears during the attempt (between the existence check and the open): `Failed` at once, its target
 *   not created;
 * - the other side of the rule: a real delete of the lock file behind the attempt is still only "busy" and the next poll
 *   creates the file and locks (an over-broad fix would turn it into a failure).
 *
 * Virtual time throughout (the lock polls with a coroutine `delay`); POSIX only (links without privileges).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CrossProcessLockA5BreakerR5Test {

    private val directory: File = Files.createTempDirectory("kmptoolkit-xlock-breaker5-a5").toFile()
    private val prefix = "store.properties"

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun assumeLinks() {
        TestHost.assumePosixFileSemantics("symbolic links without privileges; Windows needs the create-symlink right")
    }

    private fun link(at: File, to: File): File {
        Files.createSymbolicLink(at.toPath(), to.toPath())
        assertTrue(Files.isSymbolicLink(at.toPath()), "control: ${at.name} is a link")
        return at
    }

    /** Acquires [lockFile] and checks the answer is `Failed` well inside the budget; returns the virtual time spent. */
    private suspend fun TestScope.assertFailsAtOnce(lockFile: File, what: String): Duration {
        val lock = CrossProcessLock(deadline = 10.seconds)
        val startedAt: Long = testScheduler.currentTime
        val outcome: LockAcquisition = lock.acquire(lockFile, prefix)
        val spent: Duration = (testScheduler.currentTime - startedAt).milliseconds
        if (outcome is LockAcquisition.Held) outcome.close()
        assertIs<LockAcquisition.Failed>(outcome, "$what: a lock file that can never be opened must fail at once; got $outcome after $spent")
        assertEquals(Duration.ZERO, spent, "$what: the failure repeats on every poll, so it must not be polled at all: $spent")
        return spent
    }

    /** After a failure the path is repaired: the claim must be free, so the next acquisition locks without waiting. */
    private suspend fun TestScope.assertRepairedPathLocksAtOnce(lockFile: File, what: String) {
        Files.deleteIfExists(lockFile.toPath())
        val startedAt: Long = testScheduler.currentTime
        val outcome: LockAcquisition = CrossProcessLock(deadline = 10.seconds).acquire(lockFile, prefix)
        val held: LockAcquisition.Held = assertIs<LockAcquisition.Held>(outcome, "$what: the repaired lock path did not lock: $outcome")
        held.close()
        assertEquals(startedAt, testScheduler.currentTime, "$what: the claim of the failed attempt was still taken")
    }

    @Test
    fun `a link chain whose last link dangles fails at once and creates nothing at the end of the chain`() = runTest {
        assumeLinks()
        val end = File(directory, "end-target")
        val middle: File = link(File(directory, "middle-link"), end)
        val lockFile: File = link(File(directory, "store.properties.lock"), middle)

        assertFailsAtOnce(lockFile, "chain lock -> middle -> missing")

        assertFalse(end.exists(), "the end of the chain was created through the links")
        assertTrue(Files.isSymbolicLink(middle.toPath()) && Files.isSymbolicLink(lockFile.toPath()), "a link of the chain was replaced")
        assertRepairedPathLocksAtOnce(lockFile, "chain")
    }

    @Test
    fun `a link whose target directory does not exist fails at once and creates no directory`() = runTest {
        assumeLinks()
        val missingDirectory = File(directory, "not-mounted")
        val lockFile: File = link(File(directory, "store.properties.lock"), File(missingDirectory, "store.properties.lock"))

        assertFailsAtOnce(lockFile, "link into a missing directory")

        assertFalse(missingDirectory.exists(), "the link's missing directory was created (createParentDirectories through a link)")
        assertRepairedPathLocksAtOnce(lockFile, "missing directory")
    }

    @Test
    fun `a link loop fails at once`() = runTest {
        assumeLinks()
        val other = File(directory, "loop-other")
        val lockFile = File(directory, "store.properties.lock")
        link(other, lockFile)
        link(lockFile, other)

        assertFailsAtOnce(lockFile, "loop lock -> other -> lock")

        assertTrue(Files.isSymbolicLink(lockFile.toPath()), "the looping link was replaced")
        assertRepairedPathLocksAtOnce(lockFile, "loop")
    }

    @Test
    fun `a link to a directory fails at once and leaves the directory untouched`() = runTest {
        assumeLinks()
        val target = File(directory, "a-directory").apply { assertTrue(mkdirs(), "control: the directory was made") }
        val lockFile: File = link(File(directory, "store.properties.lock"), target)

        assertFailsAtOnce(lockFile, "link to a directory")

        assertTrue(target.isDirectory, "the directory the link points to was replaced")
        assertEquals(0, target.listFiles()?.size, "something was created inside the directory the link points to")
        assertRepairedPathLocksAtOnce(lockFile, "directory")
    }

    @Test
    fun `a link re-pointed between acquisitions is judged as it is at each acquisition`() = runTest {
        assumeLinks()
        val target = File(directory, "real-target")
        val lockFile: File = link(File(directory, "store.properties.lock"), target)

        assertFailsAtOnce(lockFile, "first, dangling")
        assertFalse(target.exists(), "control: the target was not created by the failed attempt")

        assertTrue(target.createNewFile(), "control: the target now exists")
        val held: LockAcquisition.Held =
            assertIs<LockAcquisition.Held>(CrossProcessLock(deadline = 10.seconds).acquire(lockFile, prefix), "a link to an existing file is an ordinary lock file")
        held.close()

        assertTrue(target.delete(), "control: the target is gone again")
        assertFailsAtOnce(lockFile, "dangling again after a held lock was released")
        assertTrue(Files.isSymbolicLink(lockFile.toPath()), "the link was replaced")
        assertFalse(target.exists(), "the target was re-created through the link")
    }

    @Test
    fun `a dangling link that appears during the attempt fails it at once and its target is not created`() = runTest {
        assumeLinks()
        val lockFile = File(directory, "store.properties.lock")
        val target = File(directory, "late-target")
        val swapped = AtomicBoolean(false)
        // The link appears between the existence check (no file yet) and the creation of the lock file.
        val files: FileAccess = object : FileAccess by createFileAccess() {
            override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> {
                if (path == lockFile.absolutePath && swapped.compareAndSet(false, true)) link(lockFile, target)
                return createFileAccess().writeAtomically(path, bytes, options)
            }
        }
        val startedAt: Long = testScheduler.currentTime

        val outcome: LockAcquisition = CrossProcessLock(files, deadline = 10.seconds).acquire(lockFile, prefix)

        assertTrue(swapped.get(), "control: the link appeared during the attempt")
        assertIs<LockAcquisition.Failed>(outcome, "a dangling link that appeared during the attempt: got $outcome")
        assertEquals(startedAt, testScheduler.currentTime, "the failure was polled instead of answered at once")
        assertFalse(target.exists(), "the link's target was created through the link")
    }

    @Test
    fun `a lock file deleted behind the attempt is busy, not failed, and the next poll creates it and locks`() = runTest {
        val lockFile = File(directory, "store.properties.lock")
        assertTrue(lockFile.createNewFile(), "control: the lock file exists before the attempt")
        val deleted = AtomicBoolean(false)
        // The file is there when the attempt looks, and gone when it opens: a real delete, not a link.
        val files: FileAccess = object : FileAccess by createFileAccess() {
            override fun metadata(path: String, followLinks: Boolean): FileMetadata? {
                val answer: FileMetadata? = createFileAccess().metadata(path, followLinks)
                if (path == lockFile.absolutePath && answer != null && deleted.compareAndSet(false, true)) {
                    assertTrue(lockFile.delete(), "control: the lock file was deleted behind the attempt")
                }
                return answer
            }
        }

        val outcome: LockAcquisition = CrossProcessLock(files, deadline = 10.seconds).acquire(lockFile, prefix)

        assertTrue(deleted.get(), "control: the delete happened during the attempt")
        val held: LockAcquisition.Held = assertIs<LockAcquisition.Held>(
            outcome,
            "a lock file deleted behind the attempt is created again on the next poll (KDoc); the A-M1 fix must not turn it into $outcome",
        )
        held.close()
        assertTrue(lockFile.isFile && !Files.isSymbolicLink(lockFile.toPath()), "the lock file was re-created as a regular file")
    }
}
