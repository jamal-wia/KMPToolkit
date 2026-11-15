package io.github.jamal_wia.kmptoolkit.storage

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyPermanentlyInvalidatedException
import androidx.test.core.app.ApplicationProvider
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Breaker review round 2 of group sto-android (ticket release-5-1-0, STO-4): the first-lookup deletion policy of the
 * round-2 fix for m1 ([resolveExistingKey]) against the exception the platform really throws.
 *
 * Contract: plan A-4 and its mapping row ("`KeyPermanentlyInvalidatedException`: the dead key is deleted and a new one
 * generated; old entries `Undecryptable(key)`; writes succeed"), the KDoc of [AndroidSecureKeyValueStorage.ensureKey]
 * ("A key the platform has permanently invalidated is replaced here as it is in any other call, so this succeeds on a
 * store whose old values are lost") and the m1 rule that an unproven lookup failure deletes nothing.
 *
 * The platform shape. `KeyStore.getEntry` reaches `AndroidKeyStoreSpi.engineGetKey`. On API 30 (keystore 1,
 * `android.security.keystore`) and API 31+ (keystore 2, `android.security.keystore2`), the provider's lookup throws
 * `KeyPermanentlyInvalidatedException("User changed or deleted their auth credentials", cause)` when the daemon answers
 * KEY_PERMANENTLY_INVALIDATED, and `engineGetKey` catches it and throws `new UnrecoverableKeyException(e.getMessage())`:
 * the proof is reduced to its message, with NO cause. Read from the framework bytecode of the Robolectric android-all
 * jars for API 30 and 34 (`javap -c`). A transient or locked failure instead comes as
 * `UnrecoverableKeyException("Failed to obtain information about key")` with the daemon's `KeyStoreException` as cause.
 * [platformLookupFailure] builds exactly the first shape; [transientLookupFailure] the second (with
 * `java.security.KeyStoreException` standing in for `android.security.KeyStoreException`, whose constructor the host
 * jar hides).
 */
@RunWith(RobolectricTestRunner::class)
class AndroidKeyLookupBreakerR2Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var sequence: Int = 0

    @Before
    fun resetKeys() {
        TestKeys.reset()
    }

    private fun preferences(label: String): SharedPreferences =
        context.getSharedPreferences("breaker.r2.lookup.$label.${sequence++}.${System.identityHashCode(this)}", Context.MODE_PRIVATE)

    /** What `AndroidKeyStoreSpi.engineGetKey` throws for a key the daemon reports permanently invalidated. */
    private fun platformLookupFailure(): UnrecoverableKeyException {
        val proof = KeyPermanentlyInvalidatedException("User changed or deleted their auth credentials", null)
        return UnrecoverableKeyException(proof.message)
    }

    /** What the provider throws for a lookup the daemon refused for a reason that may pass. */
    private fun transientLookupFailure(): UnrecoverableKeyException =
        UnrecoverableKeyException("Failed to obtain information about key").also { it.initCause(KeyStoreException("system error")) }

    /**
     * A key store whose `existingKey` is the production policy [resolveExistingKey] over a fake `getEntry` that throws
     * [failure] while [failing], and a fake `deleteEntry`; counts every call.
     */
    private class PolicyKeyStore(initial: SecretKey, @Volatile var failing: Boolean, private val failure: () -> Exception) : AliasedKeyStore {
        @Volatile
        var entry: SecretKey? = initial
        val lookups: AtomicInteger = AtomicInteger()
        val deletions: AtomicInteger = AtomicInteger()
        val generations: AtomicInteger = AtomicInteger()

        override fun existingKey(alias: String, mayDelete: Boolean): SecretKey? = resolveExistingKey(
            mayDelete = mayDelete,
            lookup = {
                lookups.incrementAndGet()
                if (failing) throw failure()
                entry
            },
            delete = { deleteKey(alias) },
        )

        override fun generateKey(alias: String): SecretKey {
            generations.incrementAndGet()
            val fresh: SecretKey = TestKeys.generate()
            entry = fresh
            failing = false
            return fresh
        }

        override fun deleteKey(alias: String) {
            deletions.incrementAndGet()
            entry = null
            failing = false
        }
    }

    /** A preferences file holding one entry `old = "v"` written under [key], as a store of this version writes it. */
    private suspend fun preferencesWithOldEntry(label: String, key: SecretKey): SharedPreferences {
        val preferences: SharedPreferences = preferences(label)
        val writer = AndroidSecureKeyValueStorage(preferences, KeystoreValueCipher(TestKeys.fixedSource(key)), Dispatchers.Unconfined)
        assertEquals(StorageResult.Success(Unit), writer.put("old", "v"), "control: the old entry is written")
        assertEquals(StorageResult.Success("v"), writer.get("old"), "control: and reads back under its key")
        return preferences
    }

    private fun storeOver(keyStore: AliasedKeyStore, alias: String, preferences: SharedPreferences): AndroidSecureKeyValueStorage =
        AndroidSecureKeyValueStorage(preferences, KeystoreValueCipher(AndroidKeystoreKeySource(alias, keyStore)), Dispatchers.Default)

    // -- The platform's shape of the proof ---------------------------------------------------------------------------

    @Test
    fun `ensureKey replaces a key the platform reports permanently invalidated at the lookup, in the shape the platform throws`() = runTest {
        val old: SecretKey = TestKeys.generate()
        val preferences: SharedPreferences = preferencesWithOldEntry("ensure", old)
        val keyStore = PolicyKeyStore(old, failing = true) { platformLookupFailure() }
        val storage = storeOver(keyStore, "breaker.r2.lookup.ensure.${sequence++}", preferences)

        val result: StorageResult<Unit> = storage.ensureKey()

        assertEquals(
            StorageResult.Success(Unit),
            result,
            "a key the platform calls permanently invalidated must be replaced, not answer Unavailable until the app data " +
                "is cleared (plan A-4; ensureKey KDoc). The platform's getEntry carries the proof only as the message of a " +
                "cause-less UnrecoverableKeyException",
        )
        assertEquals(1, keyStore.deletions.get(), "the dead alias is deleted once")
        assertEquals(1, keyStore.generations.get(), "and one new key is generated")
        assertIs<StorageError.Undecryptable>(storage.get("old").errorOrNull(), "the old value is lost, and says so")
        assertEquals(StorageResult.Success(Unit), storage.put("new", "w"))
        assertEquals(StorageResult.Success("w"), storage.get("new"))
    }

    @Test
    fun `a storm of first uses over two instances on such a key deletes it once, generates once and every write succeeds`() = runTest(timeout = 60.seconds) {
        val seed = 2201
        val old: SecretKey = TestKeys.generate()
        val preferences: SharedPreferences = preferencesWithOldEntry("storm", old)
        val keyStore = PolicyKeyStore(old, failing = true) { platformLookupFailure() }
        val alias = "breaker.r2.lookup.storm.${sequence++}"
        val stores: List<AndroidSecureKeyValueStorage> = List(2) { storeOver(keyStore, alias, preferences) }
        val failures: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val random = Random(seed)
        val plan: List<Pair<Int, Int>> = List(16) { worker: Int -> worker to random.nextInt(3) }

        withContext(Dispatchers.Default) {
            withTimeout(30.seconds) {
                coroutineScope {
                    plan.map { (worker: Int, action: Int) ->
                        async {
                            val storage: AndroidSecureKeyValueStorage = stores[worker % 2]
                            val outcome: StorageResult<*> = when (action) {
                                0 -> storage.ensureKey()
                                1 -> storage.put("k$worker", "v$worker")
                                else -> storage.androidKeySecurityLevel()
                            }
                            if (outcome !is StorageResult.Success) failures += "worker $worker action $action: $outcome"
                        }
                    }.awaitAll()
                }
            }
        }

        assertEquals(emptyList(), failures.toList(), "seed=$seed: every first use must succeed once the dead key is replaced")
        assertEquals(1, keyStore.deletions.get(), "seed=$seed: one deletion")
        assertEquals(1, keyStore.generations.get(), "seed=$seed: one generation")
        for ((worker: Int, action: Int) in plan) {
            if (action == 1) assertEquals(StorageResult.Success("v$worker"), stores[1 - worker % 2].get("k$worker"), "seed=$seed worker $worker")
        }
    }

    // -- Controls: what must stay as the m1 fix made it --------------------------------------------------------------

    @Test
    fun `control - a lookup failure that may pass deletes nothing, answers Unavailable and the value reads back afterwards`() = runTest {
        val old: SecretKey = TestKeys.generate()
        val preferences: SharedPreferences = preferencesWithOldEntry("transient", old)
        val keyStore = PolicyKeyStore(old, failing = true) { transientLookupFailure() }
        val storage = storeOver(keyStore, "breaker.r2.lookup.transient.${sequence++}", preferences)

        assertIs<StorageError.Unavailable>(storage.ensureKey().errorOrNull())
        assertIs<StorageError.Unavailable>(storage.get("old").errorOrNull())
        assertIs<StorageError.Unavailable>(storage.put("x", "y").errorOrNull())
        assertEquals(0, keyStore.deletions.get(), "a fault that may pass never deletes the key")
        assertEquals(0, keyStore.generations.get())
        assertTrue(keyStore.lookups.get() >= 3, "control: the lookup really ran and failed, ${keyStore.lookups.get()} times")

        keyStore.failing = false

        assertEquals(StorageResult.Success("v"), storage.get("old"), "the value survives the fault")
    }

    @Test
    fun `control - a cause-less UnrecoverableKeyException that is not the platform's invalidation message deletes nothing`() = runTest {
        val old: SecretKey = TestKeys.generate()
        val preferences: SharedPreferences = preferencesWithOldEntry("other", old)
        val keyStore = PolicyKeyStore(old, failing = true) { UnrecoverableKeyException("no proof") }
        val storage = storeOver(keyStore, "breaker.r2.lookup.other.${sequence++}", preferences)

        assertIs<StorageError.Unavailable>(storage.ensureKey().errorOrNull())
        assertEquals(0, keyStore.deletions.get())
        keyStore.failing = false
        assertEquals(StorageResult.Success("v"), storage.get("old"))
    }
}
