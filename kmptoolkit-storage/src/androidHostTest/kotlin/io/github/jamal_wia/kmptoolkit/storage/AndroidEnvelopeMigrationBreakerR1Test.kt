package io.github.jamal_wia.kmptoolkit.storage

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.IllegalBlockSizeException
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Breaker review round 1 of group sto-android (ticket release-5-1-0, STO-4): attacks on the A-2 version byte and the
 * A-1 closing migration with compare-and-set (plan 4.9, S19, test A5).
 *
 * Contract under attack (plan 4.9 and the KDoc of `AndroidSecureKeyValueStorage.migrate`): the pass is ONE-TIME; an
 * entry that fails its tag or is malformed is skipped (it was lost already); only a failure that may pass stops the
 * pass; after the pass an unversioned entry is `Undecryptable(key)`; a legacy entry that was readable before the pass
 * stays readable; a write or removal landing during the pass is never undone.
 *
 * [DeviceLikeGcmProvider] reproduces, beneath the production cipher, the one place where the host JCE and the AndroidKeyStore provider disagree:
 * for a GCM input shorter than the 16-byte tag the host provider throws `AEADBadTagException` (a
 * `BadPaddingException`), while `AndroidKeyStoreCipherSpiBase.engineDoFinal` maps the Keymaster error
 * (`KM_ERROR_INVALID_INPUT_LENGTH`) to `IllegalBlockSizeException`, which is not a `BadPaddingException`.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidEnvelopeMigrationBreakerR1Test {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var sequence: Int = 0

    @Before
    fun resetKeys() {
        TestKeys.reset()
        DeviceLikeGcmProvider.install()
    }

    @After
    fun removeProvider() {
        DeviceLikeGcmProvider.uninstall()
    }

    private fun uniqueName(): String = "breaker.r1.migration.${sequence++}.${System.identityHashCode(this)}"

    private fun preferences(name: String): SharedPreferences =
        context.getSharedPreferences(secureStoreId(name), Context.MODE_PRIVATE)

    private fun store(name: String, cipher: ValueCipher = KeystoreValueCipher(TestKeys.source(name))): AndroidSecureKeyValueStorage =
        AndroidSecureKeyValueStorage(preferences(name), cipher, Dispatchers.Unconfined)

    /** The 4.0.0 writer (no associated data) or, with [boundTo], the 5.0.0 one; [iv] fixed by the test. */
    private fun legacyEncrypt(name: String, plaintext: String, iv: ByteArray, boundTo: String? = null): String {
        val key: SecretKey = runBlocking { TestKeys.source(name).key() }
        val jce: Cipher = Cipher.getInstance("AES/GCM/NoPadding")
        jce.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        if (boundTo != null) jce.updateAAD("kmptoolkit-storage-key:$boundTo".toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + jce.doFinal(plaintext.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun randomIv(seed: Long): ByteArray = Random(seed).nextBytes(12).also { iv: ByteArray -> if (iv[0] == 1.toByte()) iv[0] = 2 }

    private fun stamped(name: String): Boolean = preferences(name).contains(AndroidSecureKeyValueStorage.FORMAT_KEY)

    private fun isVersioned(name: String, key: String): Boolean =
        Base64.decode(requireNotNull(preferences(name).getString(key, null)), Base64.NO_WRAP)[0] == KeystoreValueCipher.FORMAT_VERSION

    /**
     * The production cipher over [DeviceLikeGcmProvider]: the key the legacy entries were written with, handed out as a
     * [DeviceLikeKey], so every decryption runs through the device's answer to a GCM input shorter than its tag.
     */
    private fun deviceCipher(name: String): ValueCipher {
        val material: SecretKey = runBlocking { TestKeys.source(name).key() }
        return KeystoreValueCipher(TestKeys.fixedSource(DeviceLikeKey(material)))
    }

    /** Counts the legacy decryptions so a test can see how often the pass ran. */
    private class CountingCipher(private val inner: ValueCipher) : ValueCipher by inner {
        val legacyDecryptions: AtomicInteger = AtomicInteger()

        override suspend fun decryptLegacyFor(key: String, encoded: String): String {
            legacyDecryptions.incrementAndGet()
            return inner.decryptLegacyFor(key, encoded)
        }
    }

    @Test
    fun `control the device-like provider is the one the production cipher uses and it round trips`() = runTest {
        val name: String = uniqueName()
        val storage = store(name, deviceCipher(name))
        val before: Int = DeviceLikeGcmProvider.initialisations.get()
        assertEquals(StorageResult.Success(Unit), storage.put("k", "v"))
        assertEquals(StorageResult.Success<String?>("v"), storage.get("k"))
        assertTrue(DeviceLikeGcmProvider.initialisations.get() >= before + 2, "control: the production cipher ran on the device-like provider")
        val shortInput = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        shortInput.init(javax.crypto.Cipher.DECRYPT_MODE, DeviceLikeKey(TestKeys.generate()), GCMParameterSpec(128, ByteArray(12)))
        val failure: Throwable? = runCatching { shortInput.doFinal(ByteArray(15)) }.exceptionOrNull()
        assertTrue(failure is IllegalBlockSizeException, "control: a GCM input shorter than the tag fails as on a device, was $failure")
    }

    // -- Poison entries: the pass must stay one-time and close the fallback -------------------------------------

    @Test
    fun `a legacy empty value whose IV starts with the version byte stays readable on the device provider`() = runTest {
        val name: String = uniqueName()
        // 4.0.0 wrote "" as iv(12) + tag(16) = 28 bytes; one IV in 256 starts with 0x01, the version byte.
        val iv: ByteArray = randomIv(SEED).also { it[0] = 1 }
        preferences(name).edit()
            .putString("empty", legacyEncrypt(name, "", iv))
            .putString("other", legacyEncrypt(name, "kept", randomIv(SEED + 1)))
            .commit()
        val storage = store(name, deviceCipher(name))

        assertEquals(StorageResult.Success<String?>("kept"), storage.get("other"), "control: the legacy reader works")
        assertEquals(
            StorageResult.Success<String?>(""),
            storage.get("empty"),
            "a readable 4.0.0 entry must not be reported lost because its first IV byte equals the version byte",
        )
        assertTrue(stamped(name), "the pass finished")
        assertTrue(isVersioned(name, "empty"), "and rewrote the empty entry")
    }

    @Test
    fun `a truncated entry is skipped by the pass, which stamps once and closes the legacy fallback`() = runTest {
        val name: String = uniqueName()
        val versionedTruncated: ByteArray = byteArrayOf(KeystoreValueCipher.FORMAT_VERSION) + Random(SEED).nextBytes(12 + 9)
        val legacyTruncated: ByteArray = randomIv(SEED + 2) + Random(SEED + 3).nextBytes(7)
        preferences(name).edit()
            .putString("good", legacyEncrypt(name, "value", randomIv(SEED + 4)))
            .putString("cut.v1", Base64.encodeToString(versionedTruncated, Base64.NO_WRAP))
            .putString("cut.legacy", Base64.encodeToString(legacyTruncated, Base64.NO_WRAP))
            .commit()
        val cipher = CountingCipher(deviceCipher(name))
        val storage = store(name, cipher)

        assertEquals(StorageResult.Success<String?>("value"), storage.get("good"))
        for (key: String in listOf("cut.v1", "cut.legacy")) {
            assertTrue(storage.get(key).errorOrNull() is StorageError.Undecryptable, "control: $key is lost")
        }

        assertTrue(stamped(name), "a malformed entry must be skipped, not stop the one-time pass for ever")
        assertTrue(isVersioned(name, "good"))
        // The fallback is closed: an unversioned entry injected now is not readable.
        preferences(name).edit().putString("injected", legacyEncrypt(name, "moved", randomIv(SEED + 5))).commit()
        assertTrue(storage.get("injected").errorOrNull() is StorageError.Undecryptable, "the A-1 fallback is still open")

        val before: Int = cipher.legacyDecryptions.get()
        repeat(REPEATS) { assertEquals(StorageResult.Success<String?>("value"), storage.get("good")) }
        assertEquals(before, cipher.legacyDecryptions.get(), "the one-time pass ran again on later reads")
    }

    @Test
    fun `on the host provider the same truncated entries are skipped and the pass stamps`() = runTest {
        val name: String = uniqueName()
        preferences(name).edit()
            .putString("good", legacyEncrypt(name, "value", randomIv(SEED + 6)))
            .putString(
                "cut.v1",
                Base64.encodeToString(byteArrayOf(KeystoreValueCipher.FORMAT_VERSION) + Random(SEED).nextBytes(21), Base64.NO_WRAP),
            )
            .putString("cut.legacy", Base64.encodeToString(randomIv(SEED + 7) + Random(SEED).nextBytes(7), Base64.NO_WRAP))
            .putString("empty", legacyEncrypt(name, "", randomIv(SEED).also { it[0] = 1 }))
            .commit()

        assertEquals(StorageResult.Success<String?>("value"), store(name).get("good"))

        assertTrue(stamped(name), "control for the device-provider tests: the JCE answers a bad tag, which is skipped")
        assertEquals(StorageResult.Success<String?>(""), store(name).get("empty"), "control: on the JCE the 0x01-IV empty entry survives")
    }

    // -- Exceptions and cancellation at every step of the pass ---------------------------------------------------

    /** A cipher that runs [onDecrypt] before every legacy decryption and [onEncrypt] before every encryption. */
    private class SteppedCipher(
        private val inner: ValueCipher,
        var onDecrypt: suspend (String) -> Unit = {},
        var onEncrypt: suspend (String) -> Unit = {},
    ) : ValueCipher by inner {
        override suspend fun decryptLegacyFor(key: String, encoded: String): String {
            onDecrypt(key)
            return inner.decryptLegacyFor(key, encoded)
        }

        override suspend fun encryptFor(key: String, plaintext: String): String {
            onEncrypt(key)
            return inner.encryptFor(key, plaintext)
        }
    }

    private fun seedThree(name: String): Map<String, String> {
        val entries: Map<String, String> = linkedMapOf("a" to "1", "b" to "2", "c" to "3")
        val edit: SharedPreferences.Editor = preferences(name).edit()
        entries.entries.forEachIndexed { index: Int, (key: String, value: String) ->
            edit.putString(key, legacyEncrypt(name, value, randomIv(SEED + 10 + index), boundTo = if (index % 2 == 0) key else null))
        }
        assertTrue(edit.commit())
        return entries
    }

    @Test
    fun `an exception at any step of the pass leaves every entry whole and readable and the next operation finishes it`() = runTest {
        for (step: String in listOf("decrypt:a", "decrypt:b", "decrypt:c", "encrypt:a", "encrypt:b", "encrypt:c")) {
            val name: String = uniqueName()
            val entries: Map<String, String> = seedThree(name)
            val (phase: String, target: String) = step.split(":")
            val failing = SteppedCipher(KeystoreValueCipher(TestKeys.source(name)))
            val boom: suspend (String) -> Unit = { key: String -> if (key == target) throw IllegalStateException("keystore fault at $step") }
            if (phase == "decrypt") failing.onDecrypt = boom else failing.onEncrypt = boom

            // A put of another key triggers the pass; the put itself must still succeed.
            assertEquals(StorageResult.Success(Unit), store(name, failing).put("new", "n"), "step=$step")

            assertFalse(stamped(name), "step=$step: a pass that stopped never stamps")
            // Read through a cipher that can decrypt but not re-encrypt, so these reads cannot finish the pass.
            val readOnly = SteppedCipher(KeystoreValueCipher(TestKeys.source(name)), onEncrypt = { error("still unwell") })
            entries.forEach { (key: String, value: String) ->
                assertEquals(StorageResult.Success<String?>(value), store(name, readOnly).get(key), "step=$step entry $key")
            }
            assertFalse(stamped(name), "step=$step: control, the reads did not finish the pass")
            assertEquals(StorageResult.Success<String?>("n"), store(name).get("new"), "step=$step")
            assertTrue(stamped(name), "step=$step: the next operation finished the pass")
            (entries.keys + "new").forEach { key: String -> assertTrue(isVersioned(name, key), "step=$step $key versioned") }
        }
    }

    @Test
    fun `a pass cancelled while it decrypts an entry writes nothing for it, never stamps, and does not wedge the next pass`() = runTest {
        val name: String = uniqueName()
        val entries: Map<String, String> = seedThree(name)
        val entered: CompletableDeferred<Unit> = CompletableDeferred()
        val never: CompletableDeferred<Unit> = CompletableDeferred()
        val parked = SteppedCipher(KeystoreValueCipher(TestKeys.source(name)), onDecrypt = { key: String ->
            if (key == "b") {
                entered.complete(Unit)
                never.await()
            }
        })
        val job: Job = launch(UnconfinedTestDispatcher(testScheduler)) { store(name, parked).put("x", "y") }
        assertTrue(entered.isCompleted, "control: the pass is parked inside the decryption of b")
        val whileParked: Map<String, *> = preferences(name).all

        job.cancel()
        job.join()

        assertEquals(whileParked, preferences(name).all, "the cancellation wrote nothing")
        assertNull(preferences(name).getString("x", null), "the cancelled put stored nothing")
        assertFalse(stamped(name))
        withTimeout(HANG_BOUND_MS) {
            entries.forEach { (key: String, value: String) -> assertEquals(StorageResult.Success<String?>(value), store(name).get(key)) }
        }
        assertTrue(stamped(name), "the migration lock was released by the cancelled pass")
    }

    // -- Re-entrancy: calls into the store from inside the pass ---------------------------------------------------

    @Test
    fun `get, put, remove and clear called from inside the pass neither deadlock nor get undone`() = runTest {
        val name: String = uniqueName()
        seedThree(name)
        val other = store(name)
        var fired = 0
        val hooked = SteppedCipher(KeystoreValueCipher(TestKeys.source(name)), onEncrypt = { key: String ->
            if (key == "b" && fired++ == 0) {
                // Same instance and another instance, every operation, while this pass holds its migration lock.
                assertEquals(StorageResult.Success<String?>("3"), other.get("c"))
                assertEquals(StorageResult.Success(Unit), other.put("a", "A2"))
                assertEquals(StorageResult.Success(Unit), other.remove("c"))
            }
        })
        val migrating = store(name, hooked)

        withTimeout(HANG_BOUND_MS) { assertEquals(StorageResult.Success<String?>("2"), migrating.get("b")) }

        assertEquals(1, fired, "control: the calls ran inside the pass")
        assertEquals(StorageResult.Success<String?>("A2"), store(name).get("a"), "the put inside the pass was not undone")
        assertEquals(StorageResult.Success<String?>(null), store(name).get("c"), "the removal inside the pass was not undone")
        assertEquals(StorageResult.Success<String?>("2"), store(name).get("b"))
        assertTrue(stamped(name))
    }

    @Test
    fun `a clear landing in the middle of the pass leaves an empty stamped store and brings nothing back`() = runTest {
        val name: String = uniqueName()
        seedThree(name)
        val other = store(name)
        var fired = 0
        val hooked = SteppedCipher(KeystoreValueCipher(TestKeys.source(name)), onEncrypt = { key: String ->
            if (key == "a" && fired++ == 0) assertEquals(StorageResult.Success(Unit), other.clear())
        })

        val read: StorageResult<String?> = store(name, hooked).get("b")

        assertEquals(1, fired, "control: the clear ran inside the pass")
        assertEquals(StorageResult.Success<String?>("2"), read, "the read answers what it read before the clear")
        assertEquals(setOf(AndroidSecureKeyValueStorage.FORMAT_KEY), preferences(name).all.keys, "nothing came back after clear")
    }

    @Test
    fun `a pass over a store whose key died stamps it, old entries are lost, and writes work`() = runTest {
        // The legacy entries were written under a key that no longer exists: the pass must not loop or stall on them.
        val name: String = uniqueName()
        seedThree(name)
        TestKeys.reset() // the key behind the entries is gone; the source now hands out a new one
        val storage = store(name)

        assertTrue(storage.get("a").errorOrNull() is StorageError.Undecryptable)

        assertTrue(stamped(name))
        assertEquals(StorageResult.Success(Unit), storage.put("a", "fresh"))
        assertEquals(StorageResult.Success<String?>("fresh"), storage.get("a"))
        assertTrue(storage.get("b").errorOrNull() is StorageError.Undecryptable)
    }

    private companion object {
        const val SEED: Long = 31L
        const val REPEATS = 5
        const val HANG_BOUND_MS = 60_000L
    }
}
