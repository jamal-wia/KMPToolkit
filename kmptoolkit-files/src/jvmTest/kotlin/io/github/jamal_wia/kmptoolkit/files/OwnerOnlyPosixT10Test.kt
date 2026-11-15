@file:OptIn(ToolkitInternalApi::class, ExperimentalAtomicApi::class)

package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.internal.NioFileSystemOps
import io.github.jamal_wia.kmptoolkit.files.internal.OwnerOnlyAttributes
import io.github.jamal_wia.kmptoolkit.files.spi.FileOpException
import io.github.jamal_wia.kmptoolkit.files.spi.WritableFile
import io.github.jamal_wia.kmptoolkit.files.spi.fileAccessOver
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileAttribute
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import org.junit.Assume

/**
 * T10 on a POSIX host (Linux, macOS): store files, temps and directories the library creates are owner-only from the
 * moment they exist, an existing directory is never changed, an external chmod is undone by the next write, and a file
 * system that stores no permissions is ignored without an error. The Windows half is in [OwnerOnlyWindowsAclT10Test].
 */
class OwnerOnlyPosixT10Test {
    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        TestHost.assumePosixFileSemantics("owner-only modes; the Windows half is OwnerOnlyWindowsAclT10Test")
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

    @Test
    fun `an atomic write with ownerOnly leaves a file readable and writable by its owner only`() {
        val files: FileAccess = createFileAccess()
        val result: FileResult<Unit> = files.writeAtomically(p("secret"), byteArrayOf(1, 2, 3), WriteOptions(ownerOnly = true))
        assertTrue(result.isSuccess, "$result")
        assertEquals("rw-------", mode(f("secret")))
        assertContentEquals(byteArrayOf(1, 2, 3), f("secret").readBytes())
        assertEquals(listOf("secret"), root.list()!!.toList(), "no temp file is left behind")
    }

    @Test
    fun `ownerOnly holds for every durability`() {
        val files: FileAccess = createFileAccess()
        for (durability: Durability in Durability.values()) {
            val name: String = "d-$durability"
            val result: FileResult<Unit> = files.writeAtomically(p(name), byteArrayOf(7), WriteOptions(durability = durability, ownerOnly = true))
            assertTrue(result.isSuccess, "$durability: $result")
            assertEquals("rw-------", mode(f(name)), "$durability")
        }
    }

    @Test
    fun `the next ownerOnly write undoes an external chmod 644`() {
        val files: FileAccess = createFileAccess()
        assertTrue(files.writeAtomically(p("secret"), byteArrayOf(1), WriteOptions(ownerOnly = true)).isSuccess)
        chmod(f("secret"), "rw-r--r--")
        assertEquals("rw-r--r--", mode(f("secret")))
        assertTrue(files.writeAtomically(p("secret"), byteArrayOf(2), WriteOptions(ownerOnly = true)).isSuccess)
        assertEquals("rw-------", mode(f("secret")))
        assertContentEquals(byteArrayOf(2), f("secret").readBytes())
    }

    @Test
    fun `an ownerOnly write over a world readable file replaces it with an owner only one`() {
        f("old").writeBytes(byteArrayOf(9))
        chmod(f("old"), "rw-r--r--")
        val files: FileAccess = createFileAccess()
        assertTrue(files.writeAtomically(p("old"), byteArrayOf(1), WriteOptions(ownerOnly = true)).isSuccess)
        assertEquals("rw-------", mode(f("old")))
    }

    @Test
    fun `an ownerOnly write that must not replace leaves an owner only file`() {
        val files: FileAccess = createFileAccess()
        val result: FileResult<Unit> = files.writeAtomically(p("once"), byteArrayOf(4), WriteOptions(existing = ExistingFile.Fail, ownerOnly = true))
        assertTrue(result.isSuccess, "$result")
        assertEquals("rw-------", mode(f("once")))
        assertEquals(listOf("once"), root.list()!!.toList(), "the no-replace temp is removed")
    }

