package io.github.jamal_wia.kmptoolkit.storage

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.security.keystore.UserNotAuthenticatedException
import androidx.test.core.app.ApplicationProvider
import java.security.ProviderException
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Android key options, their error mappings and `androidKeySecurityLevel()` (plan 3.2 and 4.9, tests A4).
 *
 * The Keystore cannot be driven from a host test, so the key generation policy is tested through its `make` seam
 * ([generateWithBacking]) and the rest over a fake [AliasedKeyStore]; what only a device can show (a real StrongBox,
 * a locked emulator) is "not observed on a device".
 */
@RunWith(RobolectricTestRunner::class)
class AndroidSecureStorageOptionsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var sequence: Int = 0

    @Before
    fun resetKeys() {
        TestKeys.reset()
    }

    private fun preferences(): SharedPreferences =
        context.getSharedPreferences("options.${sequence++}.${System.identityHashCode(this)}", Context.MODE_PRIVATE)

    // -- The options class --------------------------------------------------------------------------------------

    @Test
    fun `the defaults ask for nothing special and the class is a plain class`() {
        val options = AndroidSecureStorageOptions()

        assertEquals(AndroidKeyBacking.DEFAULT, options.keyBacking)
        assertFalse(options.unlockedDeviceRequired)
        assertTrue(AndroidSecureStorageOptions::class.java.methods.none { it.name == "copy" || it.name.startsWith("component") })
        assertEquals(
            "AndroidSecureStorageOptions(keyBacking=REQUIRE_STRONGBOX, unlockedDeviceRequired=true, userAuthentication=null)",
            AndroidSecureStorageOptions(AndroidKeyBacking.REQUIRE_STRONGBOX, unlockedDeviceRequired = true).toString(),
        )
    }

    // -- The StrongBox policy (seam test, A4) -------------------------------------------------------------------

    private class MakeRecorder(private val failStrongBoxWith: Throwable? = null) {
        val calls: MutableList<Boolean> = mutableListOf()

        fun make(alias: String, strongBox: Boolean): SecretKey {
            calls += strongBox
            if (strongBox && failStrongBoxWith != null) throw failStrongBoxWith
            return TestKeys.generate()
        }
    }

    private val noStrongBox = StrongBoxUnavailableException("no secure element")

    @Test
    fun `DEFAULT never asks for a StrongBox`() {
        val recorder = MakeRecorder()

        generateWithBacking("a", AndroidKeyBacking.DEFAULT, sdkInt = 35) { alias, strongBox -> recorder.make(alias, strongBox) }

        assertEquals(listOf(false), recorder.calls)
    }

    @Test
    fun `PREFER_STRONGBOX falls back to the default key when the device has no StrongBox`() {
        val recorder = MakeRecorder(failStrongBoxWith = ProviderException("wrapped", noStrongBox))

        val key: SecretKey = generateWithBacking("a", AndroidKeyBacking.PREFER_STRONGBOX, sdkInt = 35) { alias, strongBox -> recorder.make(alias, strongBox) }

        assertEquals(listOf(true, false), recorder.calls, "StrongBox was tried first, then the default")
        assertEquals("AES", key.algorithm)
    }

    @Test
    fun `PREFER_STRONGBOX keeps the StrongBox key when there is one and rethrows any other failure`() {
        val ok = MakeRecorder()
        generateWithBacking("a", AndroidKeyBacking.PREFER_STRONGBOX, sdkInt = 35) { alias, strongBox -> ok.make(alias, strongBox) }
        assertEquals(listOf(true), ok.calls, "one generation, in the StrongBox")

        val broken = MakeRecorder(failStrongBoxWith = IllegalStateException("keystore daemon died"))
        assertFailsWith<IllegalStateException> {
            generateWithBacking("a", AndroidKeyBacking.PREFER_STRONGBOX, sdkInt = 35) { alias, strongBox -> broken.make(alias, strongBox) }
        }
        assertEquals(listOf(true), broken.calls, "a failure that is not 'no StrongBox' is never answered with a weaker key")
    }

    @Test
    fun `PREFER_STRONGBOX below API 28 generates the default key without asking`() {
        val recorder = MakeRecorder()

        generateWithBacking("a", AndroidKeyBacking.PREFER_STRONGBOX, sdkInt = 27) { alias, strongBox -> recorder.make(alias, strongBox) }

        assertEquals(listOf(false), recorder.calls)
    }

    @Test
    fun `REQUIRE_STRONGBOX without a StrongBox generates nothing and reports the hardware as unavailable`() {
        val recorder = MakeRecorder(failStrongBoxWith = noStrongBox)

        val failure: KeyHardwareUnavailableException = assertFailsWith<KeyHardwareUnavailableException> {
            generateWithBacking("a", AndroidKeyBacking.REQUIRE_STRONGBOX, sdkInt = 35) { alias, strongBox -> recorder.make(alias, strongBox) }
        }

        assertSame(noStrongBox, failure.cause)
        assertEquals(listOf(true), recorder.calls, "never a default key behind the caller's back")
        val old = MakeRecorder()
        assertFailsWith<KeyHardwareUnavailableException> {
            generateWithBacking("a", AndroidKeyBacking.REQUIRE_STRONGBOX, sdkInt = 27) { alias, strongBox -> old.make(alias, strongBox) }
        }
        assertEquals(emptyList(), old.calls, "below API 28 there is nothing to ask")
        val broken = MakeRecorder(failStrongBoxWith = IllegalStateException("keystore daemon died"))
        assertFailsWith<IllegalStateException> {
            generateWithBacking("a", AndroidKeyBacking.REQUIRE_STRONGBOX, sdkInt = 35) { alias, strongBox -> broken.make(alias, strongBox) }
        }
    }

    @Test
    fun `REQUIRE_STRONGBOX with a StrongBox generates the key in it`() {
        val recorder = MakeRecorder()

        generateWithBacking("a", AndroidKeyBacking.REQUIRE_STRONGBOX, sdkInt = 35) { alias, strongBox -> recorder.make(alias, strongBox) }

        assertEquals(listOf(true), recorder.calls)
    }

    @Test
    fun `the StrongBox classifier names the exception in a cause chain and nothing below API 28`() {
        assertTrue(isStrongBoxUnavailableFailure(noStrongBox, sdkInt = 35))
        assertTrue(isStrongBoxUnavailableFailure(ProviderException("wrapped", noStrongBox), sdkInt = 35))
        assertFalse(isStrongBoxUnavailableFailure(ProviderException("something else"), sdkInt = 35))
        assertFalse(isStrongBoxUnavailableFailure(noStrongBox, sdkInt = 27), "the class does not exist there")
    }

    // -- The error mappings of plan 4.9, through the store ------------------------------------------------------

    /** A key store that records the options it was asked to generate with, and can lack the hardware. */
    private class OptionsKeyStore(var hardwareMissing: Boolean, var level: AndroidKeySecurityLevel = AndroidKeySecurityLevel.UNKNOWN) : AliasedKeyStore {
        val optionsSeen: MutableList<AndroidSecureStorageOptions> = mutableListOf()
        var generated: Int = 0
        private var entry: SecretKey? = null

        override fun existingKey(alias: String, mayDelete: Boolean): SecretKey? = entry
        override fun deleteKey(alias: String): Unit = error("not expected")
        override fun generateKey(alias: String): SecretKey = error("the options overload is the one called")

        override fun generateKey(alias: String, options: AndroidSecureStorageOptions): SecretKey {
            optionsSeen += options
            if (hardwareMissing) throw KeyHardwareUnavailableException(StrongBoxUnavailableException("none"))
            generated++
            return TestKeys.generate().also { entry = it }
        }

        override fun securityLevel(key: SecretKey): AndroidKeySecurityLevel = level
    }

    private fun storeOver(keyStore: AliasedKeyStore, options: AndroidSecureStorageOptions, alias: String, preferences: SharedPreferences = preferences()) =
        AndroidSecureKeyValueStorage(
            preferences,
            KeystoreValueCipher(AndroidKeystoreKeySource(alias, keyStore, options)),
            Dispatchers.Unconfined,
        )

    @Test
    fun `REQUIRE_STRONGBOX without a StrongBox answers Unsupported HARDWARE_UNAVAILABLE on every call that needs the key`() = runTest {
        val keyStore = OptionsKeyStore(hardwareMissing = true)
        val preferences: SharedPreferences = preferences()
        val readable = storeOver(OptionsKeyStore(hardwareMissing = false), AndroidSecureStorageOptions(), "opt.seed", preferences)
        assertEquals(StorageResult.Success(Unit), readable.put("existing", "v"))
        val storage = storeOver(keyStore, AndroidSecureStorageOptions(AndroidKeyBacking.REQUIRE_STRONGBOX), "opt.required", preferences)

        for ((label: String, error: StorageError?) in listOf(
            "put" to storage.put("k", "v").errorOrNull(),
            "get of an existing entry" to storage.get("existing").errorOrNull(),
            "ensureKey" to storage.ensureKey().errorOrNull(),
            "androidKeySecurityLevel" to storage.androidKeySecurityLevel().errorOrNull(),
        )) {
            assertEquals(UnsupportedReason.HARDWARE_UNAVAILABLE, (error as? StorageError.Unsupported)?.reason, "$label was $error")
        }
        assertEquals(0, keyStore.generated, "no key was generated")
        assertNull(preferences.getString("k", null), "a refused put stores nothing")
        assertEquals(AndroidKeyBacking.REQUIRE_STRONGBOX, keyStore.optionsSeen.first().keyBacking, "the options reached the key store")
        // A device that gains the hardware (a retry on another day) is not stuck: nothing was cached.
        keyStore.hardwareMissing = false
        assertEquals(StorageResult.Success(Unit), storage.ensureKey())
        assertEquals(1, keyStore.generated)
    }

    @Test
    fun `the options object the factory was given is the one the key store sees`() = runTest {
        val keyStore = OptionsKeyStore(hardwareMissing = false)
        val options = AndroidSecureStorageOptions(AndroidKeyBacking.PREFER_STRONGBOX, unlockedDeviceRequired = true)

        assertEquals(StorageResult.Success(Unit), storeOver(keyStore, options, "opt.forwarded").put("k", "v"))

        assertSame(options, keyStore.optionsSeen.single())
    }

    /** A handle that a locked device refuses: the key needs the device unlocked. */
    private class LockedKey(private val real: SecretKey) : SecretKey {
        @Volatile
        var locked: Boolean = true

        override fun getAlgorithm(): String = real.algorithm
        override fun getFormat(): String = real.format
        override fun getEncoded(): ByteArray =
            if (locked) throw UserNotAuthenticatedException("device locked") else real.encoded
    }

    private class LockableKeyStore(val key: LockedKey) : AliasedKeyStore {
        var lookups: Int = 0
        override fun existingKey(alias: String, mayDelete: Boolean): SecretKey {
            lookups++
            return key
        }

        override fun deleteKey(alias: String): Unit = error("a locked device never deletes a key")
        override fun generateKey(alias: String): SecretKey = error("an existing key is always answered")
    }

    @Test
    fun `a locked device answers Unavailable, keeps the key, and the next call after the unlock succeeds`() = runTest {
        val key = LockedKey(TestKeys.generate())
        val keyStore = LockableKeyStore(key)
        val preferences: SharedPreferences = preferences()
        key.locked = false
        val storage = storeOver(keyStore, AndroidSecureStorageOptions(unlockedDeviceRequired = true), "opt.locked", preferences)
        assertEquals(StorageResult.Success(Unit), storage.put("k", "before the lock"))
        key.locked = true

        for ((label: String, error: StorageError?) in listOf(
            "get" to storage.get("k").errorOrNull(),
            "put" to storage.put("k2", "v").errorOrNull(),
            "ensureKey" to storage.ensureKey().errorOrNull(),
        )) {
            assertIs<StorageError.Unavailable>(error, "$label while locked was $error")
        }
        assertEquals(1, keyStore.lookups, "the key was never dropped and looked up again against a locked device")
        assertNull(preferences.getString("k2", null), "a refused put stores nothing")

        key.locked = false
        assertEquals(StorageResult.Success<String?>("before the lock"), storage.get("k"))
        assertEquals(StorageResult.Success(Unit), storage.put("k2", "after"))
    }

    // -- androidKeySecurityLevel() ------------------------------------------------------------------------------

    @Test
    fun `the security level is what the key store reports for the resolved key`() = runTest {
        for (level: AndroidKeySecurityLevel in AndroidKeySecurityLevel.entries) {
            val storage = storeOver(OptionsKeyStore(hardwareMissing = false, level = level), AndroidSecureStorageOptions(), "opt.level.$level")

            assertEquals(StorageResult.Success(level), storage.androidKeySecurityLevel())
        }
    }

    @Test
    fun `a store this factory did not create answers UNKNOWN, not a failure`() = runTest {
        val foreign = object : SecureKeyValueStorage {
            override val protection: SecureStorageProtection = SecureStorageProtection.NONE
            override suspend fun ensureKey(): StorageResult<Unit> = StorageResult.Success(Unit)
            override suspend fun get(key: String): StorageResult<String?> = StorageResult.Success(null)
            override suspend fun put(key: String, value: String): StorageResult<Unit> = StorageResult.Success(Unit)
            override suspend fun remove(key: String): StorageResult<Unit> = StorageResult.Success(Unit)
            override suspend fun clear(): StorageResult<Unit> = StorageResult.Success(Unit)
        }

        assertEquals(StorageResult.Success(AndroidKeySecurityLevel.UNKNOWN), foreign.androidKeySecurityLevel())
    }

    @Test
    fun `the security level of a store whose key store will not open is Unavailable`() = runTest {
        val noKeyStore = object : SecretKeySource {
            override suspend fun key(): SecretKey = throw StorageKeyUnavailableException(IllegalStateException("no key store"))
            override suspend fun securityLevel(): AndroidKeySecurityLevel = key().let { AndroidKeySecurityLevel.UNKNOWN }
        }
        val storage = AndroidSecureKeyValueStorage(preferences(), KeystoreValueCipher(noKeyStore), Dispatchers.Unconfined)

        assertIs<StorageError.Unavailable>(storage.androidKeySecurityLevel().errorOrNull())
    }

    @Test
    fun `the platform's level constants map one to one, an unknown one to UNKNOWN`() {
        assertEquals(AndroidKeySecurityLevel.SOFTWARE, securityLevelOf(KeyProperties.SECURITY_LEVEL_SOFTWARE))
        assertEquals(AndroidKeySecurityLevel.TRUSTED_ENVIRONMENT, securityLevelOf(KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT))
        assertEquals(AndroidKeySecurityLevel.STRONGBOX, securityLevelOf(KeyProperties.SECURITY_LEVEL_STRONGBOX))
        assertEquals(AndroidKeySecurityLevel.UNKNOWN, securityLevelOf(KeyProperties.SECURITY_LEVEL_UNKNOWN))
        assertEquals(AndroidKeySecurityLevel.UNKNOWN, securityLevelOf(12345))
    }

    @Test
    fun `below API 31 only secure hardware is known, so a hardware key is UNKNOWN and a software key SOFTWARE`() {
        val neverAsked: () -> Int = { error("the level does not exist below API 31") }

        assertEquals(AndroidKeySecurityLevel.UNKNOWN, securityLevelOf(30, neverAsked) { true })
        assertEquals(AndroidKeySecurityLevel.SOFTWARE, securityLevelOf(30, neverAsked) { false })
        assertEquals(AndroidKeySecurityLevel.STRONGBOX, securityLevelOf(31, { KeyProperties.SECURITY_LEVEL_STRONGBOX }) { error("not asked from API 31") })
    }

    // -- Cancellation must not be swallowed ---------------------------------------------------------------------

    @Test
    fun `a cancellation thrown by the key source while the caller is not cancelled propagates out of the level and ensureKey`() = runTest {
        val cancelling = object : SecretKeySource {
            override suspend fun key(): SecretKey = throw CancellationException("source cancelled")
            override suspend fun securityLevel(): AndroidKeySecurityLevel = throw CancellationException("source cancelled")
        }
        val storage = AndroidSecureKeyValueStorage(preferences(), KeystoreValueCipher(cancelling), Dispatchers.Unconfined)

        assertFailsWith<CancellationException> { storage.androidKeySecurityLevel() }
        assertFailsWith<CancellationException> { storage.ensureKey() }
    }
}
