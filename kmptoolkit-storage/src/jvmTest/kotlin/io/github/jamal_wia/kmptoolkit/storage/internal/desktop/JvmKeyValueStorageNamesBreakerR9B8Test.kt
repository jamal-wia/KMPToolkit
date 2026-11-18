package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageConfig
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.createKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.temporaryPrefixOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.security.MessageDigestSpi
import java.security.Provider
import java.security.ProviderException
import java.security.Security
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Breaker round 8 of phase B of group `sto-desktop` (review/sto-desktop-B-breaker-8), against task/sto-desktop 74357cee: the
 * plain store's per-write names of round 10 (B7-m1). The constructor keeps a failing SHA-256 to itself, every write computes the
 * lock-file and temporary-file names again. Attacked: a provider that fails a few times and then works, under a seeded storm of
 * first writes on one instance (the retry races itself); the names computed after a failure must be the names a healthy instance
 * uses (one lock file, a temporary prefix that `clear()` recognises); reads never need the names; a failed write touches nothing.
 *
 * The stand-in [B8FlakyDigestProvider] serves SHA-256 through SUN and fails the next N digests whose input contains a marker (the
 * store's name), like a PKCS#11 token error. Inserted first, removed in `finally`. Fixed seeds, no sleeps.
 */
class JvmKeyValueStorageNamesBreakerR9B8Test {

    private fun <T> withFlakyDigest(marker: String, failures: Int, block: () -> T): T {
        B8FlakyDigestSpi.marker = marker.toByteArray(StandardCharsets.UTF_8)
        B8FlakyDigestSpi.failNext.set(failures)
        check(Security.insertProviderAt(B8FlakyDigestProvider(), 1) == 1) { "control: the stand-in provider is first" }
        try {
            return block()
        } finally {
            Security.removeProvider(B8FlakyDigestProvider.NAME)
            B8FlakyDigestSpi.marker = null
            B8FlakyDigestSpi.failNext.set(0)
        }
    }

    /** A store name whose file name is 253 bytes: both the lock-file name (251+) and the temporary prefix (233+) are hashed. */
    private fun longName(tag: String): String = tag + "x".repeat(223 - tag.length)

    private fun fileOf(directory: File, name: String): File = File(directory, "$name.kmptoolkit.storage.properties")

    /**
     * A seeded storm of first writes (8 writers, distinct keys, `Dispatchers.Default`) on ONE instance made while SHA-256 fails, the
     * provider failing a seeded number of further digests (1 to 6) and then working. Every write answers `Success` or
     * `OperationFailed` of its own operation and key with a `JcaFailure` cause (never a throw, never another error); every write that
     * answered `Success` is in the file; the directory holds exactly the store file and ONE lock file, whose name is the one a
     * healthy instance derives; no temporary file is left. Seeds 1..6, the seed in every message.
     */
    @Test
    fun `first writes racing a provider that fails a few times then works lose nothing and agree on one lock file`() = runBlocking {
        withTimeout(HANG_BOUND) {
            var failedWrites = 0
            for (seed: Int in 1..6) {
                val random = Random(seed)
                val directory: File = Files.createTempDirectory("kmptoolkit-b8-names").toFile()
                try {
                    val name: String = longName("b8storm$seed-")
                    val file: File = fileOf(directory, name)
                    assertEquals(253, file.name.toByteArray(StandardCharsets.UTF_8).size, "seed=$seed: control, the file name is hashed")
                    val failures: Int = 1 + random.nextInt(6)
                    val answers: List<Pair<String, StorageResult<Unit>>> = withFlakyDigest(name, failures) {
                        val store: KeyValueStorage = createKeyValueStorage(directory, StorageConfig(name))
                        runBlocking {
                            (0 until WRITERS).map { writer: Int ->
                                async(Dispatchers.Default) { "k$writer" to store.put("k$writer", "v-k$writer") }
                            }.awaitAll()
                        }
                    }
                    val written: MutableSet<String> = mutableSetOf()
                    for ((key: String, answer: StorageResult<Unit>) in answers) {
                        when (answer) {
                            is StorageResult.Success -> written += key
                            is StorageResult.Failure -> {
                                failedWrites++
                                val failed: StorageError.OperationFailed = assertIs(answer.error, "seed=$seed $key: ${answer.error}")
                                assertEquals(StorageOperation.PUT, failed.operation, "seed=$seed $key: operation")
                                assertEquals(key, failed.key, "seed=$seed $key: key")
                                assertIs<JcaFailure>(failed.cause, "seed=$seed $key: cause ${failed.cause}")
                            }
                        }
                    }
                    assertTrue(written.isNotEmpty(), "seed=$seed: control, the provider works again after $failures failures, so some write succeeds")
                    val healthy: KeyValueStorage = createKeyValueStorage(directory, StorageConfig(name))
                    for (key: String in written) {
                        assertEquals(StorageResult.Success("v-$key"), healthy.get(key), "seed=$seed: $key answered Success but is not in the file (failures=$failures)")
                    }
                    val expectedLock: String = CrossProcessLock.lockFileOf(file).name
                    assertEquals(listOf(expectedLock, file.name).sorted(), directory.list().orEmpty().sorted(), "seed=$seed: the directory after the storm (failures=$failures)")
                } finally {
                    directory.deleteRecursively()
                }
            }
            assertTrue(failedWrites > 0, "control: some write of the storm met the failing provider (seeds 1..6)")
        }
        Unit
    }

    /**
     * While SHA-256 fails for good: reads of an existing store work (they need neither name), every write answers `OperationFailed`
     * and leaves the file byte-identical, `clear()` included (the file is still there). Once the provider works, the SAME instance's
     * `clear()` removes a leftover temporary file named with the healthy prefix: the prefix computed after the failure is the one
     * a healthy instance uses, so the sweep finds this store's own leftovers.
     */
    @Test
    fun `while SHA-256 fails reads work and writes touch nothing and afterwards clear sweeps the store's own leftovers`() = runBlocking {
        withTimeout(HANG_BOUND) {
            val directory: File = Files.createTempDirectory("kmptoolkit-b8-names").toFile()
            try {
                val name: String = longName("b8read-")
                val file: File = fileOf(directory, name)
                assertEquals(StorageResult.Success(Unit), createKeyValueStorage(directory, StorageConfig(name)).put("a", "1"), "control")
                val before: ByteArray = file.readBytes()
                val leftover = File(directory, temporaryPrefixOf(file.name) + "12345.tmp")
                val store: KeyValueStorage = withFlakyDigest(name, Int.MAX_VALUE) {
                    val made: KeyValueStorage = createKeyValueStorage(directory, StorageConfig(name))
                    runBlocking {
                        assertEquals(StorageResult.Success("1"), made.get("a"), "a read while the provider fails")
                        for ((label: String, answer: StorageResult<Unit>) in listOf("put" to made.put("b", "2"), "remove" to made.remove("a"), "clear" to made.clear())) {
                            val error: StorageError = assertIs<StorageResult.Failure>(answer, "$label: $answer").error
                            assertIs<JcaFailure>(assertIs<StorageError.OperationFailed>(error, "$label: $error").cause, "$label: cause")
                        }
                    }
                    assertContentEquals(before, file.readBytes(), "a failed write changed the file")
                    made
                }
                leftover.writeBytes(byteArrayOf(1, 2, 3))
                assertEquals(StorageResult.Success(Unit), store.clear(), "clear once the provider works")
                assertTrue(!leftover.exists(), "clear did not sweep the store's own leftover: the prefix computed after the failure differs")
                assertTrue(!file.exists(), "clear left the store file")
                assertEquals(StorageResult.Success(Unit), store.put("c", "3"), "the same instance writes")
            } finally {
                directory.deleteRecursively()
            }
        }
        Unit
    }

    private companion object {
        const val WRITERS: Int = 8
        val HANG_BOUND = 120.seconds
    }
}

/** Serves `MessageDigest.SHA-256` through [B8FlakyDigestSpi]. */
class B8FlakyDigestProvider : Provider(NAME, "1.0", "breaker B8: SHA-256 that fails a few times like a PKCS#11 token") {
    init {
        put("MessageDigest.SHA-256", B8FlakyDigestSpi::class.java.name)
    }

    companion object {
        const val NAME: String = "KmpToolkitBreakerB8FlakyDigest"
    }
}

/** SHA-256 of SUN; the next [failNext] digests whose input contains [marker] fail with a token error. */
class B8FlakyDigestSpi : MessageDigestSpi() {
    private val real: MessageDigest = MessageDigest.getInstance("SHA-256", checkNotNull(Security.getProvider("SUN")) { "control: SUN exists" })
    private val seen = ByteArrayOutputStream()

    override fun engineGetDigestLength(): Int = 32

    override fun engineUpdate(input: Byte) {
        real.update(input)
        seen.write(input.toInt())
    }

    override fun engineUpdate(input: ByteArray, offset: Int, len: Int) {
        real.update(input, offset, len)
        seen.write(input, offset, len)
    }

    override fun engineDigest(): ByteArray {
        val input: ByteArray = seen.toByteArray()
        seen.reset()
        val failing: ByteArray? = marker
        if (failing != null && contains(input, failing) && failNext.getAndUpdate { left: Int -> if (left > 0) left - 1 else 0 } > 0) {
            real.reset()
            throw ProviderException("digest() failed (breaker B8 stand-in for a transient PKCS#11 token error)")
        }
        return real.digest()
    }

    override fun engineReset() {
        real.reset()
        seen.reset()
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        return (0..haystack.size - needle.size).any { start: Int -> needle.indices.all { haystack[start + it] == needle[it] } }
    }

    companion object {
        @Volatile
        var marker: ByteArray? = null

        val failNext: AtomicInteger = AtomicInteger()
    }
}
