package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import com.sun.security.auth.module.NTSystem
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import java.io.File
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.UserPrincipalLookupService
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Assume

/**
 * Group sto-desktop, round 5 (phase A breaker round 3, m4) on a Windows host: the lock file that [CrossProcessLock]
 * creates itself when the volume refuses hard links (forced here by a files layer that answers `Unsupported`) carries a DACL
 * of allow entries for the current user and SYSTEM only. Skipped, with this reason, on every other host: the seams are
 * driven by `CrossProcessLockRound5Test`.
 *
 * Run it on Windows: `gradlew :kmptoolkit-storage:jvmTest --tests "*CrossProcessLockWindowsAclRound5Test"`.
 */
class CrossProcessLockWindowsAclRound5Test {
    private lateinit var directory: File

    @BeforeTest
    fun setUp() {
        val views: Set<String> = FileSystems.getDefault().supportedFileAttributeViews()
        Assume.assumeTrue(
            "needs a Windows host with an ACL file attribute view (the seams are CrossProcessLockRound5Test)",
            views.contains("acl") && !views.contains("posix"),
        )
        directory = Files.createTempDirectory("kmptoolkit-xlock-acl-round5").toFile()
    }

    @AfterTest
    fun tearDown() {
        if (this::directory.isInitialized) directory.deleteRecursively()
    }

    private val noLinks: FileAccess = object : FileAccess by createFileAccess() {
        override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> =
            FileResult.Failure(FileError.Unsupported(path))
    }

    private fun allowedNames(file: File): List<String> {
        val entries: List<AclEntry> = Files.getFileAttributeView(file.toPath(), AclFileAttributeView::class.java).acl
        assertTrue(entries.all { it.type() == AclEntryType.ALLOW }, "only allow entries: $entries")
        return entries.map { it.principal().name }.sorted()
    }

    /** The name Windows reports for SYSTEM: the account part is localised on some editions. */
    private fun systemName(lookup: UserPrincipalLookupService): String = try {
        lookup.lookupPrincipalByName("NT AUTHORITY\\SYSTEM").name
    } catch (_: IOException) {
        lookup.lookupPrincipalByName("SYSTEM").name
    }

    @Test
    fun `the lock file created without hard links carries a DACL of the current user and SYSTEM only`() = runBlocking {
        val lockFile = File(directory, "store.properties.lock")
        val lookup: UserPrincipalLookupService = FileSystems.getDefault().userPrincipalLookupService
        val account = NTSystem()
        val user: String = lookup.lookupPrincipalByName(account.domain + "\\" + account.name).name

        val held: LockAcquisition = CrossProcessLock(noLinks).acquire(lockFile, "store.properties")

        assertIs<LockAcquisition.Held>(held, "the fallback path created and locked the file ($held)").close()
        assertTrue(lockFile.isFile)
        assertEquals(listOf(user, systemName(lookup)).sorted(), allowedNames(lockFile), "an owner-only DACL, nothing inherited from the directory")
    }

    @Test
    fun `the link path and the fallback give the lock file the same DACL`() = runBlocking {
        val viaLinks = File(directory, "linked.properties.lock")
        val viaFallback = File(directory, "plain.properties.lock")

        assertIs<LockAcquisition.Held>(CrossProcessLock().acquire(viaLinks, "linked.properties")).close()
        assertIs<LockAcquisition.Held>(CrossProcessLock(noLinks).acquire(viaFallback, "plain.properties")).close()

        assertEquals(allowedNames(viaLinks), allowedNames(viaFallback))
    }
}
