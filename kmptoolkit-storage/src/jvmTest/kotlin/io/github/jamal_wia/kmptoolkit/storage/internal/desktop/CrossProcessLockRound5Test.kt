package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import io.github.jamal_wia.kmptoolkit.testsupport.TestFs
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.GroupPrincipal
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserPrincipal
import java.nio.file.attribute.UserPrincipalLookupService
import java.nio.file.attribute.UserPrincipalNotFoundException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Group sto-desktop, round 5 (phase A breaker round 3, m4): the lock file that [CrossProcessLock] creates itself on a volume
 * without hard links gets the same owner-only attributes as the one the files layer creates: mode 0600 where the provider has
 * POSIX modes, a DACL of the current user and SYSTEM where it has ACLs, and it fails closed (nothing created) when the DACL
 * cannot be built. The Windows branch is driven through its seams here, on every host; the real Windows result is
 * `CrossProcessLockWindowsAclRound5Test`.
 */
class CrossProcessLockRound5Test {

    private val directory: File = Files.createTempDirectory("kmptoolkit-xlock-round5").toFile()
    private val lockFile: File = File(directory, "store.properties.lock")
    private val prefix = "store.properties"

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private val noLinks: FileAccess = object : FileAccess by createFileAccess() {
        override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> =
            FileResult.Failure(FileError.Unsupported(path))
    }

    private class Principal(private val text: String) : UserPrincipal {
        override fun getName(): String = text
    }

    /** A lookup service that knows only [known] and records every name it was asked for. */
    private class RecordingLookup(private val known: Set<String>) : UserPrincipalLookupService() {
        val asked: MutableList<String> = mutableListOf()

        override fun lookupPrincipalByName(name: String): UserPrincipal {
            asked += name
            if (name !in known) throw UserPrincipalNotFoundException(name)
            return Principal(name)
        }

        override fun lookupPrincipalByGroupName(group: String): GroupPrincipal = throw UnsupportedOperationException()
    }

    private fun aclAttributes(lookup: UserPrincipalLookupService, tokenUser: () -> String = { "CORP\\jdoe" }): LockFileAttributes =
        LockFileAttributes(supportedViews = { _: Path -> setOf("acl") }, tokenUserName = tokenUser, lookup = { _: Path -> lookup })

    @Suppress("UNCHECKED_CAST")
    private fun entriesOf(attribute: FileAttribute<*>): List<AclEntry> = attribute.value() as List<AclEntry>

    @Test
    fun `the fallback asks for the attributes of the lock file and the link path does not`() = runBlocking {
        val asked: MutableList<Path> = CopyOnWriteArrayList()
        val recording = LockFileAttributes(supportedViews = { path: Path -> asked.add(path); emptySet() })

        assertIs<LockAcquisition.Held>(CrossProcessLock(createFileAccess(), lockFileAttributes = recording).acquire(lockFile, prefix)).close()
        assertEquals(emptyList(), asked.toList(), "the link path creates the file through the files layer, which has its own attributes")
        assertTrue(lockFile.delete(), "control: the lock file existed")

        assertIs<LockAcquisition.Held>(CrossProcessLock(noLinks, lockFileAttributes = recording).acquire(lockFile, prefix)).close()

        assertEquals(listOf(lockFile.toPath()), asked.toList(), "the fallback asks for the attributes of exactly the file it creates")
        assertTrue(lockFile.isFile)
    }

    @Test
    fun `a provider with neither a posix nor an acl view still gets its lock file`() = runBlocking {
        val none = LockFileAttributes(supportedViews = { _: Path -> emptySet() })

        assertEquals(0, none.forNewFile(lockFile.toPath()).size)
        assertIs<LockAcquisition.Held>(CrossProcessLock(noLinks, lockFileAttributes = none).acquire(lockFile, prefix)).close()
        assertTrue(lockFile.isFile)
    }

