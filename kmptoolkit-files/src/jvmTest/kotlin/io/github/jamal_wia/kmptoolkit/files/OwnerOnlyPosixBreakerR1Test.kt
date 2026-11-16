@file:OptIn(ToolkitInternalApi::class, ExperimentalAtomicApi::class)

package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.internal.NioFileSystemOps
import io.github.jamal_wia.kmptoolkit.files.internal.OwnerOnlyAttributes
import io.github.jamal_wia.kmptoolkit.files.spi.FileSystemOps
import io.github.jamal_wia.kmptoolkit.files.spi.fileAccessOver
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermissions
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume

/**
 * Breaker round 1 of group files-owner-only (STO-1, storage PLAN S7, 3.7, T10): attacks on the POSIX half of
 * `WriteOptions.ownerOnly` and `createDirectories(ownerOnly)` on a real volume. The contract under attack: the written
 * file is readable and writable by its owner only from the moment it exists (the final name never exists with wider
 * access), every directory the call creates is owner-only, a directory that already existed (including one a concurrent
 * caller made first) is never changed, a failure leaves no temp file and no half file. On macOS the mode is the guarantee, not
 * the absence of an inherited ACL entry (owner decision for 6.0.0): the last test pins that documented limit.
 */
class OwnerOnlyPosixBreakerR1Test {
    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        TestHost.assumePosixFileSemantics("owner-only modes; the Windows half is OwnerOnlyWindowsBreakerR1Test")
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
    private fun mode(file: File): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(file.toPath()))

    private fun p(vararg segments: String): String = segments.fold(root) { parent: File, name: String -> File(parent, name) }.path

    private fun f(vararg segments: String): File = File(p(*segments))

    private fun chmod(file: File, permissions: String) {
        // path-hygiene-ignore: PH2 a POSIX-only test: setUp skips it on Windows (TestHost.assumePosixFileSemantics)
        Files.setPosixFilePermissions(file.toPath(), PosixFilePermissions.fromString(permissions))
    }

    /** Ops that run [beforeCreateDirectory] just before each real directory creation: the "someone else won" window. */
    private class RacingOps(
        private val real: FileSystemOps,
        private val beforeCreateDirectory: (path: String) -> Unit,
    ) : FileSystemOps by real {
        override fun createDirectory(path: String, ownerOnly: Boolean) {
            beforeCreateDirectory(path)
            real.createDirectory(path, ownerOnly)
        }
    }

    private fun joinBounded(threads: List<Thread>) {
        for (thread: Thread in threads) {
            thread.join(TimeUnit.SECONDS.toMillis(60))
            assertFalse(thread.isAlive, "hang: ${thread.name} still running, state ${thread.state}")
        }
    }

    @Test
    fun `an observer never sees the final name or a temp file with wider access during an ownerOnly write storm`() {
        val seed = 2101
        val files: FileAccess = createFileAccess()
        val dir: File = f("s").also { it.mkdirs() }
        val target: String = p("s", "secret")
        assertTrue(files.writeAtomically(target, byteArrayOf(0), WriteOptions(durability = Durability.Buffered, ownerOnly = true)).isSuccess)
        val done = AtomicBoolean(false)
        val problems: MutableList<String> = Collections.synchronizedList(ArrayList())
        val finalSeen = AtomicInt(0)
        val tempSeen = AtomicInt(0)
        val start = CountDownLatch(1)
        val writers: List<Thread> = List(3) { worker: Int ->
            Thread({
                start.await()
                val random = Random(seed * 100 + worker)
                repeat(150) { step: Int ->
                    val written: FileResult<Unit> = files.writeAtomically(
                        target,
                        ByteArray(random.nextInt(1, 64)) { worker.toByte() },
                        WriteOptions(durability = Durability.Buffered, ownerOnly = true),
                    )
                    if (!written.isSuccess) problems += "seed=$seed worker $worker step $step: $written"
                }
            }, "owner-only-writer-$worker")
        }
        val observer = Thread({
            start.await()
            while (!done.load()) {
                for (name: String in dir.list().orEmpty()) {
                    val entry = File(dir, name)
                    val seen: String = try {
                        mode(entry)
                    } catch (e: NoSuchFileException) {
                        continue
                    }
                    if (name == "secret") finalSeen.addAndFetch(1) else tempSeen.addAndFetch(1)
                    if (seen != "rw-------") problems += "seed=$seed: $name observed with mode $seen"
                }
            }
        }, "owner-only-observer")
        (writers + observer).forEach { it.start() }
        start.countDown()
        joinBounded(writers)
        done.store(true)
        joinBounded(listOf(observer))
        assertEquals(listOf<String>(), problems.toList(), "seed=$seed")
        assertTrue(finalSeen.load() > 0, "seed=$seed: control, the observer must have seen the final name (temps seen ${tempSeen.load()})")
        assertEquals(listOf("secret"), dir.list()!!.toList(), "seed=$seed: no temp file is left")
        assertEquals("rw-------", mode(File(target)), "seed=$seed")
    }

    @Test
    fun `after a mixed storm the mode of every file belongs to the write whose content it holds`() {
        val seed = 2102
        val files: List<FileAccess> = listOf(createFileAccess(), createFileAccess())
        assertTrue(files[0].writeAtomically(p("reference"), byteArrayOf(1)).isSuccess)
        val plainMode: String = mode(f("reference"))
        Assume.assumeFalse("umask already gives 0600 to a plain write: owner-only cannot be told apart", plainMode == "rw-------")
        f("reference").delete()
        val problems: MutableList<String> = Collections.synchronizedList(ArrayList())
        val start = CountDownLatch(1)
        val names: List<String> = listOf("a", "b", "c")
        val threads: List<Thread> = List(8) { worker: Int ->
            Thread({
                start.await()
                val random = Random(seed * 100 + worker)
                repeat(80) { step: Int ->
                    val ownerOnly: Boolean = random.nextBoolean()
                    val existing: ExistingFile = if (random.nextInt(4) == 0) ExistingFile.Fail else ExistingFile.Replace
                    val content: ByteArray = "${if (ownerOnly) 'O' else 'P'}$worker-$step".encodeToByteArray()
                    val written: FileResult<Unit> = files[random.nextInt(2)].writeAtomically(
                        p(names[random.nextInt(names.size)]),
                        content,
                        WriteOptions(durability = Durability.Buffered, existing = existing, ownerOnly = ownerOnly),
                    )
                    val refused: Boolean = existing == ExistingFile.Fail && written is FileResult.Failure && written.error is FileError.AlreadyExists
                    if (!written.isSuccess && !refused) problems += "seed=$seed worker $worker step $step: $written"
                }
            }, "mixed-writer-$worker")
        }
        threads.forEach { it.start() }
        start.countDown()
        joinBounded(threads)
        assertEquals(listOf<String>(), problems.toList(), "seed=$seed")
        assertEquals(names, root.list()!!.sorted(), "seed=$seed: no temp file is left")
        for (name: String in names) {
            val content: String = f(name).readText()
            val expected: String = if (content.startsWith("O")) "rw-------" else plainMode
            assertEquals(expected, mode(f(name)), "seed=$seed: $name holds '$content'")
        }
    }

    @Test
    fun `a parent created by someone else between the check and the create is used and never tightened`() {
        val racedLevel: String = p("raced")
        val ops = RacingOps(NioFileSystemOps()) { path: String ->
            if (path == racedLevel && !File(racedLevel).exists()) {
                Files.createDirectory(File(racedLevel).toPath())
                chmod(File(racedLevel), "rwxr-xr-x")
            }
        }
        val files: FileAccess = fileAccessOver(ops)
        val made: FileResult<Unit> = files.createDirectories(p("raced", "x", "y"), durable = true, ownerOnly = true)
        assertTrue(made.isSuccess, "$made")
        assertEquals("rwxr-xr-x", mode(f("raced")), "a level a concurrent caller created first is never changed")
        assertEquals("rwx------", mode(f("raced", "x")))
        assertEquals("rwx------", mode(f("raced", "x", "y")))
        val written: FileResult<Unit> = files.writeAtomically(
            p("raced2", "f"),
            byteArrayOf(5),
            WriteOptions(createParentDirectories = true, ownerOnly = true),
        )
        assertTrue(written.isSuccess, "$written")
        assertEquals("rw-------", mode(f("raced2", "f")))
    }

    @Test
    fun `a file that appears at a parent level between the check and the create is NotADirectory and nothing is created below it`() {
        val racedLevel: String = p("p", "q")
        val ops = RacingOps(NioFileSystemOps()) { path: String ->
            if (path == racedLevel && !File(racedLevel).exists()) File(racedLevel).writeText("intruder")
        }
        val files: FileAccess = fileAccessOver(ops)
        val written: FileResult<Unit> = files.writeAtomically(
            p("p", "q", "r", "f"),
            byteArrayOf(1),
            WriteOptions(createParentDirectories = true, ownerOnly = true),
        )
        assertTrue(written is FileResult.Failure && written.error is FileError.NotADirectory, "$written")
        assertEquals("intruder", f("p", "q").readText(), "the intruding file is untouched")
        assertEquals("rwx------", mode(f("p")), "the level created before the race stays owner-only")
        assertEquals(listOf("q"), f("p").list()!!.toList(), "no temp file and no deeper level")
    }

    @Test
    fun `a dangling symbolic link as a missing parent is never followed to create its target`() {
        val outside: File = f("outside").also { it.mkdirs() }
        Assume.assumeTrue("symbolic links are not available", TestEnv.createSymbolicLink(p("link"), File(outside, "made").path))
        val files: FileAccess = createFileAccess()
        val made: FileResult<Unit> = files.createDirectories(p("link", "child"), ownerOnly = true)
        assertTrue(made is FileResult.Failure, "$made")
        val written: FileResult<Unit> = files.writeAtomically(
            p("link", "child", "f"),
            byteArrayOf(1),
            WriteOptions(createParentDirectories = true, ownerOnly = true),
        )
        assertTrue(written is FileResult.Failure, "$written")
        assertEquals(listOf<String>(), outside.list()!!.toList(), "nothing was created through the dangling link")
    }

    @Test
    fun `an ownerOnly write over a symbolic link replaces the link and leaves the linked file and its mode untouched`() {
        val shared: File = f("shared.txt").also { it.writeText("public") }
        chmod(shared, "rw-r--r--")
        Assume.assumeTrue("symbolic links are not available", TestEnv.createSymbolicLink(p("alias"), shared.path))
        val files: FileAccess = createFileAccess()
        assertTrue(files.writeAtomically(p("alias"), "secret".encodeToByteArray(), WriteOptions(ownerOnly = true)).isSuccess)
        assertFalse(Files.isSymbolicLink(f("alias").toPath()), "the link itself was replaced by the owner-only file")
        assertEquals("rw-------", mode(f("alias")))
        assertEquals("public", shared.readText(), "the secret never went through the link")
        assertEquals("rw-r--r--", mode(shared), "the linked file is never changed")
    }

    @Test
    fun `an ownerOnly write into a read-only directory is AccessDenied and leaves nothing behind`() {
        val dir: File = f("ro").also { it.mkdirs() }
        val guard: AutoCloseable? = TestEnv.makeUnwritable(dir.path)
        Assume.assumeTrue("the directory cannot be made unwritable for this user (root?)", guard != null)
        try {
            val files: FileAccess = createFileAccess()
            val written: FileResult<Unit> = files.writeAtomically(p("ro", "f"), byteArrayOf(1), WriteOptions(ownerOnly = true))
            assertTrue(written is FileResult.Failure && written.error is FileError.AccessDenied, "$written")
            val made: FileResult<Unit> = files.createDirectories(p("ro", "d", "e"), ownerOnly = true)
            assertTrue(made is FileResult.Failure && made.error is FileError.AccessDenied, "$made")
        } finally {
            guard?.close()
        }
        assertEquals(listOf<String>(), dir.list()!!.toList(), "no temp file, no directory")
    }

    @Test
    fun `unicode names, trailing separators and a deep chain are created owner-only`() {
        val files: FileAccess = createFileAccess()
        // path-hygiene-ignore: PH1 the attack is a trailing separator of the host's own syntax on a native path
        val deep: String = p("é", "日本語", "😀 dir", "a", "b", "c", "d", "e") + File.separator
        assertTrue(files.createDirectories(deep, ownerOnly = true).isSuccess, deep)
        var level: File = f("é")
        for (name: String in listOf("日本語", "😀 dir", "a", "b", "c", "d", "e")) {
            assertEquals("rwx------", mode(level), level.path)
            level = File(level, name)
        }
        assertEquals("rwx------", mode(level), level.path)
        val longName: String = "ü".repeat(100)
        val written: FileResult<Unit> = files.writeAtomically(
            File(level, longName).path,
            byteArrayOf(3),
            WriteOptions(ownerOnly = true, tempPrefix = "ž".repeat(40)),
        )
        assertTrue(written.isSuccess, "$written")
        assertEquals("rw-------", mode(File(level, longName)))
        assertEquals(listOf(longName), level.list()!!.toList(), "no temp file is left")
    }

    @Test
    fun `a case variant of an existing 0755 directory on a case-insensitive volume is never tightened`() {
        val existing: File = f("Mixed").also { it.mkdirs() }
        chmod(existing, "rwxr-xr-x")
        Assume.assumeTrue("the volume is case-sensitive", f("mIXED").exists())
        val files: FileAccess = createFileAccess()
        assertTrue(files.createDirectories(p("mIXED", "child"), ownerOnly = true).isSuccess)
        assertTrue(files.writeAtomically(p("MIXED", "child", "f"), byteArrayOf(1), WriteOptions(createParentDirectories = true, ownerOnly = true)).isSuccess)
        assertEquals("rwxr-xr-x", mode(existing), "the existing directory under another spelling is never changed")
        assertEquals(listOf("Mixed"), root.list()!!.toList(), "no second directory was created")
        assertEquals("rwx------", mode(f("Mixed", "child")))
        assertEquals("rw-------", mode(f("Mixed", "child", "f")))
    }

    @Test
    fun `two no-replace ownerOnly writers racing for one name leave exactly one owner-only winner and no temp`() {
        val seed = 2103
        repeat(40) { round: Int ->
            val files: List<FileAccess> = listOf(createFileAccess(), createFileAccess())
            val target: String = p("race$round")
            val start = CountDownLatch(1)
            val results: Array<FileResult<Unit>?> = arrayOfNulls(2)
            val threads: List<Thread> = List(2) { worker: Int ->
                Thread({
                    start.await()
                    results[worker] = files[worker].writeAtomically(
                        target,
                        byteArrayOf(worker.toByte()),
                        WriteOptions(durability = Durability.Buffered, existing = ExistingFile.Fail, ownerOnly = true),
                    )
                }, "no-replace-$worker")
            }
            threads.forEach { it.start() }
            start.countDown()
            joinBounded(threads)
            val winners: Int = results.count { it?.isSuccess == true }
            val losers: Int = results.count { it is FileResult.Failure && it.error is FileError.AlreadyExists }
            assertEquals(1 to 1, winners to losers, "seed=$seed round $round: ${results.toList()}")
            assertEquals("rw-------", mode(File(target)), "seed=$seed round $round")
        }
        assertEquals(40, root.list()!!.size, "seed=$seed: no temp file is left: ${root.list()!!.filterNot { it.startsWith("race") }}")
    }

    @Test
    fun `a shared 0755 temp directory with one prefix stays 0755 under two instances and holds no temp afterwards`() {
        val seed = 2104
        val scratch: File = f("scratch").also { it.mkdirs() }
        chmod(scratch, "rwxr-xr-x")
        val config = FileAccessConfig()
        val files: List<FileAccess> = listOf(createFileAccess(config), createFileAccess(config))
        val problems: MutableList<String> = Collections.synchronizedList(ArrayList())
        val start = CountDownLatch(1)
        val threads: List<Thread> = List(6) { worker: Int ->
            Thread({
                start.await()
                val random = Random(seed * 100 + worker)
                repeat(60) { step: Int ->
                    val options = WriteOptions(
                        durability = Durability.Buffered,
                        createParentDirectories = true,
                        tempDirectory = scratch.path,
                        tempPrefix = "same-",
                        ownerOnly = true,
                    )
                    val written: FileResult<Unit> = files[random.nextInt(2)].writeAtomically(p("out", "w${random.nextInt(4)}"), byteArrayOf(step.toByte()), options)
                    if (!written.isSuccess) problems += "seed=$seed worker $worker step $step: $written"
                }
            }, "shared-temp-$worker")
        }
        threads.forEach { it.start() }
        start.countDown()
        joinBounded(threads)
        assertEquals(listOf<String>(), problems.toList(), "seed=$seed")
        assertEquals("rwxr-xr-x", mode(scratch), "seed=$seed: an existing temp directory is never changed")
        assertEquals(listOf<String>(), scratch.list()!!.toList(), "seed=$seed: no temp file is left")
        assertEquals("rwx------", mode(f("out")), "seed=$seed")
        for (name: String in f("out").list()!!) assertEquals("rw-------", mode(f("out", name)), "seed=$seed: $name")
    }

    @Test
    fun `refused owner-only attributes in a loop hold no descriptor afterwards`() {
        val before: Long? = TestEnv.openDescriptorCount()
        Assume.assumeTrue("descriptor count not available on this host", before != null)
        // Control: the counter sees an open channel.
        FileChannel.open(f("control").toPath(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE).use {
            assertTrue(TestEnv.openDescriptorCount()!! > before!!, "control: an open channel is counted")
        }
        val refused: FileAttribute<String> = object : FileAttribute<String> {
            override fun name(): String = "acl:acl"
            override fun value(): String = "not supported here"
        }
        val attributes: OwnerOnlyAttributes = object : OwnerOnlyAttributes(true) {
            override fun forFile(path: Path): Array<FileAttribute<*>> = arrayOf(refused)
            override fun forDirectory(path: Path): Array<FileAttribute<*>> = arrayOf(refused)
        }
        val files: FileAccess = fileAccessOver(NioFileSystemOps(attributes))
        val baseline: Long = TestEnv.openDescriptorCount()!!
        repeat(300) { index: Int ->
            val written: FileResult<Unit> = files.writeAtomically(p("r$index"), byteArrayOf(1), WriteOptions(ownerOnly = true))
            assertTrue(written is FileResult.Failure && written.error is FileError.Unsupported, "$index: $written")
        }
        val after: Long = TestEnv.openDescriptorCount()!!
        assertTrue(after - baseline < 50, "300 refused owner-only writes leaked descriptors: $baseline -> $after")
        assertEquals(listOf("control"), root.list()!!.toList(), "no temp file is left")
    }

    /**
     * The documented macOS limit (owner decision for 6.0.0; `docs/kmptoolkit-files/05-platform-notes.md`, `WriteOptions.ownerOnly`):
     * `ownerOnly` guarantees the POSIX mode (0600 / 0700) on macOS, but an extended ACL entry that the parent directory marks
     * inheritable is still added to every new file and directory, and the JDK cannot remove it. The test pins both halves, so it
     * fails if the mode ever stops being owner-only (the guarantee) or if the OS or the library stops inheriting the entry (the
     * documented limit then changed and the documentation must change with it), and it checks the documented remedy
     * (`chmod -N` clears the entry).
     */
    @Test
    fun `on macOS an ownerOnly file and directory keep mode 0600 and 0700 and still inherit the parent's inheritable ACL entry as documented`() {
        Assume.assumeTrue("macOS extended ACLs", System.getProperty("os.name").orEmpty().startsWith("Mac"))
        val parent: File = f("acl").also { it.mkdirs() }
        val granted: Boolean = run(listOf("chmod", "+a", "everyone allow read,write,list,add_file,file_inherit,directory_inherit", parent.path)).first == 0
        Assume.assumeTrue("chmod +a is not available", granted)
        val files: FileAccess = createFileAccess()
        assertTrue(files.writeAtomically(p("acl", "secret"), "s".encodeToByteArray(), WriteOptions(ownerOnly = true)).isSuccess)
        assertTrue(files.createDirectories(p("acl", "private"), ownerOnly = true).isSuccess)
        assertContentEquals("s".encodeToByteArray(), f("acl", "secret").readBytes())
        val parentEntries: List<String> = aclEntries(parent)
        assertTrue(parentEntries.any { "everyone" in it }, "control: the parent carries the inheritable entry: $parentEntries")
        // The guarantee: the POSIX mode is owner-only.
        assertEquals("rw-------", mode(f("acl", "secret")), "ownerOnly guarantees the mode 0600 on macOS")
        assertEquals("rwx------", mode(f("acl", "private")), "ownerOnly guarantees the mode 0700 on macOS")
        // The documented limit: the parent's inheritable entry is still inherited, for the file and for the directory.
        val fileEntries: List<String> = aclEntries(f("acl", "secret"))
        val directoryEntries: List<String> = aclEntries(f("acl", "private"))
        assertEquals(1, fileEntries.size, "the file inherits the parent's one entry (documented limit): $fileEntries")
        assertEquals(1, directoryEntries.size, "the directory inherits the parent's one entry (documented limit): $directoryEntries")
        for (entry: String in fileEntries + directoryEntries) {
            assertTrue(
                "group:everyone" in entry && "inherited" in entry && "allow" in entry,
                "the inherited entry is the parent's allow entry for everyone (documented limit): $entry",
            )
        }
        // The documented remedy: `chmod -N` removes the ACL, and the mode is still owner-only afterwards.
        assertEquals(0, run(listOf("chmod", "-N", f("acl", "secret").path, f("acl", "private").path)).first)
        assertEquals(listOf<String>(), aclEntries(f("acl", "secret")), "chmod -N clears the inherited entry (documented remedy)")
        assertEquals(listOf<String>(), aclEntries(f("acl", "private")), "chmod -N clears the inherited entry (documented remedy)")
        assertEquals("rw-------", mode(f("acl", "secret")))
        assertEquals("rwx------", mode(f("acl", "private")))
    }

    /** The ACL entry lines `ls -lde` prints for [file] (macOS): lines like ` 0: group:everyone inherited allow read`. */
    private fun aclEntries(file: File): List<String> {
        val (code: Int, output: String) = run(listOf("ls", "-lde", file.path))
        assertEquals(0, code, output)
        return output.lines().drop(1).map { it.trim() }.filter { it.isNotEmpty() }
    }

    private fun run(command: List<String>): Pair<Int, String> {
        val process: Process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output: String = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "hang: $command")
        return process.exitValue() to output
    }
}
