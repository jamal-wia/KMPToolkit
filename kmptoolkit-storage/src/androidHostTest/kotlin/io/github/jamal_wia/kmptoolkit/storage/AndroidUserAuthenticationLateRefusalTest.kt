package io.github.jamal_wia.kmptoolkit.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import javax.crypto.SecretKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Group sto-android, step 4e: the platform may refuse an unauthenticated user at `doFinal` instead of at `Cipher.init`
 * (a `ProviderException` carrying the `UserNotAuthenticatedException`); the answer must be the same either way:
 * `AccessDenied` for a key bound to user authentication, `Unavailable` (a locked device) for one that is not, the key
 * kept, nothing written. Runs the production cipher over [LateAuthGcmProvider] (emulated, not observed on a device).
 */
@RunWith(RobolectricTestRunner::class)
class AndroidUserAuthenticationLateRefusalTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var sequence: Int = 0

    @Before
    fun install() {
        LateAuthGcmProvider.install()
    }

    @After
    fun uninstall() {
        LateAuthGcmProvider.uninstall()
    }

    private fun preferences(): SharedPreferences =
        context.getSharedPreferences("lateauth.${sequence++}.${System.identityHashCode(this)}", Context.MODE_PRIVATE)

    private fun storage(gate: AuthGate, authenticationBound: Boolean, preferences: SharedPreferences = preferences()): AndroidSecureKeyValueStorage {
        val key: SecretKey = LateAuthKey(TestKeys.generate(), gate)
        val source = object : SecretKeySource {
            override val userAuthenticationRequired: Boolean = authenticationBound
            override suspend fun key(): SecretKey = key
        }
        return AndroidSecureKeyValueStorage(preferences, KeystoreValueCipher(source), Dispatchers.Unconfined)
    }

    @Test
    fun `a refusal at doFinal is AccessDenied for a key bound to authentication, on write, read and ensureKey`() = runTest {
        val gate = AuthGate(open = true)
        val preferences: SharedPreferences = preferences()
        val storage = storage(gate, authenticationBound = true, preferences = preferences)
        assertEquals(StorageResult.Success(Unit), storage.put("k", "v"), "control: the provider is usable inside the window")

        gate.open = false
        for ((label: String, error: StorageError?) in listOf(
            "put" to storage.put("other", "x").errorOrNull(),
            "get" to storage.get("k").errorOrNull(),
            "ensureKey" to storage.ensureKey().errorOrNull(),
        )) {
            assertIs<StorageError.AccessDenied>(error, "$label was $error")
        }
        assertNull(preferences.getString("other", null), "a refused put stores nothing")

        gate.open = true
        assertEquals(StorageResult.Success<String?>("v"), storage.get("k"), "the entry survived the closed window")
    }

    @Test
    fun `the same refusal for a key not bound to authentication is a locked device, Unavailable`() = runTest {
        val gate = AuthGate(open = false)
        val storage = storage(gate, authenticationBound = false)

        assertIs<StorageError.Unavailable>(storage.put("k", "v").errorOrNull())
        assertIs<StorageError.Unavailable>(storage.ensureKey().errorOrNull())
    }

    @Test
    fun `the legacy reader maps a refusal the same way`() = runTest {
        val gate = AuthGate(open = false)
        val cipher: ValueCipher = run {
            val key: SecretKey = LateAuthKey(TestKeys.generate(), gate)
            KeystoreValueCipher(object : SecretKeySource {
                override val userAuthenticationRequired: Boolean = true
                override suspend fun key(): SecretKey = key
            })
        }
        val failure: Throwable? = runCatching { runBlocking { cipher.decryptLegacyFor("k", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA") } }.exceptionOrNull()

        assertIs<UserAuthenticationRequiredException>(failure, "was $failure")
    }
}
