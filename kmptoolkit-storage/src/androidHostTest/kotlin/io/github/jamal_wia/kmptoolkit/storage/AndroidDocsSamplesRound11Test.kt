package io.github.jamal_wia.kmptoolkit.storage

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Android snippets of docs/kmptoolkit-storage (plan 6.7): compiled here against the real factories and run where a host JVM can
 * run them. The text between the `sample:begin` and `sample:end` lines is the document's, kept equal by `DocsSamplesDriftTest`. The
 * AndroidKeyStore does not exist on a host JVM, so the secure store is built (its factory never touches the Keystore) and asked only what
 * needs no key: its protection and the typed answer of a key lookup that cannot happen. The `MyApplication` sample is compiled, not
 * instantiated: an Application that is not attached to a context has no application context to hand to the factory.
 * The shared samples (`docs-samples`) are the JVM module's; the AndroidDocsSamplesRound9Test covers the 5.x walkthrough.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidDocsSamplesRound11Test {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // timing-hygiene-ignore: TH3 name a unique store name per test, never a random choice of the scenario
    private fun unique(prefix: String): String = "$prefix${UUID.randomUUID().toString().replace("-", "")}"

    /** What an app does with the level (the documents' `report`). */
    private val levels: MutableList<AndroidKeySecurityLevel> = mutableListOf()

    private fun report(level: AndroidKeySecurityLevel) {
        levels.add(level)
    }

    // sample:begin android-create-store
    class MyApplication : Application() {
        lateinit var storage: KeyValueStorage

        override fun onCreate() {
            super.onCreate()
            storage = createKeyValueStorage(this)
        }
    }
    // sample:end android-create-store

    // sample:begin android-secure-store
    fun createSecrets(context: Context): SecureKeyValueStorage = createSecureKeyValueStorage(context)
    // sample:end android-secure-store

    // sample:begin android-two-stores
    fun createSessionAndDrafts(context: Context): Pair<KeyValueStorage, KeyValueStorage> = Pair(
        createKeyValueStorage(context, StorageConfig("com.example.session")),
        createKeyValueStorage(context, StorageConfig("com.example.drafts")),
    )
    // sample:end android-two-stores

    // sample:begin android-options
    suspend fun strongBoxSecrets(context: Context): SecureKeyValueStorage {
        val secrets: SecureKeyValueStorage = createSecureKeyValueStorage(
            context,
            StorageConfig("tokens"),
            AndroidSecureStorageOptions(
                keyBacking = AndroidKeyBacking.PREFER_STRONGBOX,
                unlockedDeviceRequired = true,
            ),
        )
        // Which hardware the key landed in; a store this factory did not create answers UNKNOWN.
        val level: StorageResult<AndroidKeySecurityLevel> = secrets.androidKeySecurityLevel()
        if (level is StorageResult.Success) report(level.value)
        return secrets
    }
    // sample:end android-options

    @Test
    fun `the application sample compiles against the factory`() {
        assertEquals("MyApplication", MyApplication::class.java.simpleName)
        assertTrue(Application::class.java.isAssignableFrom(MyApplication::class.java))
    }

    @Test
    fun `the secure sample builds a store that reports the Android protection without touching the Keystore`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val secrets: SecureKeyValueStorage = createSecrets(context)
            assertEquals(SecureStorageProtection.OS_KEY_STORE_APP_ISOLATED, secrets.protection)
        }
    }

    @Test
    fun `the options sample builds a store, asks for its security level and reports nothing when the key cannot be resolved`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val secrets: SecureKeyValueStorage = strongBoxSecrets(context)
            assertEquals(SecureStorageProtection.OS_KEY_STORE_APP_ISOLATED, secrets.protection)
            // A host JVM has no AndroidKeyStore: the lookup is a typed failure, so the sample reports no level.
            val level: StorageResult<AndroidKeySecurityLevel> = secrets.androidKeySecurityLevel()
            assertIs<StorageResult.Failure>(level)
            assertTrue(levels.isEmpty(), "the sample reported a level for a store whose key was never resolved: $levels")
        }
    }

    @Test
    fun `the two stores sample gives two independent stores that keep their own keys`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val one: String = unique("one")
            val (session: KeyValueStorage, drafts: KeyValueStorage) = createSessionAndDrafts(context)
            // The sample uses fixed names; a second pair over the same names is the "same store from another instance" of the guide.
            val (session2: KeyValueStorage, _) = createSessionAndDrafts(context)
            assertTrue(session.put(one, "s").isSuccess)
            assertTrue(drafts.put(one, "d").isSuccess)
            assertTrue(drafts.clear().isSuccess)
            assertEquals("s", session2.getStringOrNull(one))
            assertNull(drafts.getStringOrNull(one))
            assertTrue(session.remove(one).isSuccess)
        }
    }
}
