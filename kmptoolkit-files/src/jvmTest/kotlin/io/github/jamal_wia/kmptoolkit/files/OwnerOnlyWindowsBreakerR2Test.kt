@file:OptIn(ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.internal.NioFileSystemOps
import io.github.jamal_wia.kmptoolkit.files.spi.FileSystemOps
import io.github.jamal_wia.kmptoolkit.files.spi.WritableFile
import io.github.jamal_wia.kmptoolkit.files.spi.fileAccessOver
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.attribute.AclEntry
import java.nio.file.attribute.AclEntryFlag
import java.nio.file.attribute.AclEntryPermission
import java.nio.file.attribute.AclEntryType
import java.nio.file.attribute.AclFileAttributeView
import java.nio.file.attribute.UserPrincipal
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume

/**
 * Breaker round 2 of group files-owner-only (STO-1, storage PLAN S7, 3.7, T10, D-24): new shapes against the Windows half
 * of `ownerOnly`. The contract under attack: every entry the call creates (file, target parents, temp directory chain)
 * has an explicit DACL of exactly the token user and SYSTEM with no inherited entry at creation, even under a parent
 * that hands an inheritable Everyone entry down; an entry that existed (a planted temp name, the other name of a hard
 * link, an existing level) keeps its DACL. Everyone is resolved by its SID ([WindowsEveryonePrincipal]), so the class
 * runs on a localised Windows. Runs only on Windows (skipped elsewhere with the reason).
 */
class OwnerOnlyWindowsBreakerR2Test {
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

    private fun everyone(): UserPrincipal = WindowsEveryonePrincipal.principal

    private fun grantsEveryone(file: File): Boolean = acl(file).any { it.principal() == everyone() }

    private fun everyoneEntry(vararg flags: AclEntryFlag): AclEntry = AclEntry.newBuilder()
        .setType(AclEntryType.ALLOW)
        .setPrincipal(everyone())
        .setPermissions(AclEntryPermission.READ_DATA, AclEntryPermission.WRITE_DATA, AclEntryPermission.READ_ATTRIBUTES)
        .setFlags(*flags)
        .build()

    private fun grantEveryoneInheritable(directory: File) {
        val view: AclFileAttributeView = Files.getFileAttributeView(directory.toPath(), AclFileAttributeView::class.java)
        view.acl = listOf(everyoneEntry(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)) + view.acl
    }

    private fun isOwnerOnly(file: File): Boolean {
        val entries: List<AclEntry> = acl(file)
        return entries.size == 2 && entries.none { it.principal() == everyone() } && entries.none { AclEntryFlag.INHERIT_ONLY in it.flags() }
    }

    private fun assertOwnerOnly(file: File, context: String) {
        assertTrue(isOwnerOnly(file), "$context: exactly the user and SYSTEM, no Everyone, was ${acl(file)}")
    }

    private class PlantingOps(
        private val real: FileSystemOps,
        private val beforeCreateNew: (attempt: Int, path: String) -> Unit,
    ) : FileSystemOps by real {
        var attempts: Int = 0
            private set

        override fun createNew(path: String, protection: FileProtection, ownerOnly: Boolean): WritableFile {
            beforeCreateNew(attempts++, path)
            return real.createNew(path, protection, ownerOnly)
        }
    }

    @Test
    fun `a missing temp directory chain under an Everyone-inheriting parent is created owner-only and the existing level keeps its DACL`() {
        val open: File = f("open").also { it.mkdirs() }
        grantEveryoneInheritable(open)
        val before: List<AclEntry> = acl(open)
        val files: FileAccess = createFileAccess()
        val written: FileResult<Unit> = files.writeAtomically(
            p("open", "out", "f"),
            byteArrayOf(1),
            WriteOptions(
                durability = Durability.SyncedWithDirectory,
                createParentDirectories = true,
                tempDirectory = p("open", "tmp", "deeper"),
                ownerOnly = true,
            ),
        )
        assertTrue(written.isSuccess, "$written")
        assertEquals(before, acl(open), "the existing parent is never changed")
        assertOwnerOnly(f("open", "tmp"), "created temp directory level")
        assertOwnerOnly(f("open", "tmp", "deeper"), "created temp directory")
        assertOwnerOnly(f("open", "out"), "created parent")
        assertOwnerOnly(f("open", "out", "f"), "file")
        assertEquals(listOf<String>(), f("open", "tmp", "deeper").list()!!.toList(), "no temp file is left")
    }

    @Test
    fun `an ownerOnly replace of a hard linked file with an explicit Everyone entry gives a new owner-only file and leaves the other name alone`() {
        val other: File = f("other").also { it.writeText("public") }
        val view: AclFileAttributeView = Files.getFileAttributeView(other.toPath(), AclFileAttributeView::class.java)
        view.acl = listOf(everyoneEntry()) + view.acl
        Files.createLink(f("target").toPath(), other.toPath())
        assertTrue(grantsEveryone(f("target")), "control: the target shares the wide security descriptor")
        val files: FileAccess = createFileAccess()
        assertTrue(files.writeAtomically(p("target"), "secret".encodeToByteArray(), WriteOptions(ownerOnly = true)).isSuccess)
        assertEquals("secret", f("target").readText())
        assertOwnerOnly(f("target"), "the replaced name")
        assertEquals("public", other.readText(), "the secret never went into the shared file")
        assertTrue(grantsEveryone(other), "the other name keeps its own DACL")
    }

    @Test
    fun `a temp name planted as a wide file before the create is never tightened or removed and the write still ends owner-only`() {
        val open: File = f("open").also { it.mkdirs() }
        grantEveryoneInheritable(open)
        val planted: MutableList<File> = ArrayList()
        val ops = PlantingOps(NioFileSystemOps()) { attempt: Int, path: String ->
            if (attempt < 2) planted += File(path).also { it.writeText("planted$attempt") }
        }
        val written: FileResult<Unit> = fileAccessOver(ops).writeAtomically(p("open", "secret"), byteArrayOf(7), WriteOptions(ownerOnly = true))
        assertTrue(written.isSuccess, "$written")
        assertTrue(ops.attempts >= 3, "control: both planted names made the engine pick a new one, attempts ${ops.attempts}")
        assertOwnerOnly(f("open", "secret"), "file")
        planted.forEachIndexed { index: Int, file: File ->
            assertEquals("planted$index", file.readText(), "the planted file is not the library's and stays")
            assertTrue(grantsEveryone(file), "the planted file keeps the DACL it inherited: ${acl(file)}")
        }
        assertEquals(setOf("secret") + planted.map { it.name }, open.list()!!.toSet(), "no temp file of the library is left")
    }

    @Test
    fun `under a storm of ownerOnly and plain createDirectories below an Everyone-inheriting parent each level is either owner-only or inherited and existing levels keep their DACL`() {
        val seed = 2211
        val open: File = f("open").also { it.mkdirs() }
        grantEveryoneInheritable(open)
        val existing: File = f("open", "a").also { it.mkdirs() }
        val existingBefore: List<AclEntry> = acl(existing)
        val names: List<String> = listOf("a", "b")
        val files: List<FileAccess> = listOf(createFileAccess(), createFileAccess())
        val problems: MutableList<String> = Collections.synchronizedList(ArrayList())
        val start = CountDownLatch(1)
        val threads: List<Thread> = List(6) { worker: Int ->
            Thread({
                start.await()
                val random = Random(seed * 100 + worker)
                repeat(60) { step: Int ->
                    val chain: Array<String> = arrayOf("open") + Array(1 + random.nextInt(3)) { names[random.nextInt(names.size)] }
                    val ownerOnly: Boolean = random.nextBoolean()
                    val made: FileResult<Unit> = files[random.nextInt(2)].createDirectories(p(*chain), ownerOnly = ownerOnly)
                    if (!made.isSuccess) problems += "seed=$seed worker $worker step $step ${chain.toList()} ownerOnly=$ownerOnly: $made"
                }
            }, "win-dir-storm-$worker")
        }
        threads.forEach { it.start() }
        start.countDown()
        for (thread: Thread in threads) {
            thread.join(TimeUnit.SECONDS.toMillis(120))
            assertFalse(thread.isAlive, "hang: ${thread.name} ${thread.state}")
        }
        assertEquals(listOf<String>(), problems.toList(), "seed=$seed")
        assertEquals(existingBefore, acl(existing), "seed=$seed: a level that existed before the storm is never changed")
        var checked = 0
        open.walkTopDown().filter { it != open && it != existing }.forEach { level: File ->
            // Every level is either owner-only (created so, or plain below an owner-only level, which inherits only the
            // user and SYSTEM) or plain and inheriting Everyone; never a third state, and never Everyone below an
            // owner-only level, which would mean an owner-only DACL had an inherited entry merged in.
            val ownerOnly: Boolean = isOwnerOnly(level)
            assertTrue(ownerOnly || grantsEveryone(level), "seed=$seed: ${level.path} has ${acl(level)}")
            val parent: File = level.parentFile
            if (parent != open && isOwnerOnly(parent)) {
                assertFalse(grantsEveryone(level), "seed=$seed: ${level.path} grants Everyone below the owner-only ${parent.path}: ${acl(level)}")
            }
            checked++
        }
        assertTrue(checked > 5, "control: the storm created levels, $checked")
    }
}
