@file:OptIn(ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.internal.NioFileSystemOps
import io.github.jamal_wia.kmptoolkit.files.internal.OwnerOnlyAttributes
import io.github.jamal_wia.kmptoolkit.files.spi.FileOpException
import io.github.jamal_wia.kmptoolkit.files.spi.fileAccessOver
import java.io.File
import java.nio.file.Path
import java.nio.file.attribute.GroupPrincipal
import java.nio.file.attribute.UserPrincipal
import java.nio.file.attribute.UserPrincipalLookupService
import java.nio.file.attribute.UserPrincipalNotFoundException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assert.assertThrows

/**
 * T10, account resolution of the Windows ACL branch, on every host: the branch is driven through its seams (the view
 * set, the token user name and the lookup service), so what it asks the operating system and how it fails is checked
 * without a Windows machine. The real Windows result is `OwnerOnlyWindowsAclT10Test`.
 */
class OwnerOnlyAccountsT10Test {
    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        root = File(TestEnv.newRoot())
    }

    @AfterTest
    fun tearDown() {
        TestEnv.deleteTree(root.path)
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

    private fun aclAttributes(tokenUser: () -> String): OwnerOnlyAttributes = object : OwnerOnlyAttributes(true, tokenUser) {
        override fun supportedViews(path: Path): Set<String> = setOf("acl")
    }

    @Test
    fun `the user is looked up by its qualified token name and never by user dot name`() {
        val lookup: RecordingLookup = RecordingLookup(setOf("CORP\\jdoe", "NT AUTHORITY\\SYSTEM"))
        val original: String? = System.getProperty("user.name")
        val principals: List<UserPrincipal>
        try {
            System.setProperty("user.name", "jdoe")
            principals = OwnerOnlyAttributes(true) { "CORP\\jdoe" }.resolvePrincipals(lookup)
        } finally {
            if (original == null) System.clearProperty("user.name") else System.setProperty("user.name", original)
        }
        assertEquals(listOf("CORP\\jdoe", "NT AUTHORITY\\SYSTEM"), principals.map { it.name })
        assertEquals(listOf("CORP\\jdoe", "NT AUTHORITY\\SYSTEM"), lookup.asked, "no bare 'jdoe' lookup")
    }

    @Test
    fun `SYSTEM falls back to its unqualified name when the authority name is not known`() {
        val lookup: RecordingLookup = RecordingLookup(setOf("SYSTEM"))
        val system: UserPrincipal = OwnerOnlyAttributes(true) { "CORP\\jdoe" }.lookupSystem(lookup)
        assertEquals("SYSTEM", system.name)
        assertEquals(listOf("NT AUTHORITY\\SYSTEM", "SYSTEM"), lookup.asked, "the qualified name is tried first")
    }

    @Test
    fun `a SYSTEM account no spelling resolves fails with every attempt recorded`() {
        val lookup: RecordingLookup = RecordingLookup(emptySet())
        val thrown: UserPrincipalNotFoundException = assertThrows(UserPrincipalNotFoundException::class.java) {
            OwnerOnlyAttributes(true) { "CORP\\jdoe" }.lookupSystem(lookup)
        }
        assertEquals(1, thrown.suppressed.size, "the second failure is kept as suppressed")
    }

    @Test
    fun `a token user the host cannot resolve fails closed and never falls back to user dot name`() {
        // user.name is a valid account of this host; the token name is not. Falling back to it would succeed.
        val attributes: OwnerOnlyAttributes = aclAttributes { "NO-SUCH-DOMAIN\\${System.getProperty("user.name")}" }
        val thrown: FileOpException = assertThrows(FileOpException::class.java) {
            attributes.forFile(File(root, "x").toPath())
        }
        assertTrue(thrown.error is FileError.Io, "${thrown.error}")
        val directory: FileOpException = assertThrows(FileOpException::class.java) {
            attributes.forDirectory(File(root, "d").toPath())
        }
        assertTrue(directory.error is FileError.Io, "${directory.error}")
    }

    @Test
    fun `a JDK without the NTSystem module fails closed and the write creates nothing`() {
        for (absent: Throwable in listOf(ClassNotFoundException("NTSystem"), NoClassDefFoundError("NTSystem"), UnsatisfiedLinkError("jaas"))) {
            val files: FileAccess = fileAccessOver(NioFileSystemOps(aclAttributes { throw absent }))
            val written: FileResult<Unit> = files.writeAtomically(File(root, "f").path, byteArrayOf(1), WriteOptions(ownerOnly = true))
            assertTrue(written is FileResult.Failure && written.error is FileError.Io, "$absent: $written")
            val made: FileResult<Unit> = files.createDirectories(File(root, "d").path, ownerOnly = true)
            assertTrue(made is FileResult.Failure && made.error is FileError.Io, "$absent: $made")
            assertEquals(emptyList<String>(), root.list()!!.toList(), "$absent: nothing was created")
        }
    }

    @Test
    fun `a failed account lookup is not cached, the next call asks again`() {
        var failing: Boolean = true
        var calls: Int = 0
        val attributes: OwnerOnlyAttributes = aclAttributes {
            calls++
            if (failing) throw ClassNotFoundException("NTSystem")
            "NO-SUCH-DOMAIN\\x"
        }
        assertFalse(runCatching { attributes.forFile(File(root, "x").toPath()) }.isSuccess)
        failing = false
        assertFalse(runCatching { attributes.forFile(File(root, "x").toPath()) }.isSuccess)
        assertEquals(2, calls, "the first failure was not remembered")
    }
}
