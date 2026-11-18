package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.AppEncryptionKey
import io.github.jamal_wia.kmptoolkit.storage.JvmAppKeyEncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import java.io.File
import java.lang.management.ManagementFactory
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume

/**
 * Group sto-desktop, round 6 (phase B breaker round 3, m1): the bound of 65 535 UTF-8 bytes on an entry name is applied by
 * counting, exactly like the store size limit for values (decision D-P10), so a huge name never allocates its encoded copy
 * before it is refused. The breaker class proves it on a small heap with an OutOfMemoryError; these tests prove it by
 * the bytes the calling thread allocates, which does not depend on the heap size or on what the encoder happens to need.
 * Every measuring path is covered: the pre-lock bound of `put` and `SecureFileFormat.entrySize`.
 */
class EncryptedStoreKeyBoundRound6Test {

    private val directory: File = Files.createTempDirectory("kmptoolkit-keybound6").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun allocationCounter(): com.sun.management.ThreadMXBean {
        val threads = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        Assume.assumeTrue("needs the JDK's allocation counter of a thread", threads != null && threads.isThreadAllocatedMemorySupported)
        checkNotNull(threads).isThreadAllocatedMemoryEnabled = true
        return threads
    }

    @Test
    fun `a huge entry name is refused by put without allocating its encoding`() = runBlocking {
        val threads: com.sun.management.ThreadMXBean = allocationCounter()
        val providerCalls = AtomicInteger()
        val key = AppEncryptionKey.Raw {
            providerCalls.incrementAndGet()
            ByteArray(32)
        }
        // The caller's thread does all the work: nothing is allocated on a pool thread the counter below cannot see.
        val store = JvmAppKeyEncryptedKeyValueStorage("keybound6", directory, key, Dispatchers.Unconfined, CrossProcessLock())
        val hugeName: String = "€".repeat(HUGE_CHARS)
        val utf8Bytes: Long = hugeName.strictUtf8Length()
        assertTrue(utf8Bytes > 10_000_000, "control: the name is far over the 65 535-byte bound ($utf8Bytes bytes)")
        val id: Long = Thread.currentThread().id
        store.put("warm-up", "v").let { assertIs<StorageResult.Success<Unit>>(it) }
        directory.listFiles().orEmpty().forEach { it.delete() }
        providerCalls.set(0)

        val before: Long = threads.getThreadAllocatedBytes(id)
        val result: StorageResult<Unit> = withTimeout(HANG_BOUND) { store.put(hugeName, "v") }
        val allocated: Long = threads.getThreadAllocatedBytes(id) - before

        val error: StorageError = assertIs<StorageResult.Failure>(result, "result: $result").error
        assertIs<StorageError.InvalidValue>(error)
        assertEquals(0, providerCalls.get(), "control: the name was refused before any key was asked for")
        assertTrue(
            allocated < ALLOWANCE_BYTES,
            "refusing a name of $utf8Bytes UTF-8 bytes allocated $allocated bytes: the name was encoded to measure it (allowance $ALLOWANCE_BYTES)",
        )
    }

    @Test
    fun `entrySize counts the name and never encodes it`() {
        val threads: com.sun.management.ThreadMXBean = allocationCounter()
        val hugeName: String = "€".repeat(HUGE_CHARS)
        val id: Long = Thread.currentThread().id
        SecureFileFormat.entrySize("warm-up", 0)

        val before: Long = threads.getThreadAllocatedBytes(id)
        val size: Long = SecureFileFormat.entrySize(hugeName, 7)
        val allocated: Long = threads.getThreadAllocatedBytes(id) - before

        assertEquals(2L + HUGE_CHARS * 3L + SecureFileFormat.NONCE_BYTES + 4 + 7 + SecureFileFormat.TAG_BYTES, size, "the size is the counted one")
        assertEquals(hugeName.toByteArray(StandardCharsets.UTF_8).size.toLong(), HUGE_CHARS * 3L, "control: three bytes per euro sign")
        assertTrue(allocated < ALLOWANCE_BYTES, "entrySize of a name of ${HUGE_CHARS * 3L} bytes allocated $allocated bytes: it encoded the name")
    }

    private companion object {
        const val HUGE_CHARS: Int = 6_000_000

        /** Far below the 18 MB an encoded copy of the name costs, far above the objects a refusal makes. */
        const val ALLOWANCE_BYTES: Long = 1_000_000
        val HANG_BOUND = 60.seconds
    }
}
