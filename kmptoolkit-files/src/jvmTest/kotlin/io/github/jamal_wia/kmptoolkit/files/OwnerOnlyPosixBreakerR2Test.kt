@file:OptIn(ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.internal.NioFileSystemOps
import io.github.jamal_wia.kmptoolkit.files.spi.FileSystemOps
import io.github.jamal_wia.kmptoolkit.files.spi.WritableFile
import io.github.jamal_wia.kmptoolkit.files.spi.fileAccessOver
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
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
 * Breaker round 2 of group files-owner-only (STO-1, storage PLAN S7, 3.7, T10): new shapes against the POSIX half of
 * `WriteOptions.ownerOnly` and `createDirectories(ownerOnly)` on a real volume, none repeating round 1. The contract under
 * attack: the written file is owner-only from the moment it exists, every directory the call creates (the target's
 * parents and the temp directory) is owner-only, an entry that existed before the call (a directory, a planted file, a
 * link, the other name of a hard link) is never changed, nothing of the library is left behind, no descriptor is held.
 */
class OwnerOnlyPosixBreakerR2Test {
    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        TestHost.assumePosixFileSemantics("owner-only modes; the Windows half is OwnerOnlyWindowsBreakerR2Test")
        Assume.assumeTrue(
            "the default file system has no posix attribute view",
            FileSystems.getDefault().supportedFileAttributeViews().contains("posix"),
        )
        root = File(TestEnv.newRoot())
    }

    @AfterTest
    fun tearDown() {
        if (this::root.isInitialized) TestEnv.deleteTree(root.path)
    }

    // path-hygiene-ignore: PH2 a POSIX-only test: setUp skips it on Windows (TestHost.assumePosixFileSemantics)
    private fun mode(file: File): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath(), LinkOption.NOFOLLOW_LINKS))

    private fun p(vararg segments: String): String = segments.fold(root) { parent: File, name: String -> File(parent, name) }.path

    private fun f(vararg segments: String): File = File(p(*segments))

    private fun chmod(file: File, permissions: String) {
        // path-hygiene-ignore: PH2 a POSIX-only test: setUp skips it on Windows (TestHost.assumePosixFileSemantics)
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(permissions))
    }

    private fun joinBounded(threads: List<Thread>) {
        for (thread: Thread in threads) {
            thread.join(TimeUnit.SECONDS.toMillis(60))
            assertFalse(thread.isAlive, "hang: ${thread.name} still running, state ${thread.state}")
        }
    }

    /** The mode a plain directory gets under this process's umask; the test skips when it is already 0700. */
    private fun plainDirectoryMode(): String {
        val reference: File = f("reference-dir").also { assertTrue(it.mkdir()) }
        val plain: String = mode(reference)
        reference.delete()
        Assume.assumeFalse("umask already gives 0700 to a plain directory: owner-only cannot be told apart", plain == "rwx------")
        return plain
    }

    /** Ops that run [beforeCreateNew] with the 0-based attempt index just before each real temp file creation. */
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

    /** Ops that run [beforeCreateDirectory] just before each real directory creation. */
    private class RacingDirectoryOps(
        private val real: FileSystemOps,
        private val beforeCreateDirectory: (path: String) -> Unit,
    ) : FileSystemOps by real {
        override fun createDirectory(path: String, ownerOnly: Boolean) {
            beforeCreateDirectory(path)
            real.createDirectory(path, ownerOnly)
        }
    }

    @Test
    fun `a temp name planted as a link and as a wide file before the create is never written through, chmod-ed or removed`() {
        val bait: File = f("bait").also { it.writeText("public") }
        chmod(bait, "rw-r--r--")
        val dir: File = f("d").also { it.mkdirs() }
        val planted: MutableList<String> = ArrayList()
        val ops = PlantingOps(NioFileSystemOps()) { attempt: Int, path: String ->
            when (attempt) {
                0 -> assertTrue(TestEnv.createSymbolicLink(path, bait.path), "planting a link at $path").also { planted += path }
                1 -> File(path).also { it.writeText("planted"); chmod(it, "rw-r--r--") }.also { planted += path }
            }
        }
        val files: FileAccess = fileAccessOver(ops)
        val written: FileResult<Unit> = files.writeAtomically(p("d", "secret"), "secret".encodeToByteArray(), WriteOptions(ownerOnly = true))
        assertTrue(written.isSuccess, "$written")
        assertTrue(ops.attempts >= 3, "control: both planted names made the engine pick a new one, attempts ${ops.attempts}")
        assertEquals("rw-------", mode(f("d", "secret")))
        assertEquals("secret", f("d", "secret").readText())
        assertEquals("public", bait.readText(), "the secret never went through the planted link")
        assertEquals("rw-r--r--", mode(bait), "the file behind the planted link is never changed")
        assertTrue(Files.isSymbolicLink(File(planted[0]).toPath()), "the planted link is not the library's and stays")
        assertEquals("planted", File(planted[1]).readText(), "the planted file is not the library's and stays")
        assertEquals("rw-r--r--", mode(File(planted[1])), "the planted file is never tightened")
        assertEquals(setOf("secret", File(planted[0]).name, File(planted[1]).name), dir.list()!!.toSet(), "no temp file of the library is left")
    }

    @Test
    fun `a level that becomes a link to a wide directory between the check and the create is never tightened`() {
        val outside: File = f("outside").also { it.mkdirs() }
        chmod(outside, "rwxr-xr-x")
        val racedLevel: String = p("tree", "lvl")
        var linked = false
        val ops = RacingDirectoryOps(NioFileSystemOps()) { path: String ->
            if (path == racedLevel && !linked) {
                linked = true
                assertTrue(TestEnv.createSymbolicLink(path, outside.path), "planting a link at $path")
            }
        }
        val made: FileResult<Unit> = fileAccessOver(ops).createDirectories(p("tree", "lvl", "child"), ownerOnly = true)
        assertTrue(linked, "control: the race window was reached")
        assertEquals("rwxr-xr-x", mode(outside), "the directory behind the link is never changed, result $made")
        assertTrue(Files.isSymbolicLink(f("tree", "lvl").toPath()), "the link is never replaced")
        assertEquals("rwx------", mode(f("tree")), "the level this call created before the race is owner-only")
        if (made.isSuccess) {
            assertEquals("rwx------", mode(File(outside, "child")), "a level created through the link is still owner-only")
        } else {
            assertTrue((made as FileResult.Failure).error is FileError.NotADirectory, "a refusal is typed: $made")
            assertEquals(listOf<String>(), outside.list()!!.toList(), "and creates nothing behind the link")
        }
    }

    @Test
    fun `an ownerOnly replace of a name hard linked to a wide file leaves the other name, its content and its mode alone`() {
        val other: File = f("other").also { it.writeText("public") }
        chmod(other, "rw-r--r--")
        Files.createLink(f("target").toPath(), other.toPath())
        assertEquals(2, Files.getAttribute(other.toPath(), "unix:nlink"), "control: two names share the inode")
        val files: FileAccess = createFileAccess()
        assertTrue(files.writeAtomically(p("target"), "secret".encodeToByteArray(), WriteOptions(ownerOnly = true)).isSuccess)
        assertEquals("secret", f("target").readText())
        assertEquals("rw-------", mode(f("target")))
        assertEquals("public", other.readText(), "the secret never went into the shared inode")
        assertEquals("rw-r--r--", mode(other), "the other name's inode is never chmod-ed")
        assertEquals(1, Files.getAttribute(other.toPath(), "unix:nlink"), "the target became a new inode")
        assertEquals(setOf("other", "target"), root.list()!!.toSet())
    }

    @Test
    fun `under a storm of ownerOnly and plain createDirectories every pre-existing level stays wide and every new level has one of the two modes`() {
        val seed = 2201
        val plain: String = plainDirectoryMode()
        val names: List<String> = listOf("a", "b", "c")
        // Seed: half of the first level exists already, wide.
        for (name: String in listOf("a", "c")) f(name).also { it.mkdirs(); chmod(it, "rwxr-xr-x") }
        val files: List<FileAccess> = listOf(createFileAccess(), createFileAccess())
        val problems: MutableList<String> = Collections.synchronizedList(ArrayList())
        val start = CountDownLatch(1)
        val threads: List<Thread> = List(8) { worker: Int ->
            Thread({
                start.await()
                val random = Random(seed * 100 + worker)
                repeat(120) { step: Int ->
                    val chain: Array<String> = Array(1 + random.nextInt(4)) { names[random.nextInt(names.size)] }
                    val ownerOnly: Boolean = random.nextBoolean()
                    val made: FileResult<Unit> = files[random.nextInt(2)].createDirectories(p(*chain), durable = random.nextInt(5) == 0, ownerOnly = ownerOnly)
                    if (!made.isSuccess) problems += "seed=$seed worker $worker step $step ${chain.toList()} ownerOnly=$ownerOnly: $made"
                }
            }, "dir-storm-$worker")
        }
        threads.forEach { it.start() }
        start.countDown()
        joinBounded(threads)
        assertEquals(listOf<String>(), problems.toList(), "seed=$seed")
        assertEquals("rwxr-xr-x", mode(f("a")), "seed=$seed: a level that existed before the storm is never changed")
        assertEquals("rwxr-xr-x", mode(f("c")), "seed=$seed: a level that existed before the storm is never changed")
        var checked = 0
        root.walkTopDown().filter { it != root && it.path != f("a").path && it.path != f("c").path }.forEach { level: File ->
            assertTrue(level.isDirectory, "seed=$seed: only directories, was ${level.path}")
            val actual: String = mode(level)
            assertTrue(actual == "rwx------" || actual == plain, "seed=$seed: ${level.path} has $actual, neither owner-only nor plain $plain")
            checked++
        }
        assertTrue(checked > 10, "control: the storm created levels, $checked")
    }

    @Test
    fun `a missing temp directory chain below an existing wide level is created owner-only with SyncedWithDirectory and leaves no temp`() {
        val existing: File = f("t").also { it.mkdirs() }
        chmod(existing, "rwxr-xr-x")
        val files: FileAccess = createFileAccess()
        val options = WriteOptions(
            durability = Durability.SyncedWithDirectory,
            createParentDirectories = true,
            tempDirectory = p("t", "u", "v"),
            ownerOnly = true,
        )
        val written: FileResult<Unit> = files.writeAtomically(p("out", "x", "f"), "s".encodeToByteArray(), options)
        assertTrue(written.isSuccess, "$written")
        assertEquals("rwxr-xr-x", mode(existing), "the existing level of the temp directory is never changed")
        assertEquals("rwx------", mode(f("t", "u")), "a created level of the temp directory chain is owner-only")
        assertEquals("rwx------", mode(f("t", "u", "v")), "the created temp directory is owner-only")
        assertEquals("rwx------", mode(f("out")))
        assertEquals("rwx------", mode(f("out", "x")))
        assertEquals("rw-------", mode(f("out", "x", "f")))
        assertEquals(listOf<String>(), f("t", "u", "v").list()!!.toList(), "no temp file is left")
    }

    @Test
    fun `an existing wide temp directory without createParentDirectories is used, never changed, and the file is still owner-only`() {
        val scratch: File = f("scratch").also { it.mkdirs() }
        chmod(scratch, "rwxrwxrwx")
        f("out").mkdirs()
        val files: FileAccess = createFileAccess()
        val written: FileResult<Unit> = files.writeAtomically(
            p("out", "f"),
            "s".encodeToByteArray(),
            WriteOptions(tempDirectory = scratch.path, ownerOnly = true, existing = ExistingFile.Fail),
        )
        assertTrue(written.isSuccess, "$written")
        assertEquals("rwxrwxrwx", mode(scratch), "the temp directory is never changed")
        assertEquals("rw-------", mode(f("out", "f")))
        assertEquals(listOf<String>(), scratch.list()!!.toList(), "no temp file is left")
    }

    @Test
    fun `successful, refused and failing ownerOnly operations in a loop hold no descriptor afterwards`() {
        val before: Long? = TestEnv.openDescriptorCount()
        Assume.assumeTrue("descriptor count not available on this host", before != null)
        FileChannel.open(f("control").toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
            assertTrue(TestEnv.openDescriptorCount()!! > before!!, "control: an open channel is counted")
        }
        f("blocker").writeText("file")
        val files: FileAccess = createFileAccess()
        val baseline: Long = TestEnv.openDescriptorCount()!!
        repeat(150) { index: Int ->
            val synced: FileResult<Unit> = files.writeAtomically(
                p("ok", "d${index % 7}", "f$index"),
                byteArrayOf(index.toByte()),
                WriteOptions(durability = Durability.SyncedWithDirectory, createParentDirectories = true, ownerOnly = true),
            )
            assertTrue(synced.isSuccess, "$index: $synced")
            val refused: FileResult<Unit> = files.writeAtomically(
                p("ok", "d${index % 7}", "f$index"),
                byteArrayOf(0),
                WriteOptions(existing = ExistingFile.Fail, ownerOnly = true),
            )
            assertTrue(refused is FileResult.Failure && refused.error is FileError.AlreadyExists, "$index: $refused")
            val blocked: FileResult<Unit> = files.createDirectories(p("blocker", "d$index"), durable = true, ownerOnly = true)
            assertTrue(blocked is FileResult.Failure && blocked.error is FileError.NotADirectory, "$index: $blocked")
        }
        val after: Long = TestEnv.openDescriptorCount()!!
        assertTrue(after - baseline < 50, "450 owner-only operations leaked descriptors: $baseline -> $after")
        for (d: File in f("ok").listFiles()!!) {
            assertEquals("rwx------", mode(d), d.path)
            for (file: File in d.listFiles()!!) {
                assertTrue(file.name.startsWith("f"), "no temp file is left: ${file.path}")
                assertEquals("rw-------", mode(file), file.path)
            }
        }
    }
}
