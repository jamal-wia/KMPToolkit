package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest

/**
 * Group sto-desktop, round 6 (phase A breaker round 4, M1: a lock path that is a dangling symbolic link). The breaker class
 * `CrossProcessLockBreakerR4Test` proves the answer (`Failed` at once, `OperationFailed` through a store). These tests pin
 * what the fix must not do: it must never create the link's target (opening the link with `CREATE` would), it must not
 * hold the claim of the lock file afterwards, and a link whose target exists is an ordinary lock file.
 */
class CrossProcessLockRound6Test {

    private val directory: File = Files.createTempDirectory("kmptoolkit-xlock-round6").toFile()
    private val prefix = "store.properties"

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun link(at: File, target: File) {
        TestHost.assumePosixFileSemantics("a symbolic link without privileges; Windows needs the create-symlink right")
        Files.createSymbolicLink(at.toPath(), target.toPath())
        assertTrue(Files.isSymbolicLink(at.toPath()), "control: the lock path is a link")
    }

    @Test
    fun `a dangling link is never followed with CREATE, so its target is not created`() = runTest {
        val target = File(directory, "gone-target")
        val lockFile = File(directory, "store.properties.lock")
        link(lockFile, target)
        assertFalse(target.exists(), "control: the target does not exist before")

        val outcome: LockAcquisition = CrossProcessLock(deadline = 10.seconds).acquire(lockFile, prefix)

        assertIs<LockAcquisition.Failed>(outcome)
        assertFalse(target.exists(), "the acquisition created the file the link points to")
        assertTrue(Files.isSymbolicLink(lockFile.toPath()), "the link itself was replaced or removed")
    }

    @Test
    fun `the claim is released after the failure, so a repaired lock path is usable at once`() = runTest {
        val target = File(directory, "gone-target")
        val lockFile = File(directory, "store.properties.lock")
        link(lockFile, target)
        val lock = CrossProcessLock(deadline = 10.seconds)
        assertIs<LockAcquisition.Failed>(lock.acquire(lockFile, prefix), "control: the dangling link fails the acquisition")

        Files.delete(lockFile.toPath())
        val repaired: LockAcquisition = lock.acquire(lockFile, prefix)

        val held: LockAcquisition.Held = assertIs<LockAcquisition.Held>(repaired, "the lock file's claim stayed taken after a failed attempt")
        held.close()
    }

    @Test
    fun `a link whose target exists is an ordinary lock file and the lock is taken on the target`() = runTest {
        val target = File(directory, "real-target")
        assertTrue(target.createNewFile(), "control: the target exists")
        val lockFile = File(directory, "store.properties.lock")
        link(lockFile, target)

        val held: LockAcquisition.Held = assertIs<LockAcquisition.Held>(CrossProcessLock(deadline = 10.seconds).acquire(lockFile, prefix))

        assertTrue(Files.isSymbolicLink(lockFile.toPath()), "the link was replaced")
        assertEquals(0L, target.length(), "the lock file's target was written to")
        held.close()
    }
}
