package io.github.jamal_wia.kmptoolkit.storage

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.UserNotAuthenticatedException
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Breaker review round 2 of group sto-android (ticket release-5-1-0, STO-4, step 4e, D-28): the one-time envelope
 * migration (plan 4.9, CAS per entry, stamp last) on a store whose key is bound to user authentication, while the
 * validity window closes in the middle of the pass and a background token refresh from a second instance lands inside
 * the pass.
 *
 * Contract: plan 4.9 (the pass never loses a readable legacy entry, never overwrites a newer write, stamps only after
 * every legacy entry was rewritten), the [AndroidUserAuthentication] KDoc (outside the window a call that needs the key
 * is [StorageError.AccessDenied], the key is kept, nothing is deleted) and the class KDoc of
 * [AndroidSecureKeyValueStorage] (the lock order: an operation that finds a pass running does not wait for it).
 *
 * The window is a fake key handle: every use of its material counts as one step; at a seeded step the second instance
 * writes a new value for a seeded legacy entry (re-entrantly, from inside the pass), at another seeded step the window
 * closes. Deterministic: one thread, every interleaving is decided by the seed.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidUserAuthenticationBreakerR2Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var sequence: Int = 0

    @Before
    fun resetKeys() {
        TestKeys.reset()
    }

    private class Window {
        @Volatile
        var open: Boolean = true
        val uses: AtomicInteger = AtomicInteger()

        @Volatile
        var onUse: (Int) -> Unit = {}
    }

    /** The platform's key handle for a time-bound key: refuses with `UserNotAuthenticatedException` outside the window. */
    private class WindowKey(private val real: SecretKey, private val window: Window) : SecretKey {
        override fun getAlgorithm(): String = real.algorithm
        override fun getFormat(): String = real.format
        override fun getEncoded(): ByteArray {
            window.onUse(window.uses.incrementAndGet())
            if (!window.open) throw UserNotAuthenticatedException("authentication required")
            return real.encoded
        }
    }

    /** An existing time-bound key, never replaced: generating or deleting would be a defect here. */
    private class ExistingKeyStore(private val key: SecretKey) : AliasedKeyStore {
        override fun existingKey(alias: String, mayDelete: Boolean): SecretKey = key
        override fun generateKey(alias: String): SecretKey = error("a closed window must never generate a key")
        override fun generateKey(alias: String, options: AndroidSecureStorageOptions): SecretKey =
            error("a closed window must never generate a key")
        override fun deleteKey(alias: String): Unit = error("a closed window must never delete a key")
    }

    private val options: AndroidSecureStorageOptions =
        AndroidSecureStorageOptions(userAuthentication = AndroidUserAuthentication(validitySeconds = 30))

    /** The 4.0.0 writer (no associated data) or, with [boundTo], the 5.0.0 one. */
    private fun legacyEncrypt(material: SecretKey, plaintext: String, random: Random, boundTo: String?): String {
        val iv: ByteArray = random.nextBytes(12).also { it[0] = if (it[0] == 1.toByte()) 2 else it[0] }
        val jce: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
        jce.init(Cipher.ENCRYPT_MODE, material, GCMParameterSpec(128, iv))
        if (boundTo != null) jce.updateAAD("kmptoolkit-storage-key:$boundTo".toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + jce.doFinal(plaintext.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun isVersioned(stored: String): Boolean = Base64.decode(stored, Base64.NO_WRAP)[0] == KeystoreValueCipher.FORMAT_VERSION

    @Test
    fun `a window closing mid-pass with a refresh landing inside the pass loses nothing, overwrites nothing and stamps only when done`() = runTest {
        for (seed: Int in 0 until 40) {
            runScenario(seed)
        }
    }

    private suspend fun runScenario(seed: Int) {
        val random = Random(seed)
        val material: SecretKey = TestKeys.generate()
        val window = Window()
        val key = WindowKey(material, window)
        val alias = "breaker.r2.auth.$seed.${sequence++}.${System.identityHashCode(this)}"
        val preferences: SharedPreferences = context.getSharedPreferences(alias, Context.MODE_PRIVATE)
        val expected: MutableMap<String, String> = linkedMapOf()
        val entries: Int = 3 + random.nextInt(4)
        repeat(entries) { index: Int ->
            val name = "token$index"
            val plaintext = "legacy-$seed-$index"
            preferences.edit().putString(name, legacyEncrypt(material, plaintext, random, boundTo = name.takeIf { random.nextBoolean() })).commit()
            expected[name] = plaintext
        }
        val keyStore = ExistingKeyStore(key)
        fun store(): AndroidSecureKeyValueStorage =
            AndroidSecureKeyValueStorage(preferences, KeystoreValueCipher(AndroidKeystoreKeySource(alias, keyStore, options)), Dispatchers.Unconfined)
        val app: AndroidSecureKeyValueStorage = store()
        val refresher: AndroidSecureKeyValueStorage = store()

        val refreshAt: Int = 1 + random.nextInt(entries * 3)
        val closeAt: Int = 1 + random.nextInt(entries * 3)
        val refreshed: String = "token${random.nextInt(entries)}"
        var refreshOutcome: StorageResult<Unit>? = null
        window.onUse = { use: Int ->
            if (use == closeAt) window.open = false
            if (use == refreshAt && refreshOutcome == null) {
                refreshOutcome = StorageResult.Success(Unit) // marks the hook as fired before the re-entrant put uses the key
                refreshOutcome = runBlocking { refresher.put(refreshed, "refreshed-$seed") }
            }
        }

        val first: StorageResult<String?> = app.get("token0")
        val stampedAfterFirst: Boolean = preferences.contains(AndroidSecureKeyValueStorage.FORMAT_KEY)
        val legacyLeft: List<String> = expected.keys.filter { name: String -> !isVersioned(requireNotNull(preferences.getString(name, null))) }
        assertTrue(
            !stampedAfterFirst || legacyLeft.isEmpty(),
            "seed=$seed closeAt=$closeAt refreshAt=$refreshAt: stamped with legacy entries left: $legacyLeft",
        )
        assertTrue(
            first is StorageResult.Success || first.errorOrNull() is StorageError.AccessDenied,
            "seed=$seed: the first read is a value or AccessDenied, was $first",
        )
        val outcome: StorageResult<Unit>? = refreshOutcome
        if (outcome == StorageResult.Success(Unit)) expected[refreshed] = "refreshed-$seed"
        assertTrue(
            outcome == null || outcome == StorageResult.Success(Unit) || outcome.errorOrNull() is StorageError.AccessDenied,
            "seed=$seed: the refresh is written or AccessDenied, was $outcome",
        )

        // The user authenticates again; nothing else interferes.
        window.onUse = {}
        window.open = true
        for ((name: String, value: String) in expected) {
            assertEquals(StorageResult.Success(value), app.get(name), "seed=$seed closeAt=$closeAt refreshAt=$refreshAt: $name")
        }
        assertTrue(preferences.contains(AndroidSecureKeyValueStorage.FORMAT_KEY), "seed=$seed: the pass finished once the window opened")
        for (name: String in expected.keys) {
            assertTrue(isVersioned(requireNotNull(preferences.getString(name, null))), "seed=$seed: $name rewritten")
        }
    }
}
