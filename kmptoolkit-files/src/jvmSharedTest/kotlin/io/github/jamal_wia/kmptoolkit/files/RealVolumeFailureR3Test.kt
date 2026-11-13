package io.github.jamal_wia.kmptoolkit.files

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assume
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Round 3 of the breaker for unit G (failure injection with the real kernel errors): a full volume (ENOSPC), a
 * read-only volume (EROFS) and an exhausted descriptor table (EMFILE) on the JVM ops. 04-api-reference.md: ENOSPC is
 * `NoSpace`, EROFS is `AccessDenied`, anything else is `Io`; a failure leaves no temporary file and the path untouched.
 * The volumes are disk images mounted under a directory of the test and always detached; the test is skipped on a host
 * without `hdiutil` (not macOS).
 *
 * Opt-in: the tests depend on the host (`hdiutil` mounts, a real kernel) and the descriptor test exhausts the
 * descriptors of the whole test JVM, which a shared Gradle worker would pass on to every other class running in it, so
 * the default gate skips the class. Run it with the system property `kmptoolkit.test.hostfaults` equal to `true`, which
 * the build forwards from a Gradle property, for example
 * `./gradlew :kmptoolkit-files:jvmTest --tests '*RealVolumeFailureR3Test' -Pkmptoolkit.test.hostfaults=true`
 * (or `:kmptoolkit-files:testAndroidHostTest`, which runs the same source).
 */
class RealVolumeFailureR3Test {
    @BeforeTest
    fun requireHostFaults() {
        Assume.assumeTrue(
            "opt-in: run with -Pkmptoolkit.test.hostfaults=true (real volumes through hdiutil, real descriptor exhaustion)",
            System.getProperty("kmptoolkit.test.hostfaults") == "true",
        )
    }

    private val files: FileAccess = createFileAccess()

