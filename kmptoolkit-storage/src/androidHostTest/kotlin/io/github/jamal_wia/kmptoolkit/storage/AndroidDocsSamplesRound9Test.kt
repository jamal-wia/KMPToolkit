package io.github.jamal_wia.kmptoolkit.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * B round 9, Q3: docs/kmptoolkit-storage/02-getting-started.md followed line by line on Android: the onboarding sample
 * (step 3), the device id (step 4), the token flow with its exhaustive `when` over [StorageError] (step 5, built on a test
 * key source because the AndroidKeyStore does not exist on a host JVM), and two stores in one app (step 6), each across a
 * simulated restart (a second factory call over the same names).
 */
@RunWith(RobolectricTestRunner::class)
class AndroidDocsSamplesRound9Test {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun unique(prefix: String): String = "$prefix${UUID.randomUUID().toString().replace("-", "")}"

    // Step 3 of the document, verbatim apart from the key.
    private class OnboardingState(private val storage: KeyValueStorage) {
        suspend fun hasSeenOnboarding(): Boolean = storage.getStringOrNull(KEY) == "true"

        suspend fun markSeen() {
            storage.put(KEY, "true")
        }

        private companion object {
            const val KEY = "onboarding.seen"
        }
    }

    @Test
    fun `onboarding and the device id survive a restart and the id is one value under concurrent first calls`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val name: String = unique("docs")
            val storage: KeyValueStorage = createKeyValueStorage(context, StorageConfig(name))
            assertFalse(OnboardingState(storage).hasSeenOnboarding())
            OnboardingState(storage).markSeen()
            val ids: List<String> = (1..16).map { async(Dispatchers.Default) { DeviceIdProvider(storage).current() } }.awaitAll()
            assertEquals(1, ids.toSet().size, "concurrent first calls disagreed: $ids")
            // A restart: a new store object over the same name.
            val restarted: KeyValueStorage = createKeyValueStorage(context, StorageConfig(name))
            assertTrue(OnboardingState(restarted).hasSeenOnboarding())
            assertEquals(ids.first(), DeviceIdProvider(restarted).current())
            restarted.clear()
            assertFalse(OnboardingState(restarted).hasSeenOnboarding())
            assertNotEquals(ids.first(), DeviceIdProvider(restarted).current(), "clearing app data must reset the device id")
        }
    }

    @Test
    fun `two named stores never touch each other and a shared key name stays separate`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val session: KeyValueStorage = createKeyValueStorage(context, StorageConfig(unique("session")))
            val drafts: KeyValueStorage = createKeyValueStorage(context, StorageConfig(unique("drafts")))
            session.put("shared", "from-session")
            drafts.put("shared", "from-drafts")
            drafts.put("only-drafts", "d")
            drafts.clear()
            assertEquals("from-session", session.getStringOrNull("shared"))
            assertEquals(null, drafts.getStringOrNull("shared"))
            assertEquals(null, drafts.getStringOrNull("only-drafts"))
        }
    }

    /** Step 5: the document's `when`, run for an absent value, a stored value, a rotated key and an unavailable key store. */
    @Test
    fun `the token flow of the document handles absent stored undecryptable and unavailable in its own branches`() = runBlocking<Unit> {
        withTimeout(60_000) {
            TestKeys.reset()
            val name: String = unique("tokens")
            val oldKey: javax.crypto.SecretKey = TestKeys.generate()
            fun secure(source: SecretKeySource): SecureKeyValueStorage = AndroidSecureKeyValueStorage(
                preferences = context.getSharedPreferences(secureStoreId(name), Context.MODE_PRIVATE),
                cipher = KeystoreValueCipher(source),
                dispatcher = Dispatchers.IO,
            )

            fun branch(result: StorageResult<String?>): String = when (val stored = result) {
                is StorageResult.Success -> stored.value?.let { "refresh:$it" } ?: "signIn"
                is StorageResult.Failure -> when (stored.error) {
                    is StorageError.Undecryptable -> "signIn(undecryptable)"
                    is StorageError.Unavailable -> "retryLater"
                    is StorageError.OperationFailed -> "report"
                }
            }

            val secrets: SecureKeyValueStorage = secure(TestKeys.fixedSource(oldKey))
            assertEquals("signIn", branch(secrets.get("refresh_token")))
            secrets.put("refresh_token", "tok-1")
            assertEquals("refresh:tok-1", branch(secure(TestKeys.fixedSource(oldKey)).get("refresh_token")))
            assertEquals("signIn(undecryptable)", branch(secure(TestKeys.fixedSource(TestKeys.generate())).get("refresh_token")))
            assertEquals("retryLater", branch(secure(TestKeys.unavailableSource()).get("refresh_token")))
            // The way out the document names: a new put under the new key works for that key.
            val rotated: SecureKeyValueStorage = secure(TestKeys.fixedSource(TestKeys.generate()))
            assertTrue(rotated.put("refresh_token", "tok-2") is StorageResult.Success)
            assertEquals("refresh:tok-2", branch(rotated.get("refresh_token")))
        }
    }
}
