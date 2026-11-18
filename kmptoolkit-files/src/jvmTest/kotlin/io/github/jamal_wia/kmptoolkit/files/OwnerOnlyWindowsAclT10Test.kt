package io.github.jamal_wia.kmptoolkit.files

import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.UserPrincipalLookupService
import com.sun.security.auth.module.NTSystem
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume

/**
 * T10 on a Windows host: a file or directory made with `ownerOnly` carries a DACL of allow entries for the current user
 * and SYSTEM only, with none of the entries its parent would have given it. Skipped, with this reason, on every other
 * host: the POSIX half is [OwnerOnlyPosixT10Test].
 */
class OwnerOnlyWindowsAclT10Test {
    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        Assume.assumeTrue(
            "needs a Windows host with an ACL file attribute view (the POSIX half is OwnerOnlyPosixT10Test)",
            FileSystems.getDefault().supportedFileAttributeViews().contains("acl") &&
                !FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        )
        root = File(TestEnv.newRoot())
    }

    @AfterTest
    fun tearDown() {
        if (this::root.isInitialized) TestEnv.deleteTree(root.path)
    }

    private fun acl(file: File): List<AclEntry> =
        Files.getFileAttributeView(file.toPath(), AclFileAttributeView::class.java).acl

    private fun allowedNames(file: File): List<String> {
        val entries: List<AclEntry> = acl(file)
        assertTrue(entries.all { it.type() == AclEntryType.ALLOW }, "only allow entries: $entries")
        return entries.map { it.principal().name }.sorted()
    }

    /** The user of the process token as the JDK's NTSystem reports it, domain included; never `user.name`. */
    private fun tokenUser(): String {
        val account: NTSystem = NTSystem()
        return account.domain + "\\" + account.name
    }

    /** The name Windows reports for SYSTEM: the account part is localised on some editions (ru-RU for one). */
    private fun systemName(): String {
        val lookup: UserPrincipalLookupService = FileSystems.getDefault().userPrincipalLookupService
        return try {
            lookup.lookupPrincipalByName("NT AUTHORITY\\SYSTEM").name
        } catch (_: java.io.IOException) {
            lookup.lookupPrincipalByName("SYSTEM").name
        }
    }

    private fun expectedNames(): List<String> {
        val user: String = FileSystems.getDefault().userPrincipalLookupService.lookupPrincipalByName(tokenUser()).name
        return listOf(user, systemName()).sorted()
    }

    @Test
    fun `an ownerOnly write leaves a file whose DACL names only the current user and SYSTEM`() {
        val files: FileAccess = createFileAccess()
        val path: String = File(root, "secret").path
        assertTrue(files.writeAtomically(path, byteArrayOf(1, 2), WriteOptions(ownerOnly = true)).isSuccess)
        assertEquals(expectedNames(), allowedNames(File(path)))
        assertEquals(listOf<Byte>(1, 2), File(path).readBytes().toList())
    }

    @Test
    fun `a second ownerOnly write over the same path keeps the owner and SYSTEM DACL`() {
        val files: FileAccess = createFileAccess()
        val path: String = File(root, "secret").path
        assertTrue(files.writeAtomically(path, byteArrayOf(1), WriteOptions(ownerOnly = true)).isSuccess)
        assertTrue(files.writeAtomically(path, byteArrayOf(2), WriteOptions(ownerOnly = true)).isSuccess)
        assertEquals(expectedNames(), allowedNames(File(path)))
    }

    @Test
    fun `an ownerOnly write that must not replace leaves the same DACL`() {
        val files: FileAccess = createFileAccess()
        val path: String = File(root, "once").path
        assertTrue(files.writeAtomically(path, byteArrayOf(1), WriteOptions(existing = ExistingFile.Fail, ownerOnly = true)).isSuccess)
        assertEquals(expectedNames(), allowedNames(File(path)))
    }

    @Test
    fun `createDirectories with ownerOnly gives every created level the owner and SYSTEM DACL`() {
        val files: FileAccess = createFileAccess()
        assertTrue(files.createDirectories(File(root, "a").path, ownerOnly = true).isSuccess)
        val before: List<String> = allowedNames(root)
        assertTrue(files.createDirectories(File(File(root, "a"), "b").path, ownerOnly = true).isSuccess)
        assertEquals(expectedNames(), allowedNames(File(root, "a")))
        assertEquals(expectedNames(), allowedNames(File(File(root, "a"), "b")))
        assertEquals(before, allowedNames(root), "the existing parent is not changed")
    }

    @Test
    fun `a file written into an ownerOnly directory by plain code inherits the owner and SYSTEM entries`() {
        val files: FileAccess = createFileAccess()
        val directory: File = File(root, "vault")
        assertTrue(files.createDirectories(directory.path, ownerOnly = true).isSuccess)
        val plain: File = File(directory, "plain.txt")
        assertTrue(files.writeAtomically(plain.path, byteArrayOf(3)).isSuccess)
        assertEquals(expectedNames(), allowedNames(plain))
    }

    @Test
    fun `a write without ownerOnly keeps the DACL the parent gives it`() {
        val files: FileAccess = createFileAccess()
        val reference: File = Files.createFile(File(root, "reference").toPath()).toFile()
        val path: String = File(root, "plain").path
        assertTrue(files.writeAtomically(path, byteArrayOf(1)).isSuccess)
        assertEquals(allowedNames(reference), allowedNames(File(path)))
    }

    @Test
    fun `the granted user is the qualified token user and carries a domain part`() {
        val files: FileAccess = createFileAccess()
        val path: String = File(root, "secret").path
        assertTrue(files.writeAtomically(path, byteArrayOf(1), WriteOptions(ownerOnly = true)).isSuccess)
        val users: List<String> = acl(File(path)).map { it.principal().name }.filter { it != systemName() }
        assertEquals(1, users.size, "exactly one entry besides SYSTEM: ${acl(File(path))}")
        assertTrue(users.single().contains('\\'), "the principal is domain-qualified: ${users.single()}")
        assertEquals(tokenUser(), users.single(), "the principal is the token user")
    }

    @Test
    fun `a changed user dot name property does not redirect the grant`() {
        val original: String? = System.getProperty("user.name")
        val files: FileAccess = createFileAccess()
        val path: String = File(root, "secret").path
        try {
            // A real, different local account: the old lookup by user.name would have granted it.
            System.setProperty("user.name", if (NTSystem().name.equals("Guest", ignoreCase = true)) "Administrator" else "Guest")
            assertTrue(files.writeAtomically(path, byteArrayOf(1), WriteOptions(ownerOnly = true)).isSuccess)
        } finally {
            if (original == null) System.clearProperty("user.name") else System.setProperty("user.name", original)
        }
        assertEquals(expectedNames(), allowedNames(File(path)))
    }

    @Test
    fun `the DACL has no inherited entry at creation`() {
        val files: FileAccess = createFileAccess()
        val path: String = File(root, "secret").path
        assertTrue(files.writeAtomically(path, byteArrayOf(1), WriteOptions(ownerOnly = true)).isSuccess)
        assertEquals(2, acl(File(path)).size, "user and SYSTEM only: ${acl(File(path))}")
    }
}
