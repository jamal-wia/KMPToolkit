package io.github.jamal_wia.kmptoolkit.storage

import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * Group sto-desktop, round 8 (breaker finding B5-m1, the audit of every "missing file" reading): the plain store over a
 * dangling symbolic link. Unlike the encrypted stores it has no create step that can find "the name taken", so nothing can
 * go round: a read finds no file, and the first write publishes by atomic rename, which replaces the NAME (the link), never
 * its missing target. This is the behaviour the plain store has always had (the 05 notes say it); the test pins it so that a
 * change of it is a decision, and so that the claim "the target is never created" stays true.
 */
class JvmKeyValueStorageDanglingLinkRound8Test {

    private val directory: File = Files.createTempDirectory("kmptoolkit-plain-dangling").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun `a dangling link at the plain store file reads as no file and the first write replaces the link but never creates its target`() {
        TestHost.assumePosixFileSemantics("symbolic links")
        val file = File(directory, "store.properties")
        val target = File(directory, "moved-away.properties")
        Files.createSymbolicLink(file.toPath(), target.toPath())
        assertTrue(Files.isSymbolicLink(file.toPath()), "control: the link is there")
        val storage = JvmKeyValueStorage(file, Dispatchers.IO)

        runBlocking {
            assertEquals(StorageResult.Success(null), storage.get("a"), "a read of a dangling link is no file")
            assertEquals(StorageResult.Success(Unit), storage.put("a", "1"), "the first write succeeds")
            assertEquals(StorageResult.Success("1"), storage.get("a"), "the value reads back")
        }

        assertFalse(target.exists(), "the missing target of the link was created")
        assertFalse(Files.isSymbolicLink(file.toPath()), "the name now holds the store file, not the link")
    }
}