    @Test
    fun `the temp file is owner only when it is created, before a byte is written`() {
        val ops: NioFileSystemOps = NioFileSystemOps()
        val temp: String = p("fresh.tmp")
        val file: WritableFile = ops.createNew(temp, FileProtection.Default, true)
        try {
            assertEquals(0L, f("fresh.tmp").length(), "nothing written yet")
            assertEquals("rw-------", mode(f("fresh.tmp")))
        } finally {
            file.close()
        }
    }

    @Test
    fun `moving an owner only file keeps its mode`() {
        val files: FileAccess = createFileAccess()
        assertTrue(files.writeAtomically(p("a"), byteArrayOf(1), WriteOptions(ownerOnly = true)).isSuccess)
        assertTrue(files.move(p("a"), p("b")).isSuccess)
        assertEquals("rw-------", mode(f("b")))
    }

    @Test
    fun `createDirectories with ownerOnly makes every created level 0700 and keeps an existing 0755 level`() {
        val existing: File = f("existing")
        assertTrue(existing.mkdir())
        chmod(existing, "rwxr-xr-x")
        val files: FileAccess = createFileAccess()
        assertTrue(files.createDirectories(p("existing", "x", "y"), ownerOnly = true).isSuccess)
        assertEquals("rwxr-xr-x", mode(existing), "an existing directory is never changed")
        assertEquals("rwx------", mode(f("existing", "x")))
        assertEquals("rwx------", mode(f("existing", "x", "y")))
    }

    @Test
    fun `createDirectories with ownerOnly of an existing directory succeeds and changes nothing`() {
        val existing: File = f("open")
        assertTrue(existing.mkdir())
        chmod(existing, "rwxrwxr-x")
        val files: FileAccess = createFileAccess()
        assertTrue(files.createDirectories(p("open"), ownerOnly = true).isSuccess)
        assertEquals("rwxrwxr-x", mode(existing))
    }

    @Test
    fun `createDirectories with ownerOnly and durable still makes the levels 0700`() {
        val files: FileAccess = createFileAccess()
        assertTrue(files.createDirectories(p("a", "b"), durable = true, ownerOnly = true).isSuccess)
        assertEquals("rwx------", mode(f("a")))
        assertEquals("rwx------", mode(f("a", "b")))
    }

    @Test
    fun `an ownerOnly write with createParentDirectories creates its parents and its temp directory 0700 and keeps an existing parent`() {
        val existing: File = f("existing")
        assertTrue(existing.mkdir())
        chmod(existing, "rwxr-xr-x")
        val files: FileAccess = createFileAccess()
        val options: WriteOptions = WriteOptions(
            createParentDirectories = true,
            tempDirectory = p("existing", "scratch"),
            ownerOnly = true,
        )
        assertTrue(files.writeAtomically(p("existing", "new", "deep", "f"), byteArrayOf(1), options).isSuccess)
        assertEquals("rwxr-xr-x", mode(existing))
        assertEquals("rwx------", mode(f("existing", "new")))
        assertEquals("rwx------", mode(f("existing", "new", "deep")))
        assertEquals("rwx------", mode(f("existing", "scratch")))
        assertEquals("rw-------", mode(f("existing", "new", "deep", "f")))
    }

    @Test
    fun `without ownerOnly the mode is the one the process umask gives, not forced`() {
        // The references are made by plain java.nio in the same directory, so the umask of the test machine cancels out.
        val reference: Path = Files.createFile(f("reference").toPath())
        // path-hygiene-ignore: PH2 a POSIX-only test: setUp skips it on Windows (TestHost.assumePosixFileSemantics)
        val referenceMode: String = PosixFilePermissions.toString(Files.getPosixFilePermissions(reference))
        val referenceDirectory: Path = Files.createDirectory(f("reference-dir").toPath())
        // path-hygiene-ignore: PH2 a POSIX-only test: setUp skips it on Windows (TestHost.assumePosixFileSemantics)
        val referenceDirectoryMode: String = PosixFilePermissions.toString(Files.getPosixFilePermissions(referenceDirectory))
        // With a umask of 077 the plain result is owner-only already and the control proves nothing.
        Assume.assumeFalse("umask 077: a plain file is already 0600", referenceMode == "rw-------")
        Assume.assumeFalse("umask 077: a plain directory is already 0700", referenceDirectoryMode == "rwx------")
        val files: FileAccess = createFileAccess()
        assertTrue(files.writeAtomically(p("plain"), byteArrayOf(1)).isSuccess)
        assertTrue(files.createDirectories(p("plain-dir")).isSuccess)
        assertEquals(referenceMode, mode(f("plain")), "a write without ownerOnly keeps the umask mode")
        assertEquals(referenceDirectoryMode, mode(f("plain-dir")), "a directory without ownerOnly keeps the umask mode")
    }