    @Test
    fun `on a POSIX host the fallback creates the lock file with mode 0600`() = runBlocking {
        TestHost.assumePosixFileSemantics("a POSIX mode; the Windows ACL is CrossProcessLockWindowsAclRound5Test")

        assertIs<LockAcquisition.Held>(CrossProcessLock(noLinks).acquire(lockFile, prefix)).close()

        // path-hygiene-ignore: PH2 reads the mode the library set, on a POSIX host only (assumed above)
        val actual: Set<PosixFilePermission> = Files.getPosixFilePermissions(lockFile.toPath())
        // path-hygiene-ignore: PH2 the mode a POSIX host must show, on a POSIX host only (assumed above)
        val ownerOnly: Set<PosixFilePermission> = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        assertEquals(ownerOnly, actual)
    }

    @Test
    fun `the ACL attribute names the qualified token user and SYSTEM, allows them everything and inherits nothing`() {
        val lookup = RecordingLookup(setOf("CORP\\jdoe", "NT AUTHORITY\\SYSTEM"))

        val attributes: Array<FileAttribute<*>> = aclAttributes(lookup).forNewFile(lockFile.toPath())

        val attribute: FileAttribute<*> = attributes.single()
        assertEquals("acl:acl", attribute.name())
        val entries: List<AclEntry> = entriesOf(attribute)
        assertEquals(listOf("CORP\\jdoe", "NT AUTHORITY\\SYSTEM"), entries.map { it.principal().name })
        assertTrue(entries.all { it.type() == AclEntryType.ALLOW }, "only allow entries: $entries")
        assertTrue(entries.all { it.permissions() == AclEntryPermission.values().toSet() }, "full control for both: $entries")
        assertTrue(entries.all { it.flags() == emptySet<AclEntryFlag>() }, "a file entry carries no inheritance flag: $entries")
        assertEquals(listOf("CORP\\jdoe", "NT AUTHORITY\\SYSTEM"), lookup.asked, "no bare user name is ever looked up")
    }

    @Test
    fun `SYSTEM falls back to its unqualified name when the authority name is not known`() {
        val lookup = RecordingLookup(setOf("CORP\\jdoe", "SYSTEM"))

        val entries: List<AclEntry> = entriesOf(aclAttributes(lookup).forNewFile(lockFile.toPath()).single())

        assertEquals(listOf("CORP\\jdoe", "SYSTEM"), entries.map { it.principal().name })
        assertEquals(listOf("CORP\\jdoe", "NT AUTHORITY\\SYSTEM", "SYSTEM"), lookup.asked)
    }

    @Test
    fun `an ACL that cannot be built fails the acquisition and creates no lock file`() = runBlocking {
        val before: Long = TestFs.requireOpenDescriptorCount()
        val unknownUser = aclAttributes(RecordingLookup(setOf("NT AUTHORITY\\SYSTEM")))
        val unknownSystem = aclAttributes(RecordingLookup(setOf("CORP\\jdoe")))
        val noModule = aclAttributes(RecordingLookup(emptySet()), tokenUser = { throw NoClassDefFoundError("com/sun/security/auth/module/NTSystem") })
        val unqualified = aclAttributes(RecordingLookup(emptySet()), tokenUser = { throw IllegalStateException("NTSystem reported no qualified user name") })

        for ((label: String, attributes: LockFileAttributes) in listOf("unknown user" to unknownUser, "unknown SYSTEM" to unknownSystem, "no NTSystem module" to noModule, "no domain" to unqualified)) {
            val outcome: LockAcquisition = CrossProcessLock(noLinks, lockFileAttributes = attributes).acquire(lockFile, prefix)

            assertIs<LockAcquisition.Failed>(outcome, "$label: fails closed, never creates the file with wider access ($outcome)")
            assertFalse(lockFile.exists(), "$label: a lock file was created without the owner-only DACL")
            assertEquals(emptyList(), directory.list()!!.toList(), "$label: nothing is left in the directory")
        }

        assertEquals(before, TestFs.requireOpenDescriptorCount(), "a failed creation leaves no descriptor")
        assertIs<LockAcquisition.Held>(CrossProcessLock(createFileAccess()).acquire(lockFile, prefix), "the claim was released: a later acquisition works").close()
    }
}
