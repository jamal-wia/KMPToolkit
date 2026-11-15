package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.testsupport.TestFs
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Breaker round 3 of group sto-desktop (phase A): the parked lock-file channel of round 4 (a channel whose `tryLock` met
 * another handle of this JVM is kept open, at most one per lock file; plan T17 "Lock FileChannels are closed after each
 * operation" for every other channel).
 *
 * The descriptor budget of a long-lived process with a second copy of the library: every failed acquisition of that copy
 * against a lock the first copy holds, over several lock files in a seeded order, costs at most one descriptor per lock
 * file, never one per failed acquisition, and the first copy's own channels are still closed after each operation.
 */
class CrossProcessLockBreakerR3Test {

    private val directory: File = Files.createTempDirectory("kmptoolkit-xlock-breaker3").toFile()
    private val prefix = "store.properties"

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun `failed acquisitions of a second copy over several lock files cost one descriptor per lock file, never one per attempt`() = runBlocking {
        val seed = 20_261_118L
        val random = Random(seed)
        val files: List<File> = (0 until FILES).map { File(directory, "s$it.properties.lock") }
        isolatedLoader().use { loader: URLClassLoader ->
            // Warm-up on a free lock file: loading the second copy's classes opens jar files, which must not be counted.
            assertTrue(contend(loader, File(directory, "warm.properties.lock")).contains("Held"), "seed=$seed control: a free lock is taken")
            val holders: List<LockAcquisition.Held> = files.map { assertIs(CrossProcessLock().acquire(it, prefix)) }
            try {
                val withHolders: Long = TestFs.requireOpenDescriptorCount()
                repeat(ATTEMPTS) { attempt: Int ->
                    val file: File = files[random.nextInt(files.size)]
                    assertEquals("TimedOut", contend(loader, file), "seed=$seed attempt=$attempt control: the second copy found ${file.name} busy")
                }
                val parked: Long = TestFs.requireOpenDescriptorCount() - withHolders
                assertTrue(parked > 0, "seed=$seed control: the second copy parked a channel")
                assertTrue(
                    parked <= FILES,
                    "seed=$seed: $ATTEMPTS failed acquisitions over $FILES lock files left $parked descriptors open (at most one per lock file)",
                )
            } finally {
                holders.forEach { it.close() }
            }
            val afterRelease: Long = TestFs.requireOpenDescriptorCount()
            repeat(ATTEMPTS) { attempt: Int ->
                val file: File = files[random.nextInt(files.size)]
                assertIs<LockAcquisition.Held>(CrossProcessLock().acquire(file, prefix), "seed=$seed attempt=$attempt").close()
            }
            assertEquals(afterRelease, TestFs.requireOpenDescriptorCount(), "seed=$seed: the first copy's channels are no longer closed after each operation")
        }
    }

    private fun isolatedLoader(): URLClassLoader {
        val urls: Array<URL> = System.getProperty("java.class.path").split(File.pathSeparator)
            .filter { it.isNotEmpty() }
            .map { File(it).toURI().toURL() }
            .toTypedArray()
        return URLClassLoader(urls, ClassLoader.getPlatformClassLoader())
    }

    private fun contend(loader: URLClassLoader, file: File): String = loader.loadClass(IsolatedLockContender::class.java.name)
        .getMethod("contend", String::class.java, String::class.java)
        .invoke(null, file.path, prefix) as String

    private companion object {
        const val FILES: Int = 3
        const val ATTEMPTS: Int = 24
    }
}