    @Test
    fun `ownerOnly is ignored on Android style ops`() {
        val ignoring: NioFileSystemOps = NioFileSystemOps(applyOwnerOnly = false)
        val plain: NioFileSystemOps = NioFileSystemOps()
        val ignoredFile: String = p("ignored")
        val plainFile: String = p("plain")
        ignoring.createNew(ignoredFile, FileProtection.Default, true).close()
        plain.createNew(plainFile, FileProtection.Default, false).close()
        assertEquals(mode(f("plain")), mode(f("ignored")), "the same mode as a file made without the request")
        ignoring.createDirectory(p("ignored-dir"), true)
        plain.createDirectory(p("plain-dir"), false)
        assertEquals(mode(f("plain-dir")), mode(f("ignored-dir")))
    }

    @Test
    fun `a provider that reports neither a posix nor an acl view is ignored, the write succeeds`() {
        val attributes: OwnerOnlyAttributes = object : OwnerOnlyAttributes(true) {
            override fun supportedViews(path: Path): Set<String> = setOf("basic")
        }
        assertEquals(0, attributes.forFile(f("x").toPath()).size)
        assertEquals(0, attributes.forDirectory(f("x").toPath()).size)
        val files = fileAccessOver(NioFileSystemOps(attributes))
        assertTrue(files.writeAtomically(p("v", "f"), byteArrayOf(3), WriteOptions(createParentDirectories = true, ownerOnly = true)).isSuccess)
        assertTrue(files.createDirectories(p("w", "x"), ownerOnly = true).isSuccess)
        assertContentEquals(byteArrayOf(3), f("v", "f").readBytes())
        assertTrue(f("w", "x").isDirectory)
    }

    @Test
    fun `a provider that refuses the permission attribute fails closed and creates nothing, never a wider file`() {
        // The attribute a POSIX provider cannot take as an initial attribute: UnsupportedOperationException, as on a
        // provider without that view. There is no retry without it: that could only create the entry with wider access.
        val refused: FileAttribute<String> = object : FileAttribute<String> {
            override fun name(): String = "acl:acl"
            override fun value(): String = "not supported here"
        }
        val attributes: OwnerOnlyAttributes = object : OwnerOnlyAttributes(true) {
            override fun forFile(path: Path): Array<FileAttribute<*>> = arrayOf(refused)
            override fun forDirectory(path: Path): Array<FileAttribute<*>> = arrayOf(refused)
        }
        val files = fileAccessOver(NioFileSystemOps(attributes))
        val written: FileResult<Unit> = files.writeAtomically(p("v", "f"), byteArrayOf(8), WriteOptions(createParentDirectories = true, ownerOnly = true))
        assertTrue(written is FileResult.Failure && written.error is FileError.Unsupported, "$written")
        assertFalse(f("v").exists(), "the directory the attribute was refused for was not created without it")
        val made: FileResult<Unit> = files.createDirectories(p("w", "x"), ownerOnly = true)
        assertTrue(made is FileResult.Failure && made.error is FileError.Unsupported, "$made")
        assertFalse(f("w").exists())
        val direct: FileResult<Unit> = files.writeAtomically(p("g"), byteArrayOf(1), WriteOptions(ownerOnly = true))
        assertTrue(direct is FileResult.Failure && direct.error is FileError.Unsupported, "$direct")
        assertEquals(listOf<String>(), root.list()!!.toList(), "no temp file is left behind")
    }

