package io.github.jamal_wia.kmptoolkit.storage

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The key lock is per alias (test A3): one alias whose key-store call never returns stalls nothing but its own
 * alias, a resolved key is answered without any lock, and the callers of one alias still never overlap.
 *
 * Every wait is a latch or a deferred, never a sleep. The millisecond and second bounds are hang bounds only: a
 * correct run never reaches them.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidKeystoreAliasLockTest {

    /**
     * A key store that counts, per alias, how many of its calls are in flight at once, and parks the lookup of
     * [parkOn] until the test releases it (a key-store call that does not return).
     */
    private class AliasStore(private val parkOn: String? = null) : AliasedKeyStore {
        val entered: CountDownLatch = CountDownLatch(1)
        val release: CountDownLatch = CountDownLatch(1)
        private val entries: ConcurrentHashMap<String, SecretKey> = ConcurrentHashMap()
        private val inFlight: ConcurrentHashMap<String, AtomicInteger> = ConcurrentHashMap()
        private val maxInFlight: ConcurrentHashMap<String, AtomicInteger> = ConcurrentHashMap()
        private val lookups: ConcurrentHashMap<String, AtomicInteger> = ConcurrentHashMap()
        private val generations: ConcurrentHashMap<String, AtomicInteger> = ConcurrentHashMap()

        private fun counter(map: ConcurrentHashMap<String, AtomicInteger>, alias: String): AtomicInteger =
            map.computeIfAbsent(alias) { AtomicInteger() }

        private fun <T> traced(alias: String, body: () -> T): T {
            val now: Int = counter(inFlight, alias).incrementAndGet()
            counter(maxInFlight, alias).accumulateAndGet(now) { a: Int, b: Int -> maxOf(a, b) }
            try {
                return body()
            } finally {
                counter(inFlight, alias).decrementAndGet()
            }
        }

        override fun existingKey(alias: String, mayDelete: Boolean): SecretKey? = traced(alias) {
            counter(lookups, alias).incrementAndGet()
            if (alias == parkOn) {
                entered.countDown()
                check(release.await(PARK_BOUND_SECONDS, TimeUnit.SECONDS)) { "the parked lookup was never released" }
            }
            entries[alias]
        }

        override fun generateKey(alias: String): SecretKey = traced(alias) {
            counter(generations, alias).incrementAndGet()
            TestKeys.generate().also { fresh: SecretKey -> entries[alias] = fresh }
        }

        override fun deleteKey(alias: String) {
            traced(alias) { entries.remove(alias) }
        }

        fun live(alias: String): SecretKey? = entries[alias]
        fun maxOverlap(alias: String): Int = maxInFlight[alias]?.get() ?: 0
        fun lookupCount(alias: String): Int = lookups[alias]?.get() ?: 0
        fun generationCount(alias: String): Int = generations[alias]?.get() ?: 0
    }

    @Test
    fun `A3 an alias whose key resolution never returns does not stall another alias`() = runBlocking<Unit> {
        val store = AliasStore(parkOn = "stuck.alias")
        val hot: SecretKey = AndroidKeystoreKeySource("hot.alias", store).key()
        val stuck: Job = async(Dispatchers.IO) { AndroidKeystoreKeySource("stuck.alias", store).key() }
        try {
            assertTrue(store.entered.await(HANG_BOUND_SECONDS, TimeUnit.SECONDS), "the stuck lookup never started")

            withTimeout(HANG_BOUND_MS) {
                // A cached key of another alias, a first resolution of a new alias, and a whole store over it.
                assertSame(hot, AndroidKeystoreKeySource("hot.alias", store).key())
                val fresh: SecretKey = AndroidKeystoreKeySource("free.alias", store).key()
                assertSame(store.live("free.alias"), fresh)
                val preferences = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
                    .getSharedPreferences("a3.free", android.content.Context.MODE_PRIVATE)
                val storage = AndroidSecureKeyValueStorage(
                    preferences,
                    KeystoreValueCipher(AndroidKeystoreKeySource("free.alias", store)),
                    Dispatchers.IO,
                )
                assertEquals(StorageResult.Success(Unit), storage.put("k", "v"))
                assertEquals(StorageResult.Success<String?>("v"), storage.get("k"))
            }
            assertTrue(stuck.isActive, "control: the stuck alias is still parked while the others completed")
        } finally {
            store.release.countDown()
        }
        withTimeout(HANG_BOUND_MS) { stuck.join() }
        assertFalse(stuck.isCancelled)
    }

    @Test
    fun `a second caller of the parked alias waits for the lock instead of calling the key store, and a cancelled waiter wedges nothing`() = runBlocking<Unit> {
        val store = AliasStore(parkOn = "shared.alias")
        val first: Job = async(Dispatchers.IO) { AndroidKeystoreKeySource("shared.alias", store).key() }
        try {
            assertTrue(store.entered.await(HANG_BOUND_SECONDS, TimeUnit.SECONDS), "the first lookup never started")
            // UNDISPATCHED: the waiters run on this thread up to their first suspension, which is the alias lock.
            val cancelled: Job = async(start = CoroutineStart.UNDISPATCHED) { AndroidKeystoreKeySource("shared.alias", store).key() }
            val second: Job = async(start = CoroutineStart.UNDISPATCHED) { AndroidKeystoreKeySource("shared.alias", store).key() }
            assertTrue(cancelled.isActive && second.isActive, "control: both waiters are parked on the alias lock")
            assertEquals(1, store.lookupCount("shared.alias"), "a waiter must not call the key store while the lock is held")
            cancelled.cancel()
        } finally {
            store.release.countDown()
        }
        withTimeout(HANG_BOUND_MS) {
            first.join()
            val key: SecretKey = AndroidKeystoreKeySource("shared.alias", store).key()
            assertSame(store.live("shared.alias"), key)
        }
        assertEquals(1, store.lookupCount("shared.alias"), "the waiters found the first caller's key")
        assertEquals(1, store.generationCount("shared.alias"), "and made no key of their own")
        assertEquals(1, store.maxOverlap("shared.alias"))
    }

    @Test
    fun `a seeded storm over several aliases never overlaps two key-store calls of one alias and ends with one live key each`() = runBlocking<Unit> {
        val seed: Long = System.getProperty("kmptoolkit.test.seed")?.toLongOrNull() ?: DEFAULT_SEED
        val store = AliasStore()
        val aliases: List<String> = (0 until ALIASES).map { index: Int -> "storm.alias.$index" }

        withTimeout(HANG_BOUND_MS) {
            (0 until WORKERS).map { worker: Int ->
                async(Dispatchers.Default) {
                    val random = Random(seed * 1000 + worker)
                    repeat(STEPS) {
                        val source = AndroidKeystoreKeySource(aliases[random.nextInt(ALIASES)], store)
                        val key: SecretKey = source.key()
                        when (random.nextInt(4)) {
                            0 -> source.invalidate(key)
                            1 -> source.discard(key)
                            else -> Unit
                        }
                    }
                }
            }.awaitAll()
        }

        for (alias: String in aliases) {
            assertEquals(1, store.maxOverlap(alias), "seed=$seed alias=$alias: two key-store calls of one alias overlapped")
            val key: SecretKey = AndroidKeystoreKeySource(alias, store).key()
            assertSame(store.live(alias), key, "seed=$seed alias=$alias: the cached key is not the key store's")
        }
    }

    private companion object {
        const val DEFAULT_SEED: Long = 7L
        const val ALIASES = 4
        const val WORKERS = 24
        const val STEPS = 200
        const val HANG_BOUND_SECONDS = 20L
        const val PARK_BOUND_SECONDS = 120L
        const val HANG_BOUND_MS = 60_000L
    }
}