    private fun run(vararg command: String): Boolean =
        try {
            val process: Process = ProcessBuilder(*command).redirectErrorStream(true).start()
            val finished: Boolean = process.waitFor(60, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            finished && process.exitValue() == 0
        } catch (_: IOException) {
            false
        }

    /** Mounts a fresh 2 MB volume, lets [seed] fill it while writable, then mounts it as asked and runs [body]. */
    private fun withVolume(readOnly: Boolean, seed: (File) -> Unit = {}, body: (File) -> Unit) {
        val work: File = Files.createTempDirectory("kmptoolkit-vol-r3").toFile()
        val image: File = File(work, "volume.dmg")
        val mount: File = File(work, "mount")
        var attached: Boolean = false
        try {
            Assume.assumeTrue("hdiutil is needed", run("hdiutil", "create", "-size", "2m", "-fs", "HFS+", "-volname", "R3", image.path))
            assertTrue(mount.mkdir())
            assertTrue(run("hdiutil", "attach", "-mountpoint", mount.path, "-nobrowse", "-noverify", image.path), "attach")
            attached = true
            seed(mount)
            if (readOnly) {
                assertTrue(run("hdiutil", "detach", mount.path, "-force"), "detach before the read-only mount")
                attached = false
                assertTrue(run("hdiutil", "attach", "-readonly", "-mountpoint", mount.path, "-nobrowse", "-noverify", image.path), "attach read-only")
                attached = true
            }
            body(mount)
        } finally {
            if (attached) run("hdiutil", "detach", mount.path, "-force")
            work.deleteRecursively()
        }
    }

    private fun error(result: FileResult<*>): FileError = assertNotNull(result.errorOrNull(), "expected a failure but got $result")

    private fun temps(directory: File): List<String> = directory.list().orEmpty().filter { FileNames.isTempName(it) }

    @Test
    fun `a full volume answers NoSpace to every write keeps the old content and leaves no temp`() {
        withVolume(readOnly = false, seed = { mount: File ->
            File(mount, "keep").writeBytes(ByteArray(1000) { 7 })
            val chunk: ByteArray = ByteArray(1024)
            var index: Int = 0
            while (true) {
                try {
                    FileOutputStream(File(mount, "fill-${index++}")).use { it.write(chunk); it.fd.sync() }
                } catch (_: IOException) {
                    break
                }
                if (index > 4000) fail("the volume never filled")
            }
        }) { mount: File ->
            val keep: String = File(mount, "keep").path
            val big: ByteArray = ByteArray(3 shl 20) { 1 }
            for (durability: Durability in Durability.entries) {
                val replaced: FileResult<Unit> = files.writeAtomically(keep, big, WriteOptions(durability = durability))
                assertTrue(error(replaced) is FileError.NoSpace, "replace with $durability: $replaced")
                val created: FileResult<Unit> = files.writeAtomically(File(mount, "new-$durability").path, big, WriteOptions(durability = durability))
                assertTrue(error(created) is FileError.NoSpace, "create with $durability: $created")
            }
            assertContentEquals(ByteArray(1000) { 7 }, File(keep).readBytes())
            assertEquals(emptyList(), temps(mount))
            assertTrue(File(mount, "new-Synced").exists().not())
            val free: Long? = files.freeSpaceBytes(keep)
            assertNotNull(free)
            assertTrue(free < 64 * 1024, "free space of a full volume: $free")
            // reads and deletes still work, and freeing space makes writes work again
            assertEquals(1000L, files.metadata(keep)?.sizeBytes)
            assertTrue(files.delete(File(mount, "fill-0").path).isSuccess)
            assertTrue(files.delete(File(mount, "fill-1").path).isSuccess)
            assertTrue(files.writeAtomically(keep, ByteArray(500) { 9 }).isSuccess)
            assertContentEquals(ByteArray(500) { 9 }, File(keep).readBytes())
            assertEquals(emptyList(), temps(mount))
        }
    }

    @Test
    fun `a read-only volume answers AccessDenied to every mutation and still reads`() {
        withVolume(readOnly = true, seed = { mount: File ->
            File(mount, "d").mkdir()
            File(mount, "d/inner").writeText("inner")
            File(mount, "f").writeText("old")
        }) { mount: File ->
            val f: String = File(mount, "f").path
            val d: String = File(mount, "d").path
            assertTrue(error(files.writeAtomically(f, "new".encodeToByteArray())) is FileError.AccessDenied)
            assertTrue(error(files.writeAtomically(File(mount, "g").path, "new".encodeToByteArray())) is FileError.AccessDenied)
            assertTrue(error(files.writeAtomically(File(mount, "x/g").path, byteArrayOf(1), WriteOptions(createParentDirectories = true))) is FileError.AccessDenied)
            assertTrue(error(files.createDirectories(File(mount, "n/m").path)) is FileError.AccessDenied)
            assertTrue(error(files.delete(f)) is FileError.AccessDenied)
            assertTrue(error(files.deleteRecursively(d)) is FileError.AccessDenied)
            assertTrue(error(files.move(f, File(mount, "h").path)) is FileError.AccessDenied)
            assertTrue(error(files.move(f, File(mount, "h").path, MoveOptions(existing = ExistingFile.Fail))) is FileError.AccessDenied)
            assertEquals(FileResult.Success(0), files.sweep(mount.path, 0L, CancellationCheck.Never) { false })
            assertEquals("old", File(f).readText())
            assertEquals("inner", File(d, "inner").readText())
            assertEquals(emptyList(), temps(mount))
            assertContentEquals("old".encodeToByteArray(), files.readBytes(f, 100).getOrNull())
            assertEquals(FileResult.Success(8L), files.treeSize(mount.path))
            assertNull(files.metadata(File(mount, "g").path))
        }
    }

    @Test
    fun `an exhausted descriptor table makes the calls fail typed and nothing breaks afterwards`() {
        val root: File = Files.createTempDirectory("kmptoolkit-emfile-r3").toFile()
        try {
            File(root, "d").mkdir()
            File(root, "d/a").writeText("a")
            File(root, "f").writeText("old")
            val sink: File = File(root, "sink")
            sink.writeText("s")
            val held: MutableList<FileInputStream> = mutableListOf()
            val results: MutableMap<String, Any?> = linkedMapOf()
            val thrown: MutableMap<String, Throwable> = linkedMapOf()
            try {
                while (held.size < 300_000) {
                    try {
                        held += FileInputStream(sink)
                    } catch (_: IOException) {
                        break
                    }
                }
                fun record(name: String, call: () -> Any?) {
                    try {
                        results[name] = call()
                    } catch (t: Throwable) {
                        thrown[name] = t
                    }
                }
                record("readBytes") { files.readBytes(File(root, "f").path, 100) }
                record("write") { files.writeAtomically(File(root, "f").path, "new".encodeToByteArray()) }
                record("writeNew") { files.writeAtomically(File(root, "g").path, "new".encodeToByteArray()) }
                record("list") { files.list(File(root, "d").path) }
                record("deleteRecursively") { files.deleteRecursively(File(root, "d").path) }
                record("sweep") { files.sweep(File(root, "d").path, 0L, CancellationCheck.Never) { true } }
                record("move") { files.move(File(root, "f").path, File(root, "m").path) }
                record("treeSize") { files.treeSize(root.path) }
                record("metadata") { files.metadata(File(root, "f").path) }
            } finally {
                for (stream: FileInputStream in held) {
                    try { stream.close() } catch (_: IOException) { }
                }
            }
            assertTrue(held.size > 16, "no descriptor limit reached")
            assertEquals(emptyMap(), thrown, "calls that threw")
            for ((name: String, result: Any?) in results) {
                if (result is FileResult.Failure) {
                    assertTrue(result.error is FileError.Io, "$name under EMFILE: ${result.error}")
                }
            }
            assertTrue(results["readBytes"] is FileResult.Failure, "readBytes under EMFILE: ${results["readBytes"]}")
            assertTrue(results["write"] is FileResult.Failure, "write under EMFILE: ${results["write"]}")
            // after the table is free again the library works and nothing was half done; a rename needs no descriptor
            if (results["move"] is FileResult.Success<*>) assertTrue(File(root, "m").renameTo(File(root, "f")))
            assertEquals("old", File(root, "f").readText())
            assertEquals(emptyList(), temps(root))
            assertEquals("a", File(root, "d/a").readText())
            assertTrue(files.writeAtomically(File(root, "f").path, "new".encodeToByteArray()).isSuccess)
            assertEquals("new", File(root, "f").readText())
        } finally {
            root.deleteRecursively()
        }
    }
}
