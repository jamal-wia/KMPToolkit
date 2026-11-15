package io.github.jamal_wia.kmptoolkit.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
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
 * The versioned envelope and the one-time migration of the entries written before it (plan 4.9, test A5).
 *
 * The legacy entries are written here with the JCE directly, from the documented framing of 4.0.0 (no version byte, no
 * associated data) and 5.0.0 (no version byte, the key name as associated data): a writer that shares no code with the
 * one under test. The store under test runs over [TestKeys], see that file for what that leaves covered.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidEnvelopeMigrationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var sequence: Int = 0

    @Before
    fun resetKeys() {
        TestKeys.reset()
    }

    private fun uniqueName(): String = "migration.${sequence++}.${System.identityHashCode(this)}"

    private fun preferences(name: String): SharedPreferences =
        context.getSharedPreferences(secureStoreId(name), Context.MODE_PRIVATE)

    private fun store(name: String, cipher: ValueCipher = KeystoreValueCipher(TestKeys.source(name))): AndroidSecureKeyValueStorage =
        AndroidSecureKeyValueStorage(preferences(name), cipher, Dispatchers.Unconfined)

    /** The 4.0.0 writer, or with [boundTo] the 5.0.0 one. */
    private fun legacyEncrypt(name: String, plaintext: String, boundTo: String? = null, seed: Long = SEED): String {
        val key: SecretKey = runBlocking { TestKeys.source(name).key() }
        val iv: ByteArray = Random(seed + plaintext.hashCode()).nextBytes(12)
        val jce: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
        jce.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        if (boundTo != null) jce.updateAAD("kmptoolkit-storage-key:$boundTo".toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + jce.doFinal(plaintext.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun seedLegacy(name: String, entries: Map<String, String>, bound: Boolean) {
        val edit: SharedPreferences.Editor = preferences(name).edit()
        entries.forEach { (key: String, value: String) -> edit.putString(key, legacyEncrypt(name, value, boundTo = if (bound) key else null)) }
        assertTrue(edit.commit())
    }

    private fun isVersioned(name: String, key: String): Boolean {
        val raw: String = requireNotNull(preferences(name).getString(key, null)) { "no entry $key" }
        return Base64.decode(raw, Base64.NO_WRAP)[0] == KeystoreValueCipher.FORMAT_VERSION
    }

    private fun stamped(name: String): Boolean = preferences(name).contains(AndroidSecureKeyValueStorage.FORMAT_KEY)

    /** A cipher whose `encryptFor` first runs [hook], then encrypts; a seam between the pass's read and its write. */
    private class HookCipher(
        private val inner: ValueCipher,
        var hook: suspend (String) -> Unit = {},
    ) : ValueCipher by inner {
        override suspend fun encryptFor(key: String, plaintext: String): String {
            hook(key)
            return inner.encryptFor(key, plaintext)
        }
    }

    // -- Legacy reads before the pass (A-1, A-2) ----------------------------------------------------------------

    @Test
    fun `legacy entries of both older writers read correctly while the pass cannot finish, and the file stays unstamped`() = runTest {
        val name: String = uniqueName()
        seedLegacy(name, mapOf("unbound" to "4.0.0 value", "empty" to ""), bound = false)
        seedLegacy(name, mapOf("bound" to "5.0.0 value"), bound = true)
        // A pass that cannot re-encrypt stops without stamping: the key store is "unwell" for writes only.
        val storage = store(name, HookCipher(KeystoreValueCipher(TestKeys.source(name))) { throw IllegalStateException("not now") })

        assertEquals(StorageResult.Success<String?>("4.0.0 value"), storage.get("unbound"))
        assertEquals(StorageResult.Success<String?>(""), storage.get("empty"))
        assertEquals(StorageResult.Success<String?>("5.0.0 value"), storage.get("bound"))

        assertFalse(stamped(name), "a pass that did not finish never stamps the file")
        assertFalse(isVersioned(name, "unbound"))
    }

    @Test
    fun `the first read runs the pass, every entry becomes versioned and reads the same, and the file is stamped`() = runTest {
        val name: String = uniqueName()
        val entries: Map<String, String> = mapOf("a" to "1", "b" to "", "c" to "unicode 😀", "d" to "x".repeat(10_000))
        seedLegacy(name, entries.filterKeys { it in setOf("a", "b") }, bound = false)
        seedLegacy(name, entries.filterKeys { it in setOf("c", "d") }, bound = true)
        val storage = store(name)

        assertEquals(StorageResult.Success<String?>("1"), storage.get("a"))

        assertTrue(stamped(name))
        entries.forEach { (key: String, value: String) ->
            assertTrue(isVersioned(name, key), "entry $key was rewritten")
            assertEquals(StorageResult.Success<String?>(value), store(name).get(key), "entry $key after the pass")
        }
    }

    @Test
    fun `after the pass an unversioned entry is Undecryptable for its key, whichever writer made it`() = runTest {
        val name: String = uniqueName()
        seedLegacy(name, mapOf("old" to "v"), bound = true)
        val storage = store(name)
        assertEquals(StorageResult.Success<String?>("v"), storage.get("old"))
        assertTrue(stamped(name), "control: the pass ran")

        // Injected after the pass, from both older writers: the fallback is closed.
        preferences(name).edit()
            .putString("injected4", legacyEncrypt(name, "late 4.0.0"))
            .putString("injected5", legacyEncrypt(name, "late 5.0.0", boundTo = "injected5"))
            .commit()

        for (key: String in listOf("injected4", "injected5")) {
            val error: StorageError? = storage.get(key).errorOrNull()
            assertTrue(error is StorageError.Undecryptable && error.key == key, "$key was $error")
        }
    }

    @Test
    fun `a pass skips entries that were lost already, still stamps, and the next put replaces them`() = runTest {
        val name: String = uniqueName()
        seedLegacy(name, mapOf("good" to "kept"), bound = false)
        preferences(name).edit()
            .putString("junk", "not base64 !!").putString("short", Base64.encodeToString(ByteArray(5), Base64.NO_WRAP))
            .putString("foreign", legacyEncrypt("some.other.store", "other key")).putInt("int", 3).commit()
        val storage = store(name)

        assertEquals(StorageResult.Success<String?>("kept"), storage.get("good"))

        assertTrue(stamped(name))
        for (key: String in listOf("junk", "short", "foreign")) {
            assertTrue(storage.get(key).errorOrNull() is StorageError.Undecryptable, "$key stays lost")
        }
        assertEquals(3, preferences(name).getInt("int", 0), "an entry of another type is not touched")
        assertEquals(StorageResult.Success(Unit), storage.put("junk", "fresh"))
        assertEquals(StorageResult.Success<String?>("fresh"), storage.get("junk"))
    }

    @Test
    fun `a pass that meets an unavailable key store changes nothing and a later operation finishes it`() = runTest {
        val name: String = uniqueName()
        seedLegacy(name, mapOf("k" to "v"), bound = false)
        val before: Map<String, *> = preferences(name).all
        val down = KeystoreValueCipher(TestKeys.unavailableSource())

        assertEquals(StorageError.Unavailable::class, store(name, down).get("k").errorOrNull()!!::class)

        assertEquals(before, preferences(name).all, "nothing was written")
        assertFalse(stamped(name))
        assertEquals(StorageResult.Success<String?>("v"), store(name).get("k"))
        assertTrue(stamped(name))
        assertTrue(isVersioned(name, "k"))
    }

    // -- Compare-and-set (S19) ----------------------------------------------------------------------------------

    @Test
    fun `a write that lands between the pass's read and its re-encryption is never overwritten with the old value`() = runTest {
        val name: String = uniqueName()
        seedLegacy(name, mapOf("token" to "old-token", "other" to "other-value"), bound = true)
        val refresher = store(name) // a second instance over the same file, like a background token refresh
        val hooked = HookCipher(KeystoreValueCipher(TestKeys.source(name)))
        var fired = 0
        hooked.hook = { key: String ->
            if (key == "token" && fired++ == 0) assertEquals(StorageResult.Success(Unit), refresher.put("token", "refreshed-token"))
        }
        val migrating = store(name, hooked)

        assertEquals(StorageResult.Success<String?>("other-value"), migrating.get("other"))

        assertEquals(1, fired, "control: the refresh landed inside the pass, between the read and the write")
        assertEquals(StorageResult.Success<String?>("refreshed-token"), store(name).get("token"))
        assertEquals(StorageResult.Success<String?>("other-value"), store(name).get("other"))
        assertTrue(stamped(name), "the pass finished")
    }

    @Test
    fun `an entry removed during the pass is not brought back by it`() = runTest {
        val name: String = uniqueName()
        seedLegacy(name, mapOf("gone" to "v", "kept" to "w"), bound = false)
        val remover = store(name)
        val hooked = HookCipher(KeystoreValueCipher(TestKeys.source(name)))
        var fired = 0
        hooked.hook = { key: String -> if (key == "gone" && fired++ == 0) assertEquals(StorageResult.Success(Unit), remover.remove("gone")) }

        assertEquals(StorageResult.Success<String?>("w"), store(name, hooked).get("kept"))

        assertEquals(1, fired, "control: the removal landed inside the pass")
        assertNull(preferences(name).getString("gone", null))
        assertEquals(StorageResult.Success<String?>("w"), store(name).get("kept"))
    }

    // -- Cancellation and release -------------------------------------------------------------------------------

    @Test
    fun `a pass cancelled in the middle leaves every entry whole, unstamped, and the next operation finishes it`() = runTest {
        val name: String = uniqueName()
        seedLegacy(name, mapOf("a" to "1", "b" to "2", "c" to "3"), bound = false)
        val gate: CompletableDeferred<Unit> = CompletableDeferred()
        val entered: CompletableDeferred<Unit> = CompletableDeferred()
        val hooked = HookCipher(KeystoreValueCipher(TestKeys.source(name))) { key: String ->
            if (key == "b") {
                entered.complete(Unit)
                gate.await()
            }
        }
        val reader: Job = launch(UnconfinedTestDispatcher(testScheduler)) { store(name, hooked).get("a") }

        assertTrue(entered.isCompleted, "control: the pass reached its second entry")
        val whileParked: Map<String, *> = preferences(name).all
        reader.cancel()
        reader.join()

        assertTrue(reader.isCancelled)
        assertEquals(whileParked, preferences(name).all, "the cancellation itself wrote nothing")
        assertFalse(stamped(name), "a cancelled pass never stamps")
        listOf("a" to "1", "b" to "2", "c" to "3").forEach { (key: String, value: String) ->
            assertEquals(StorageResult.Success<String?>(value), store(name).get(key), "entry $key is whole")
        }
        assertTrue(stamped(name), "the next operation ran the pass again, and it did not wait on the cancelled one")
    }

    @Test
    fun `a put cancelled while its key is being resolved stores no entry and no stamp`() = runTest {
        val name: String = uniqueName()
        val gate: CompletableDeferred<Unit> = CompletableDeferred()
        val slowKey = object : SecretKeySource {
            override suspend fun key(): SecretKey {
                gate.await()
                return TestKeys.generate()
            }
        }
        val job: Job = launch(UnconfinedTestDispatcher(testScheduler)) { store(name, KeystoreValueCipher(slowKey)).put("k", "v") }

        job.cancel()
        job.join()

        assertTrue(job.isCancelled)
        assertEquals(emptyMap<String, Any?>(), preferences(name).all, "no half envelope, no stamp")
        assertEquals(StorageResult.Success(Unit), store(name).put("k", "v"))
    }

    // -- A fresh store, clear, and the reserved name --------------------------------------------------------------

    @Test
    fun `a fresh store is stamped by its first write, clear keeps the stamp, and a read alone writes nothing`() = runTest {
        val name: String = uniqueName()
        val storage = store(name)

        assertEquals(StorageResult.Success<String?>(null), storage.get("k"))
        assertEquals(StorageResult.Success(Unit), storage.ensureKey())
        assertEquals(emptyMap<String, Any?>(), preferences(name).all, "a read and ensureKey leave the file empty")

        assertEquals(StorageResult.Success(Unit), storage.put("k", "v"))
        assertTrue(stamped(name))
        assertTrue(isVersioned(name, "k"))

        assertEquals(StorageResult.Success(Unit), storage.clear())
        assertEquals(setOf(AndroidSecureKeyValueStorage.FORMAT_KEY), preferences(name).all.keys, "clear removes every entry and only entries")
        assertEquals(StorageResult.Success<String?>(null), storage.get("k"))
    }

    @Test
    fun `a put beside entries that may be legacy does not stamp the file before they are rewritten`() = runTest {
        val name: String = uniqueName()
        seedLegacy(name, mapOf("old" to "v"), bound = false)
        // The pass cannot finish, so the put must not close the legacy fallback for the entry it did not touch.
        val storage = store(name, HookCipher(KeystoreValueCipher(TestKeys.source(name))) { key: String -> if (key == "old") error("not now") })

        assertEquals(StorageResult.Success(Unit), storage.put("new", "w"))

        assertFalse(stamped(name))
        assertEquals(StorageResult.Success<String?>("v"), storage.get("old"))
    }

    @Test
    fun `the reserved format key is never an entry`() = runTest {
        val name: String = uniqueName()
        val storage = store(name)
        assertEquals(StorageResult.Success(Unit), storage.put("k", "v"))

        val error: StorageError? = storage.put(AndroidSecureKeyValueStorage.FORMAT_KEY, "v").errorOrNull()

        assertEquals(AndroidSecureKeyValueStorage.FORMAT_KEY, (error as? StorageError.InvalidValue)?.key, "was $error")
        assertEquals(StorageResult.Success<String?>(null), storage.get(AndroidSecureKeyValueStorage.FORMAT_KEY))
        assertEquals(StorageResult.Success(Unit), storage.remove(AndroidSecureKeyValueStorage.FORMAT_KEY))
        assertTrue(stamped(name), "the stamp survived every call on its name")
        assertEquals(StorageResult.Success<String?>("v"), storage.get("k"))
    }

    // -- Concurrency -------------------------------------------------------------------------------------------

    @Test
    fun `a seeded storm of readers and writers during the pass never sees a legacy entry lost or torn`() = runBlocking<Unit> {
        val seed: Long = System.getProperty("kmptoolkit.test.seed")?.toLongOrNull() ?: SEED
        val name: String = uniqueName()
        val legacy: Map<String, String> = (0 until LEGACY_ENTRIES).associate { index: Int -> "legacy-$index" to "value-$index-" + "x".repeat(index * 7) }
        seedLegacy(name, legacy, bound = false)
        val problems: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val lastWritten: java.util.concurrent.ConcurrentHashMap<String, String> = java.util.concurrent.ConcurrentHashMap()

        withTimeout(HANG_BOUND_MS) {
            (0 until WORKERS).map { worker: Int ->
                async(Dispatchers.Default) {
                    val random = Random(seed * 1000 + worker)
                    val storage = store(name)
                    repeat(STEPS) { step: Int ->
                        when (random.nextInt(3)) {
                            0 -> {
                                val key: String = "legacy-${random.nextInt(LEGACY_ENTRIES)}"
                                val read: StorageResult<String?> = storage.get(key)
                                if (read != StorageResult.Success<String?>(legacy.getValue(key))) problems += "worker $worker step $step: $key read as $read"
                            }
                            1 -> {
                                val key = "own-$worker"
                                val value = "w$worker-s$step"
                                val put: StorageResult<Unit> = storage.put(key, value)
                                if (put != StorageResult.Success(Unit)) problems += "worker $worker step $step: put gave $put" else lastWritten[key] = value
                            }
                            else -> {
                                val read: StorageResult<String?> = storage.get("own-$worker")
                                val expected: String? = lastWritten["own-$worker"]
                                if (read != StorageResult.Success<String?>(expected)) problems += "worker $worker step $step: own entry read as $read, expected $expected"
                            }
                        }
                    }
                }
            }.awaitAll()
        }

        assertEquals(emptyList(), problems.toList(), "seed=$seed")
        val after: AndroidSecureKeyValueStorage = store(name)
        legacy.forEach { (key: String, value: String) -> assertEquals(StorageResult.Success<String?>(value), after.get(key), "seed=$seed $key") }
        assertTrue(stamped(name), "seed=$seed: the pass finished")
        legacy.keys.forEach { key: String -> assertTrue(isVersioned(name, key), "seed=$seed $key is versioned") }
        assertNotNull(preferences(name).getString("own-0", null))
    }

    private companion object {
        const val SEED: Long = 5L
        const val LEGACY_ENTRIES = 6
        const val WORKERS = 12
        const val STEPS = 60
        const val HANG_BOUND_MS = 120_000L
    }
}
