package io.github.jamal_wia.kmptoolkit.storage

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.CrossProcessLock
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

/**
 * Plan S7 and 6.1 T10 on a POSIX host, for the plain desktop store: its data file, its lock file and a directory the store
 * creates are owner-only; an existing directory is never changed; an external `chmod` of the data file is undone by the next
 * write. The Windows ACL half and the file-system mechanics are `kmptoolkit-files`' own tests.
 */
class JvmKeyValueStorageOwnerOnlyTest {

    private val root: File = Files.createTempDirectory("kmptoolkit-owneronly").toFile()

    @BeforeTest
    fun requirePosix() {
        TestHost.assumePosixFileSemantics("POSIX modes; the Windows ACL is the files module's test")
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    // path-hygiene-ignore: PH2 reads a POSIX mode, on a POSIX host only (assumed in requirePosix)
    private fun mode(file: File): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))

    @Test
    fun `the data file, the lock file and a directory the store creates are owner-only`() = runBlocking {
        val created = File(root, "created")
        val storage: KeyValueStorage = createKeyValueStorage(created, StorageConfig("x"))

        assertEquals(StorageResult.Success(Unit), storage.put("k", "v"))

        val data = File(created, "${plainStoreId("x")}.properties")
        assertEquals("rw-------", mode(data), "the data file")
        assertEquals("rw-------", mode(File(created, CrossProcessLock.lockNameOf(data.name))), "the lock file")
        assertEquals("rwx------", mode(created), "a directory the store created")
    }

    @Test
    fun `an existing directory keeps its mode and an external chmod of the data file is undone by the next write`() = runBlocking {
        val existing = File(root, "existing").also { it.mkdirs() }
        // path-hygiene-ignore: PH2 sets a POSIX mode on a directory the test made, POSIX host only
        Files.setPosixFilePermissions(existing.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        val storage: KeyValueStorage = createKeyValueStorage(existing, StorageConfig("x"))
        assertEquals(StorageResult.Success(Unit), storage.put("k", "v"))
        val data = File(existing, "${plainStoreId("x")}.properties")
        assertEquals("rwxr-xr-x", mode(existing), "an existing directory is never tightened")

        // path-hygiene-ignore: PH2 an outsider widens the data file's POSIX mode
        Files.setPosixFilePermissions(data.toPath(), setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ))
        assertEquals("rw-r--r--", mode(data), "control: the outsider's chmod took effect")
        assertEquals(StorageResult.Success(Unit), storage.put("k", "v2"))

        assertEquals("rw-------", mode(data), "the next write replaced the file with an owner-only one")
    }
}
