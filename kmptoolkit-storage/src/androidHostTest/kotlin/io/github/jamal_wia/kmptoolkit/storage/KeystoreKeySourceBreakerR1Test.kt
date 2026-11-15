package io.github.jamal_wia.kmptoolkit.storage

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.UserNotAuthenticatedException
import androidx.test.core.app.ApplicationProvider
import java.security.InvalidKeyException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Breaker review round 1 of group sto-android (ticket release-5-1-0, STO-4): attacks on the Android key resolution of
 * `AndroidKeystoreKeySource` / `KeystoreValueCipher`: A-4 (a permanently invalidated key is deleted and replaced, in
 * every path), A-3 (one lock per alias, the process-wide cache kept), `unlockedDeviceRequired` (a locked device never
 * drops the key) and the full `ensureKey` (plan 4.9: resolves or creates the key and runs one cipher init).
 *
 * The fake key store behaves like the AndroidKeyStore in the one way the earlier fakes do not: every lookup hands out
 * a NEW handle object for the same entry, so identity comparisons in the source are attacked with handles that are
 * equal in meaning but not the same object.
 */
@RunWith(RobolectricTestRunner::class)
class KeystoreKeySourceBreakerR1Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var sequence: Int = 0

    @Before
    fun resetKeys() {
        TestKeys.reset()
    }

    private fun preferences(label: String): SharedPreferences =
        context.getSharedPreferences("breaker.r1.key.$label.${sequence++}.${System.identityHashCode(this)}", Context.MODE_PRIVATE)

    /** A handle to [real], a new object per lookup; refuses every use while [dead] or while [locked] says so. */
    private class Handle(private val real: SecretKey, private val dead: Boolean, private val locked: () -> Boolean) : SecretKey {
        override fun getAlgorithm(): String = "AES"
        override fun getFormat(): String = "RAW"
        override fun getEncoded(): ByteArray = when {
            dead -> throw KeyPermanentlyInvalidatedException()
            locked() -> throw UserNotAuthenticatedException("device locked")
            else -> real.encoded
        }
    }

    /**
     * Per alias: an entry that is permanently dead until deleted ([startDead]), a live key after a generation; a new
     * handle per lookup; thread-safe counters of every call.
     */
    private class PlatformLikeKeyStore(private val startDead: Boolean, private val deleteAllowed: Boolean = true) : AliasedKeyStore {
        private class Entry(val real: SecretKey, val dead: Boolean)

        private val entries: ConcurrentHashMap<String, Entry> = ConcurrentHashMap()
        private val initialised: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val lookups: AtomicInteger = AtomicInteger()
        val deletions: ConcurrentHashMap<String, AtomicInteger> = ConcurrentHashMap()
        val generations: ConcurrentHashMap<String, AtomicInteger> = ConcurrentHashMap()
        val locked: AtomicBoolean = AtomicBoolean(false)

        @Volatile
        var onDelete: () -> Unit = {}

        private fun entry(alias: String): Entry? {
            if (initialised.add(alias) && startDead) entries.putIfAbsent(alias, Entry(TestKeys.generate(), dead = true))
            return entries[alias]
        }

        override fun existingKey(alias: String, mayDelete: Boolean): SecretKey? {
            lookups.incrementAndGet()
            return entry(alias)?.let { found: Entry -> Handle(found.real, found.dead) { locked.get() } }
        }

        override fun generateKey(alias: String): SecretKey {
            generations.computeIfAbsent(alias) { AtomicInteger() }.incrementAndGet()
            val fresh = Entry(TestKeys.generate(), dead = false)
            entries[alias] = fresh
            return Handle(fresh.real, dead = false) { locked.get() }
        }

        override fun deleteKey(alias: String) {
            check(deleteAllowed) { "this scenario must never delete a key" }
            entry(alias)
            onDelete()
            deletions.computeIfAbsent(alias) { AtomicInteger() }.incrementAndGet()
            entries.remove(alias)
        }

        fun liveEncoded(alias: String): ByteArray? = entries[alias]?.takeIf { !it.dead }?.real?.encoded
        fun deletionsOf(alias: String): Int = deletions[alias]?.get() ?: 0
        fun generationsOf(alias: String): Int = generations[alias]?.get() ?: 0
    }

    private fun storeOver(keyStore: AliasedKeyStore, alias: String, preferences: SharedPreferences): AndroidSecureKeyValueStorage =
        AndroidSecureKeyValueStorage(preferences, KeystoreValueCipher(AndroidKeystoreKeySource(alias, keyStore)), Dispatchers.Default)

    // -- A-4 in every path -----------------------------------------------------------------------------------------

    @Test
    fun `every operation works on a store whose key died, and only the first key-using one replaces the key`() = runTest {
        val keyStore = PlatformLikeKeyStore(startDead = true)
        val alias = "breaker.every.path"
        val preferences: SharedPreferences = preferences("every")
        preferences.edit().putString("old", "AQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=").commit()
        val storage = storeOver(keyStore, alias, preferences)

        // remove and clear need no key: they must not touch the dead key at all.
        assertEquals(StorageResult.Success(Unit), storage.remove("absent"))
        assertEquals(0, keyStore.deletionsOf(alias), "remove touched the key")
        // ensureKey is the first call that needs the key: it repairs it and succeeds.
        assertEquals(StorageResult.Success(Unit), storage.ensureKey())
        assertEquals(1, keyStore.deletionsOf(alias), "ensureKey deleted the dead key")
        assertEquals(1, keyStore.generationsOf(alias), "and generated one new key")
        assertIs<StorageError.Undecryptable>(storage.get("old").errorOrNull())
        assertEquals(StorageResult.Success(Unit), storage.put("new", "v"))
        assertEquals(StorageResult.Success<String?>("v"), storage.get("new"))
        assertIs<StorageResult.Success<AndroidKeySecurityLevel>>(storage.androidKeySecurityLevel())
        assertEquals(StorageResult.Success(Unit), storage.clear())
        assertEquals(StorageResult.Success(Unit), storage.ensureKey())
        assertEquals(1, keyStore.deletionsOf(alias), "nothing deleted the new key")
        assertEquals(1, keyStore.generationsOf(alias))
    }

    @Test
    fun `a cancellation landing after the dead key was deleted and before the new one exists leaves a repairable store`() = runTest {
        val keyStore = PlatformLikeKeyStore(startDead = true)
        val alias = "breaker.cancel.mid.repair"
        val preferences: SharedPreferences = preferences("cancel")
        val storage = AndroidSecureKeyValueStorage(
            preferences,
            KeystoreValueCipher(AndroidKeystoreKeySource(alias, keyStore)),
            Dispatchers.Unconfined,
        )
        lateinit var job: Job
        keyStore.onDelete = { job.cancel() } // the caller is cancelled while the deletion runs under the alias lock
        job = launch(UnconfinedTestDispatcher(testScheduler), start = CoroutineStart.LAZY) { storage.put("k", "v") }

        job.start()
        job.join()
        keyStore.onDelete = {}

        assertTrue(job.isCancelled, "control: the put was cancelled mid-repair")
        assertEquals(1, keyStore.deletionsOf(alias), "control: the dead key was deleted before the cancellation surfaced")
        // A cancelled put either stored nothing or stored a whole, readable value (a commit can land before
        // withContext reports the cancellation, documented behaviour); never a value under a key that is gone.
        val afterCancel: StorageResult<String?> = storage.get("k")
        assertTrue(
            afterCancel == StorageResult.Success<String?>(null) || afterCancel == StorageResult.Success<String?>("v"),
            "after the cancelled put the entry read as $afterCancel",
        )
        assertEquals(StorageResult.Success(Unit), storage.put("k", "v2"))
        assertEquals(StorageResult.Success<String?>("v2"), storage.get("k"))
        assertEquals(1, keyStore.generationsOf(alias), "the next call generated exactly one key")
        assertEquals(1, keyStore.deletionsOf(alias), "and deleted nothing more")
    }

    @Test
    fun `a seeded storm of first uses over dead keys on several aliases replaces each key once and loses no write`() = runBlocking<Unit> {
        val seed: Long = System.getProperty("kmptoolkit.test.seed")?.toLongOrNull() ?: SEED
        val keyStore = PlatformLikeKeyStore(startDead = true)
        val aliases: List<String> = (0 until ALIASES).map { index: Int -> "breaker.storm.$index" }
        val files: List<SharedPreferences> = aliases.map { alias: String -> preferences(alias) }
        val problems: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val lastWritten: ConcurrentHashMap<String, String> = ConcurrentHashMap()

        withTimeout(HANG_BOUND_MS) {
            (0 until WORKERS).map { worker: Int ->
                async(Dispatchers.Default) {
                    val random = Random(seed * 1000 + worker)
                    repeat(STEPS) { step: Int ->
                        val index: Int = random.nextInt(ALIASES)
                        // A new store and source per call: instances race for the alias, as separate factory calls do.
                        val storage = storeOver(keyStore, aliases[index], files[index])
                        val own = "w$worker"
                        val slot = "$index/$own"
                        when (random.nextInt(4)) {
                            0 -> {
                                val value = "v$worker-$step"
                                val put: StorageResult<Unit> = storage.put(own, value)
                                if (put == StorageResult.Success(Unit)) lastWritten[slot] = value else problems += "seed=$seed w$worker s$step put $put"
                            }
                            1 -> {
                                val read: StorageResult<String?> = storage.get(own)
                                if (read != StorageResult.Success<String?>(lastWritten[slot])) problems += "seed=$seed w$worker s$step get $read, expected ${lastWritten[slot]}"
                            }
                            2 -> storage.ensureKey().let { result: StorageResult<Unit> ->
                                if (result != StorageResult.Success(Unit)) problems += "seed=$seed w$worker s$step ensureKey $result"
                            }
                            else -> storage.androidKeySecurityLevel().let { result: StorageResult<AndroidKeySecurityLevel> ->
                                if (result !is StorageResult.Success) problems += "seed=$seed w$worker s$step level $result"
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        assertEquals(emptyList(), problems.take(10), "seed=$seed: ${problems.size} problems")
        aliases.forEachIndexed { index: Int, alias: String ->
            // Only aliases a key-using call reached were repaired; each exactly once.
            val generated: Int = keyStore.generationsOf(alias)
            assertTrue(generated <= 1, "seed=$seed $alias generated $generated keys")
            assertEquals(generated, keyStore.deletionsOf(alias), "seed=$seed $alias deletions")
            if (generated == 1) {
                val cached: SecretKey = AndroidKeystoreKeySource(alias, keyStore).key()
                assertContentEquals(keyStore.liveEncoded(alias), cached.encoded, "seed=$seed $alias: the cached key is the live one")
                lastWritten.filterKeys { it.startsWith("$index/") }.forEach { (slot: String, value: String) ->
                    val read: StorageResult<String?> = storeOver(keyStore, alias, files[index]).get(slot.substringAfter('/'))
                    assertEquals(StorageResult.Success<String?>(value), read, "seed=$seed $slot after the storm")
                }
            }
        }
    }

    // -- unlockedDeviceRequired under concurrency ------------------------------------------------------------------

    @Test
    fun `a locked device under a storm of every operation never deletes or replaces the key, and the unlock restores everything`() = runBlocking<Unit> {
        val seed: Long = System.getProperty("kmptoolkit.test.seed")?.toLongOrNull() ?: SEED
        val keyStore = PlatformLikeKeyStore(startDead = false, deleteAllowed = false)
        val alias = "breaker.locked.storm"
        val preferences: SharedPreferences = preferences("locked")
        assertEquals(StorageResult.Success(Unit), storeOver(keyStore, alias, preferences).put("before", "kept"))
        keyStore.locked.set(true)
        val lookupsBefore: Int = keyStore.lookups.get()
        val problems: MutableList<String> = Collections.synchronizedList(mutableListOf())

        withTimeout(HANG_BOUND_MS) {
            (0 until WORKERS).map { worker: Int ->
                async(Dispatchers.Default) {
                    val random = Random(seed * 1000 + worker)
                    repeat(LOCKED_STEPS) { step: Int ->
                        val storage = storeOver(keyStore, alias, preferences)
                        val error: StorageError? = when (random.nextInt(3)) {
                            0 -> storage.get("before").errorOrNull()
                            1 -> storage.put("w$worker", "v").errorOrNull()
                            else -> storage.ensureKey().errorOrNull()
                        }
                        if (error !is StorageError.Unavailable) problems += "seed=$seed w$worker s$step: $error"
                    }
                }
            }.awaitAll()
        }

        assertEquals(emptyList(), problems.take(10), "seed=$seed: ${problems.size} problems")
        assertEquals(lookupsBefore, keyStore.lookups.get(), "seed=$seed: a locked device made the source look the key up again")
        assertEquals(1, keyStore.generationsOf(alias), "seed=$seed: no key was generated while locked")
        keyStore.locked.set(false)
        val storage = storeOver(keyStore, alias, preferences)
        assertEquals(StorageResult.Success<String?>("kept"), storage.get("before"), "seed=$seed")
        assertEquals(StorageResult.Success(Unit), storage.put("after", "v"), "seed=$seed")
        assertTrue(preferences.all.keys.none { key: String -> key.startsWith("w") }, "seed=$seed: a refused put stored something")
    }

    // -- ensureKey: one resolution, one init, concurrent with put ---------------------------------------------------

    @Test
    fun `concurrent ensureKey and put from many instances on a fresh alias generate one key and look it up once`() = runBlocking<Unit> {
        val keyStore = PlatformLikeKeyStore(startDead = false)
        val alias = "breaker.ensure.concurrent"
        val preferences: SharedPreferences = preferences("ensure")

        withTimeout(HANG_BOUND_MS) {
            (0 until WORKERS).map { worker: Int ->
                async(Dispatchers.Default) {
                    val storage = storeOver(keyStore, alias, preferences)
                    if (worker % 2 == 0) storage.ensureKey() else storage.put("w$worker", "v$worker")
                }
            }.awaitAll().forEach { result: StorageResult<Unit> -> assertEquals(StorageResult.Success(Unit), result) }
        }

        assertEquals(1, keyStore.generationsOf(alias), "exactly one key for the alias")
        assertEquals(1, keyStore.lookups.get(), "one key-store lookup served every instance")
        repeat(3) { assertEquals(StorageResult.Success(Unit), storeOver(keyStore, alias, preferences).ensureKey()) }
        assertEquals(1, keyStore.lookups.get(), "a later ensureKey on a resolved key does not go to the key store")
        (1 until WORKERS step 2).forEach { worker: Int ->
            assertEquals(StorageResult.Success<String?>("v$worker"), storeOver(keyStore, alias, preferences).get("w$worker"))
        }
    }

    // -- A non-permanent rejection racing resolution ----------------------------------------------------------------

    @Test
    fun `a one-off rejection of the cached handle racing other users re-resolves without deleting or regenerating`() = runBlocking<Unit> {
        val live: SecretKey = TestKeys.generate()
        val rejectedOnce = AtomicBoolean(false)
        val lookups = AtomicInteger()
        val keyStore = object : AliasedKeyStore {
            override fun existingKey(alias: String, mayDelete: Boolean): SecretKey {
                val number: Int = lookups.incrementAndGet()
                return object : SecretKey {
                    override fun getAlgorithm(): String = "AES"
                    override fun getFormat(): String = "RAW"
                    override fun getEncoded(): ByteArray {
                        // The first handle refuses one use with a rejection that is neither transient nor permanent.
                        if (number == 1 && rejectedOnce.compareAndSet(false, true)) throw InvalidKeyException("spurious rejection")
                        return live.encoded
                    }
                }
            }

            override fun generateKey(alias: String): SecretKey = error("an existing key is always answered")
            override fun deleteKey(alias: String): Unit = error("a non-permanent rejection never deletes")
        }
        val alias = "breaker.spurious"
        val preferences: SharedPreferences = preferences("spurious")

        withTimeout(HANG_BOUND_MS) {
            (0 until WORKERS).map { worker: Int ->
                async(Dispatchers.Default) { storeOver(keyStore, alias, preferences).put("w$worker", "v$worker") }
            }.awaitAll().forEachIndexed { worker: Int, result: StorageResult<Unit> ->
                assertEquals(StorageResult.Success(Unit), result, "worker $worker")
            }
        }

        assertTrue(rejectedOnce.get(), "control: the rejection happened")
        // 1 when a JCE provider fallback absorbed the one-off refusal, 2 when it reached the source; never more.
        assertTrue(lookups.get() <= 2, "the rejected handle was looked up again by more than one caller: ${lookups.get()}")
        (0 until WORKERS).forEach { worker: Int ->
            assertEquals(StorageResult.Success<String?>("v$worker"), storeOver(keyStore, alias, preferences).get("w$worker"))
        }
    }

    private companion object {
        const val SEED: Long = 11L
        const val ALIASES = 3
        const val WORKERS = 16
        const val STEPS = 80
        const val LOCKED_STEPS = 20
        const val HANG_BOUND_MS = 120_000L
    }
}
