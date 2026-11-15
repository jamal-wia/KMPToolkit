package io.github.jamal_wia.kmptoolkit.files

import java.io.File
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.UserPrincipal
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume

/**
 * Breaker round 1 of group files-owner-only (STO-1, storage PLAN S7, 3.7, T10): attacks on the Windows half of
 * `ownerOnly`. The contract under attack: an owner-only file or directory has an explicit DACL of exactly the token user
 * and SYSTEM with no inherited entry at creation, even under a parent that hands an inheritable `Everyone` entry to its
 * children; a directory that existed is never changed; the first-call principal resolution is safe from many threads.
 * Runs only on Windows (skipped elsewhere with the reason).
 */
class OwnerOnlyWindowsBreakerR1Test {
    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        Assume.assumeTrue(
            "Windows ACL test: the default file system has no acl view here",
            FileSystems.getDefault().supportedFileAttributeViews().contains("acl") &&
                !FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        )
        root = File(TestEnv.newRoot())
    }

    @AfterTest
    fun tearDown() {
        if (this::root.isInitialized) TestEnv.deleteTree(root.path)
    }

    private fun p(vararg segments: String): String = segments.fold(root) { parent: File, name: String -> File(parent, name) }.path

    private fun f(vararg segments: String): File = File(p(*segments))

    private fun acl(file: File): List<AclEntry> =
        Files.getFileAttributeView(file.toPath(), AclFileAttributeView::class.java).acl

    // Breaker round 2: by the well-known SID S-1-1-0, not by the English name, which a localised Windows does not know.
    private fun everyone(): UserPrincipal = WindowsEveryonePrincipal.principal

    /** Adds an inheritable allow-everything entry for Everyone to [directory]. */
    private fun grantEveryoneInheritable(directory: File) {
        val view: AclFileAttributeView = Files.getFileAttributeView(directory.toPath(), AclFileAttributeView::class.java)
        val entry: AclEntry = AclEntry.newBuilder()
            .setType(AclEntryType.ALLOW)
            .setPrincipal(everyone())
            .setPermissions(AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA, AclEntryPermission.READ_ATTRIBUTES)
            .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
            .build()
        view.acl = listOf(entry) + view.acl
    }

    private fun assertOwnerOnly(file: File, context: String) {
        val entries: List<AclEntry> = acl(file)
        assertEquals(2, entries.size, "$context: exactly the user and SYSTEM, was $entries")
        assertFalse(entries.any { it.principal() == everyone() }, "$context: Everyone must not be granted, was $entries")
        assertFalse(entries.any { AclEntryFlag.INHERIT_ONLY in it.flags() }, "$context: $entries")
    }

    @Test
    fun `an ownerOnly file and directory under a parent with an inheritable Everyone entry get no Everyone entry`() {
        val parent: File = f("open").also { it.mkdirs() }
        grantEveryoneInheritable(parent)
        val plain: File = f("open", "plain.txt").also { it.writeText("x") }
        assertTrue(acl(plain).any { it.principal() == everyone() }, "control: a plain child inherits the Everyone entry")
        val files: FileAccess = createFileAccess()
        assertTrue(files.writeAtomically(p("open", "secret"), byteArrayOf(1), WriteOptions(ownerOnly = true)).isSuccess)
        assertTrue(files.createDirectories(p("open", "private", "deeper"), ownerOnly = true).isSuccess)
        assertOwnerOnly(f("open", "secret"), "file")
        assertOwnerOnly(f("open", "private"), "directory")
        assertOwnerOnly(f("open", "private", "deeper"), "nested directory")
    }

    @Test
    fun `an existing directory with a wide DACL is never changed by an ownerOnly createDirectories or write into it`() {
        val existing: File = f("existing").also { it.mkdirs() }
        grantEveryoneInheritable(existing)
        val before: List<AclEntry> = acl(existing)
        val files: FileAccess = createFileAccess()
        assertTrue(files.createDirectories(existing.path, ownerOnly = true).isSuccess)
        assertTrue(files.createDirectories(p("existing", "child"), ownerOnly = true).isSuccess)
        assertTrue(files.writeAtomically(p("existing", "f"), byteArrayOf(1), WriteOptions(createParentDirectories = true, ownerOnly = true)).isSuccess)
        assertEquals(before, acl(existing), "the existing directory's DACL is unchanged")
        assertOwnerOnly(f("existing", "child"), "child")
        assertOwnerOnly(f("existing", "f"), "file")
    }

    @Test
    fun `many fresh instances resolving the principals for the first time at once all write the same owner-only DACL`() {
        val start = CountDownLatch(1)
        val problems: MutableList<String> = Collections.synchronizedList(ArrayList())
        val threads: List<Thread> = List(16) { worker: Int ->
            Thread({
                start.await()
                val written: FileResult<Unit> = createFileAccess().writeAtomically(p("w$worker"), byteArrayOf(1), WriteOptions(ownerOnly = true))
                if (!written.isSuccess) problems += "worker $worker: $written"
            }, "first-resolution-$worker")
        }
        threads.forEach { it.start() }
        start.countDown()
        threads.forEach { thread: Thread ->
            thread.join(TimeUnit.SECONDS.toMillis(60))
            assertFalse(thread.isAlive, "hang: ${thread.name} ${thread.state}")
        }
        assertEquals(listOf<String>(), problems.toList())
        val reference: Set<UserPrincipal> = acl(f("w0")).map { it.principal() }.toSet()
        for (worker: Int in 0 until 16) {
            assertOwnerOnly(f("w$worker"), "w$worker")
            assertEquals(reference, acl(f("w$worker")).map { it.principal() }.toSet(), "w$worker")
        }
    }

    @Test
    fun `an observer never sees the final name with a DACL wider than the user and SYSTEM during a write storm`() {
        val files: FileAccess = createFileAccess()
        val dir: File = f("s").also { it.mkdirs() }
        grantEveryoneInheritable(dir)
        val target: File = f("s", "secret")
        assertTrue(files.writeAtomically(target.path, byteArrayOf(0), WriteOptions(durability = Durability.Buffered, ownerOnly = true)).isSuccess)
        val done = AtomicBoolean(false)
        val seen = AtomicInteger(0)
        val problems: MutableList<String> = Collections.synchronizedList(ArrayList())
        val writer = Thread({
            repeat(200) { step: Int ->
                val written: FileResult<Unit> = files.writeAtomically(target.path, byteArrayOf(step.toByte()), WriteOptions(durability = Durability.Buffered, ownerOnly = true))
                if (!written.isSuccess) problems += "step $step: $written"
            }
        }, "acl-writer")
        val observer = Thread({
            while (!done.get()) {
                for (name: String in dir.list().orEmpty()) {
                    val entries: List<AclEntry> = try {
                        acl(File(dir, name))
                    } catch (e: NoSuchFileException) {
                        continue
                    } catch (e: AccessDeniedException) {
                        // A file being renamed or deleted can refuse the read; never a wider DACL.
                        continue
                    }
                    seen.incrementAndGet()
                    if (entries.size != 2 || entries.any { it.principal() == everyone() }) problems += "$name observed with $entries"
                }
            }
        }, "acl-observer")
        writer.start()
        observer.start()
        writer.join(TimeUnit.SECONDS.toMillis(90))
        assertFalse(writer.isAlive, "hang: writer ${writer.state}")
        done.set(true)
        observer.join(TimeUnit.SECONDS.toMillis(30))
        assertFalse(observer.isAlive, "hang: observer ${observer.state}")
        assertEquals(listOf<String>(), problems.toList())
        assertTrue(seen.get() > 0, "control: the observer read at least one DACL")
        assertEquals(listOf("secret"), dir.list()!!.toList(), "no temp file is left")
    }
}