    @Test
    fun `the ownerOnly request reaches the real ops through a decorating ops`() {
        // FaultOps decorates by overriding, not by delegating: with the optional overloads of the first draft the engine's
        // three-argument call fell to a default and never reached the real ops.
        val files: FileAccess = fileAccessOver(FaultOps(NioFileSystemOps()))
        val options: WriteOptions = WriteOptions(createParentDirectories = true, tempDirectory = p("scratch"), ownerOnly = true)
        assertTrue(files.writeAtomically(p("d", "e", "f"), byteArrayOf(1), options).isSuccess)
        assertEquals("rw-------", mode(f("d", "e", "f")))
        assertEquals("rwx------", mode(f("d")))
        assertEquals("rwx------", mode(f("d", "e")))
        assertEquals("rwx------", mode(f("scratch")))
        assertTrue(files.createDirectories(p("g", "h"), ownerOnly = true).isSuccess)
        assertEquals("rwx------", mode(f("g", "h")))
    }

    @Test
    fun `an ownerOnly request on a path whose parent is missing is NotFound, as without it`() {
        val files: FileAccess = createFileAccess()
        val result: FileResult<Unit> = files.writeAtomically(p("missing", "f"), byteArrayOf(1), WriteOptions(ownerOnly = true))
        assertTrue(result is FileResult.Failure && result.error is FileError.NotFound, "$result")
    }

    @Test
    fun `an owner only creation that clashes is AlreadyExists`() {
        val ops: NioFileSystemOps = NioFileSystemOps()
        ops.createNew(p("taken"), FileProtection.Default, true).close()
        val thrown: FileOpException = org.junit.Assert.assertThrows(FileOpException::class.java) {
            ops.createNew(p("taken"), FileProtection.Default, true)
        }
        assertTrue(thrown.error is FileError.AlreadyExists, "${thrown.error}")
        val thrownDirectory: FileOpException = org.junit.Assert.assertThrows(FileOpException::class.java) {
            ops.createDirectory(p("taken"), true)
        }
        assertTrue(thrownDirectory.error is FileError.AlreadyExists, "${thrownDirectory.error}")
        // path-hygiene-ignore: PH2 a POSIX-only test: setUp skips it on Windows (TestHost.assumePosixFileSemantics)
        assertTrue(PosixFilePermission.OWNER_READ in Files.getPosixFilePermissions(f("taken").toPath()))
    }

    @Test
    fun `concurrent ownerOnly writers and directory creators leave only 0600 files and 0700 directories`() =
        runTest(timeout = 90.seconds) {
            val seed: Int = 1006
            val instances: List<FileAccess> = listOf(createFileAccess(), createFileAccess())
            val failures: AtomicInt = AtomicInt(0)
            val names: List<List<String>> = listOf(listOf("a.dat"), listOf("n", "b.dat"), listOf("n", "m", "c.dat"))
            withContext(Dispatchers.Default) {
                withTimeout(60.seconds) {
                    coroutineScope {
                        repeat(12) { worker: Int ->
                            launch {
                                val random: Random = Random(seed * 100 + worker)
                                repeat(60) {
                                    val files: FileAccess = instances[random.nextInt(instances.size)]
                                    if (random.nextInt(3) == 0) {
                                        val made: FileResult<Unit> = files.createDirectories(p("n", "m", "k${random.nextInt(3)}"), ownerOnly = true)
                                        if (made !is FileResult.Success) failures.addAndFetch(1)
                                    } else {
                                        val options: WriteOptions = WriteOptions(
                                            durability = Durability.Buffered,
                                            createParentDirectories = true,
                                            ownerOnly = true,
                                        )
                                        val written: FileResult<Unit> =
                                            files.writeAtomically(p(*names[random.nextInt(names.size)].toTypedArray()), byteArrayOf(worker.toByte()), options)
                                        if (written !is FileResult.Success) failures.addAndFetch(1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            assertEquals(0, failures.load(), "seed=$seed: calls failed")
            root.walkTopDown().forEach { entry: File ->
                if (entry == root) return@forEach
                val expected: String = if (entry.isDirectory) "rwx------" else "rw-------"
                assertEquals(expected, mode(entry), "seed=$seed: ${entry.relativeTo(root)}")
            }
        }
}
