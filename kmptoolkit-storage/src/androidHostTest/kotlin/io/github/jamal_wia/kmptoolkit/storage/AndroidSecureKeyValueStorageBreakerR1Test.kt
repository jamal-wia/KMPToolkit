package io.github.jamal_wia.kmptoolkit.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import java.security.InvalidKeyException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Breaker round 1 of group sto-api (STO-2, plan 4.9 "ensureKey() resolves or creates the Keystore key and
 * runs one cipher init", ticket release-5-1-0): the provisional Android `ensureKey` attacked with a key the
 * platform rejects mid-call, a key that stays rejected, a first-run storm of `ensureKey` and `put` over two
 * instances racing for one alias, and a caller cancelled while it waits for the process-wide key lock.
 *
 * Contract: `EncryptedKeyValueStorage.ensureKey` KDoc (resolves or creates and verifies the key, no effect on
 * the stored values, otherwise the typed failure the other operations would answer), D-15..D-17 (an
 * unexpected failure is `OperationFailed(ENSURE_KEY, key = null)`), REVIEW-ADDENDUM 3 (CancellationException
 * is never swallowed), `AndroidKeystoreKeySource` KDoc (one key per alias in the process). Real-time bounds
 * are hang bounds only; the storm seed is named in every failure message.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidSecureKeyValueStorageBreakerR1Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun preferences(name: String = NAME): SharedPreferences =
        context.getSharedPreferences(secureStoreId(name), Context.MODE_PRIVATE)

    @Before
    fun emptyTheFile() {
        preferences().edit().clear().commit()
    }

    /** A key the platform refuses at `Cipher.init`, as it refuses a key the Keystore invalidated. */
    private val rejected: SecretKey = SecretKeySpec(ByteArray(7), "AES")

    /** A source that hands out [answers] in order (then the last one), recording every invalidation. */
    private class ScriptedSource(private vararg val answers: SecretKey) : SecretKeySource {
        val lookups: AtomicInteger = AtomicInteger()
        val invalidated: MutableList<SecretKey> = Collections.synchronizedList(mutableListOf())

        override suspend fun key(): SecretKey = answers[minOf(lookups.getAndIncrement(), answers.lastIndex)]

        override suspend fun invalidate(stale: SecretKey) {
            invalidated += stale
        }
    }

    /**
     * An alias store with the real one's shape (generate replaces), thread safe, counting generations. When
     * [hold] is set, the first lookup parks the calling thread on it after signalling [entered].
     */
    private class CountingKeyStore(
        private val hold: CountDownLatch? = null,
        private val entered: CountDownLatch? = null,
    ) : AliasedKeyStore {
        private val keys: ConcurrentHashMap<String, SecretKey> = ConcurrentHashMap()
        private val firstLookup: AtomicInteger = AtomicInteger()
        val generated: AtomicInteger = AtomicInteger()

        fun live(alias: String): SecretKey? = keys[alias]

        override fun existingKey(alias: String, mayDelete: Boolean): SecretKey? {
            if (hold != null && firstLookup.getAndIncrement() == 0) {
                entered?.countDown()
                check(hold.await(30, TimeUnit.SECONDS)) { "the test never released the held lookup" }
            }
            return keys[alias]
        }

        override fun generateKey(alias: String): SecretKey {
            val key: SecretKey = TestKeys.generate()
            keys[alias] = key
            generated.incrementAndGet()
            return key
        }
    }

    private fun store(source: SecretKeySource, dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined) =
        AndroidSecureKeyValueStorage(preferences(), KeystoreValueCipher(source), dispatcher)

    @Test
    fun `ensureKey meeting a key the platform rejects drops that key once and succeeds with a fresh one`() = runTest {
        val good: SecretKey = TestKeys.generate()
        val source = ScriptedSource(rejected, good)
        val storage = store(source)

        assertEquals(StorageResult.Success(Unit), storage.ensureKey())

        assertEquals(listOf(rejected), source.invalidated.toList(), "exactly the rejected key is reported, once")
        assertEquals(2, source.lookups.get(), "one lookup, then one re-lookup after the rejection")
        assertEquals(emptySet(), preferences().all.keys, "ensureKey wrote an entry")
        // The store works on the key ensureKey settled on.
        assertEquals(StorageResult.Success(Unit), storage.put("k", "v"))
        assertEquals(StorageResult.Success("v"), storage.get("k"))
    }

    @Test
    fun `ensureKey whose re-resolved key is rejected again is OperationFailed for ENSURE_KEY without a key and stores nothing`() = runTest {
        val source = ScriptedSource(rejected)
        val storage = store(source)

        val error: StorageError? = storage.ensureKey().errorOrNull()

        assertTrue(
            error is StorageError.OperationFailed && error.operation == StorageOperation.ENSURE_KEY && error.key == null,
            "was $error",
        )
        assertTrue(error.cause is InvalidKeyException, "cause was ${error.cause}")
        assertEquals(2, source.lookups.get(), "the rejection must be retried exactly once, never looped")
        assertEquals(emptySet(), preferences().all.keys)
    }

    @Test
    fun `a first-run storm of ensureKey and put over two instances creates one key and every value reads back`() = runTest(timeout = 120.seconds) {
        val seed = 1201
        val keyStore = CountingKeyStore()
        val stores: List<AndroidSecureKeyValueStorage> = List(2) { store(AndroidKeystoreKeySource(ALIAS, keyStore), Dispatchers.IO) }
        val failures: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val written: ConcurrentHashMap<String, String> = ConcurrentHashMap()

        withContext(Dispatchers.Default) {
            withTimeout(90.seconds) {
                coroutineScope {
                    repeat(16) { worker: Int ->
                        launch {
                            val random = Random(seed * 1000 + worker)
                            val storage: AndroidSecureKeyValueStorage = stores[worker % 2]
                            repeat(25) { step: Int ->
                                if (random.nextBoolean()) {
                                    val result: StorageResult<Unit> = storage.ensureKey()
                                    if (result != StorageResult.Success(Unit)) failures += "worker $worker step $step ensureKey $result"
                                } else {
                                    val key = "w$worker-$step"
                                    val result: StorageResult<Unit> = storage.put(key, "v$step")
                                    if (result == StorageResult.Success(Unit)) written[key] = "v$step" else failures += "worker $worker step $step put $result"
                                }
                            }
                        }
                    }
                }
            }
        }

        if (failures.isNotEmpty()) fail("seed=$seed: ${failures.size} failure(s), first ${failures.first()}")
        assertEquals(1, keyStore.generated.get(), "seed=$seed: keys generated for one alias")
        val reader: AndroidSecureKeyValueStorage = store(AndroidKeystoreKeySource(ALIAS, keyStore))
        assertTrue(written.isNotEmpty(), "seed=$seed: control: the storm wrote nothing")
        for ((key: String, value: String) in written) {
            assertEquals(StorageResult.Success(value), reader.get(key), "seed=$seed: $key")
        }
        assertEquals(written.keys.toSet(), preferences().all.keys, "seed=$seed: ensureKey must not add entries")
    }

    @Test
    fun `ensureKey cancelled while it waits for the process-wide key lock throws and leaves the lock usable`() = runTest(timeout = 60.seconds) {
        val hold = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val keyStore = CountingKeyStore(hold = hold, entered = entered)
        val holderStore: AndroidSecureKeyValueStorage = store(AndroidKeystoreKeySource(ALIAS, keyStore), Dispatchers.IO)
        // Unconfined: the waiter's call runs inline up to its first real suspension, the lock wait.
        val waiterStore: AndroidSecureKeyValueStorage = store(AndroidKeystoreKeySource(ALIAS, keyStore), Dispatchers.Unconfined)
        var holderOutcome: StorageResult<Unit>? = null
        var waiterOutcome: StorageResult<Unit>? = null
        try {
            val holder: Job = launch(Dispatchers.IO) { holderOutcome = holderStore.ensureKey() }
            withContext(Dispatchers.IO) { assertTrue(entered.await(30, TimeUnit.SECONDS), "the holder never reached the key store") }

            val waiter: Job = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { waiterOutcome = waiterStore.ensureKey() }
            assertTrue(waiter.isActive, "control: the waiter must be suspended on the key lock")
            waiter.cancel()
            withTimeout(30.seconds) { waiter.join() }

            assertTrue(waiter.isCancelled, "the waiter was not cancelled")
            assertNull(waiterOutcome, "a cancelled ensureKey reported a result instead of throwing")

            hold.countDown()
            withContext(Dispatchers.Default) { withTimeout(30.seconds) { holder.join() } }
            assertEquals(StorageResult.Success(Unit), holderOutcome)
        } finally {
            // Never leave the process-wide lock held for the next test class in this JVM.
            hold.countDown()
        }

        withContext(Dispatchers.Default) {
            withTimeout(30.seconds) { assertEquals(StorageResult.Success(Unit), waiterStore.ensureKey()) }
        }
        assertEquals(1, keyStore.generated.get(), "keys generated")
        assertSame(keyStore.live(ALIAS), AndroidKeystoreKeySource(ALIAS, keyStore).key())
        assertEquals(emptySet(), preferences().all.keys)
    }

    private companion object {
        const val NAME = "io.github.jamal_wia.kmptoolkit.storage.breaker.r1"
        const val ALIAS = "breaker.r1.alias"
    }
}
