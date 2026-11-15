package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import io.github.jamal_wia.kmptoolkit.testsupport.TestFs
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Group sto-desktop, round 4 (phase A breaker round 2, M1 and m2): the lock-file channel of a contender that met another
 * handle of this JVM is parked instead of closed, exactly one per lock file and reused, and the no-hard-link creation path
 * treats a file another creator made first as success.
 */
class CrossProcessLockRound4Test {

    private val directory: File = Files.createTempDirectory("kmptoolkit-xlock-round4").toFile()
    private val lockFile: File = File(directory, "store.properties.lock")
    private val prefix = "store.properties"

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun isolatedLoader(): URLClassLoader {
        val urls: Array<URL> = System.getProperty("java.class.path").split(File.pathSeparator)
            .filter { it.isNotEmpty() }
            .map { File(it).toURI().toURL() }
            .toTypedArray()
        return URLClassLoader(urls, ClassLoader.getPlatformClassLoader())
    }

    private fun contend(loader: URLClassLoader, file: File = lockFile): String = loader.loadClass(IsolatedLockContender::class.java.name)
        .getMethod("contend", String::class.java, String::class.java)
        .invoke(null, file.path, prefix) as String

    @Test
    fun `a contender of a second copy keeps exactly one channel per lock file, reuses it, and locks through it once the holder lets go`() =
        runBlocking {
            val holder: LockAcquisition.Held = assertIs(CrossProcessLock().acquire(lockFile, prefix))
            isolatedLoader().use { loader: URLClassLoader ->
                assertNotSame(CrossProcessLock::class.java, loader.loadClass(CrossProcessLock::class.java.name), "control: a second copy")
                // Warm-up on a free lock file: loading the second copy's classes opens jar files, which must not be counted below.
                assertTrue(contend(loader, File(directory, "warm.properties.lock")).contains("Held"), "control: a free lock is taken")
                val withHolder: Long = TestFs.requireOpenDescriptorCount()

                assertEquals("TimedOut", contend(loader), "control: the second copy found the lock busy")
                val parked: Long = TestFs.requireOpenDescriptorCount()
                assertEquals(withHolder + 1, parked, "the contender's channel is kept open (one descriptor), not closed")

                assertEquals("TimedOut", contend(loader))
                assertEquals(parked, TestFs.requireOpenDescriptorCount(), "a second contention reuses the parked channel: no further descriptor")

                holder.close()
                assertEquals(parked - 1, TestFs.requireOpenDescriptorCount(), "the holder's own channel is closed as always")

                assertTrue(contend(loader).contains("Held"), "the second copy locks through its parked channel once the lock is free")
                assertEquals(parked - 1, TestFs.requireOpenDescriptorCount(), "taking and releasing the lock through the parked channel opens and closes nothing")
            }
            // The first copy still locks and unlocks normally and leaves nothing open beyond the parked channel.
            val again: LockAcquisition.Held = assertIs(CrossProcessLock().acquire(lockFile, prefix), "the lock is free for the first copy")
            again.close()
        }

    @Test
    fun `a lock file that another creator made first on a volume without links is success and keeps its mode`() = runBlocking {
        val real: FileAccess = createFileAccess()
        val racing = object : FileAccess by real {
            override fun writeAtomically(path: String, bytes: ByteArray, options: WriteOptions): FileResult<Unit> {
                if (path != lockFile.absolutePath) return real.writeAtomically(path, bytes, options)
                // The other creator wins between the metadata check and the creation attempt, on a volume that refuses links.
                Files.createFile(lockFile.toPath())
                return FileResult.Failure(FileError.Unsupported(path))
            }
        }

        val held: LockAcquisition = CrossProcessLock(racing).acquire(lockFile, prefix)

        assertIs<LockAcquisition.Held>(held, "AlreadyExists of the fallback is success, not a failure ($held)")
        held.close()
        assertTrue(lockFile.isFile)
    }
}
