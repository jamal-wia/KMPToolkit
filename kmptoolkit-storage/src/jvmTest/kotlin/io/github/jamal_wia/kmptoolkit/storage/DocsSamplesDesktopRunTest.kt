package io.github.jamal_wia.kmptoolkit.storage

import docssamples.LaunchCounter
import docssamples.LegacySecretFile
import docssamples.LoggingSecureStorage
import docssamples.OnboardingState
import docssamples.PassphraseDialog
import docssamples.SampleLog
import docssamples.SecretsPlan
import docssamples.Session
import docssamples.appDataDirectory
import docssamples.createHeadlessStores
import docssamples.createVault
import docssamples.changePassphrase
import docssamples.decodeSession
import docssamples.deviceIdHeader
import docssamples.describe
import docssamples.encode
import docssamples.handle
import docssamples.migrateSecrets
import docssamples.planFor
import docssamples.react
import docssamples.requireAppIsolation
import docssamples.resumeSession
import docssamples.saveSession
import docssamples.storeAndRefresh
import docssamples.unlock
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.DesktopOs
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.OsKeyCache
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.SecureTestRig
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.StoreIdentity
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Plan 6.7 (b): the snippets of docs/kmptoolkit-storage that the `docs-samples` module compiles as a consumer, run here against
 * the real desktop stores above the internal fake binding of the OS key store (so no keychain, DPAPI or Secret Service is touched). The
 * samples are the files of `docs-samples/src/jvmMain/kotlin`, compiled into this module's jvmTest by its build file; each test names the
 * sample it runs and asserts the reaction the document promises, not what the implementation happens to do.
 *
 * Not run here: `openSecrets` (it asks the real operating system through `probeDesktopKeyStore()` and the real factory).
 */
class DocsSamplesDesktopRunTest {

    private val directory: File = Files.createTempDirectory("kmptoolkit-docs-samples").toFile()

    @BeforeTest
    fun clearLog() {
        SampleLog.clear()
    }

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun bounded(block: suspend () -> Unit): Unit = runBlocking { withTimeout(BOUND_MILLIS) { block() } }

    private fun dialog(passphrase: String): PassphraseDialog = PassphraseDialog { passphrase.toCharArray() }

    // ---- 02-getting-started: the plain store, the onboarding flag, the device id ----

    @Test
    fun `the onboarding sample, the counter and the device id header work over the plain desktop store and survive a restart`() = bounded {
        val config = StorageConfig("docs.onboarding")
        val storage: KeyValueStorage = createKeyValueStorage(directory, config)
        assertFalse(OnboardingState(storage).hasSeenOnboarding())
        assertTrue(OnboardingState(storage).markSeen())
        assertEquals(1, LaunchCounter(storage).increment())
        assertEquals(2, LaunchCounter(storage).increment())
        val (header: String, id: String) = deviceIdHeader(storage)
        assertEquals("X-Device-Id", header)

        val restarted: KeyValueStorage = createKeyValueStorage(directory, config)
        assertTrue(OnboardingState(restarted).hasSeenOnboarding())
        assertEquals(3, LaunchCounter(restarted).increment())
        assertEquals(id, deviceIdHeader(restarted).second, "the device id survives a restart")
        assertEquals(emptyList(), SampleLog.events, "no sample reacted to a failure on a healthy store")
    }

    @Test
    fun `the directory helper names the app inside the operating system's local application data`() {
        val dir: File = appDataDirectory("MyApp")
        assertEquals("MyApp", dir.name)
        assertTrue(dir.isAbsolute, "the helper must not return a relative path: $dir")
    }

    // ---- 01 and 02: reading a token from an OS-key store, per operating system's fake binding ----

    @Test
    fun `resumeSession resumes a stored token and signs in for an absent one, on every operating system`() = bounded {
        for (os in DesktopOs.entries) {
            SecureTestRig(os).use { rig: SecureTestRig ->
                SampleLog.clear()
                val secrets: SecureKeyValueStorage = rig.store()
                resumeSession(secrets)
                assertEquals(listOf("signIn"), SampleLog.events, "$os: an absent token signs in")

                assertTrue(secrets.put("refresh_token", "tok").isSuccess)
                SampleLog.clear()
                resumeSession(secrets)
                assertEquals(listOf("resume:tok"), SampleLog.events, "$os: a stored token resumes")
            }
        }
    }

    @Test
    fun `resumeSession reads a lost key as a sign in, never as a resume, and a missing session as an unsupported fallback`() = bounded {
        SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
            val secrets: SecureKeyValueStorage = rig.store()
            assertTrue(secrets.put("refresh_token", "tok").isSuccess)
            val identity: StoreIdentity = StoreIdentity.of(SecureTestRig.NAME, rig.directory)
            rig.cache.get(identity)?.let { rig.cache.invalidate(identity, it) }
            rig.fake.macItems.clear()
            val lost: SecureKeyValueStorage = rig.store(cache = OsKeyCache())
            SampleLog.clear()
            resumeSession(lost)
            assertEquals(listOf("signIn"), SampleLog.events, "a destroyed key: the stored session is gone for good")
        }
        SecureTestRig(DesktopOs.LINUX).use { rig: SecureTestRig ->
            rig.hasSessionBus = false
            val secrets: SecureKeyValueStorage = rig.store()
            // An absent store needs no key, so a read still answers "nothing there"; the first call that needs the key says why it cannot.
            SampleLog.clear()
            resumeSession(secrets)
            assertEquals(listOf("signIn"), SampleLog.events)
            val ensured: StorageResult<Unit> = secrets.ensureKey()
            assertEquals(StorageError.Unsupported(UnsupportedReason.NO_USER_SESSION), assertIs<StorageResult.Failure>(ensured).error)
            SampleLog.clear()
            handle(secrets, assertIs<StorageResult.Failure>(ensured))
            assertEquals(listOf("fallBackExplicitly:${UnsupportedReason.NO_USER_SESSION}"), SampleLog.events)
        }
    }

    @Test
    fun `storeAndRefresh writes the token then reads it back`() = bounded {
        SecureTestRig(DesktopOs.WINDOWS).use { rig: SecureTestRig ->
            val secrets: SecureKeyValueStorage = rig.store()
            storeAndRefresh(secrets, "t1")
            assertEquals(listOf("refresh:t1"), SampleLog.events)
            assertEquals(StorageResult.Success("t1"), secrets.get("refresh_token"))
        }
    }

    // ---- 03: the error table, one reaction per case ----

    @Test
    fun `handle and react give each of the six errors exactly its documented reaction`() = bounded {
        SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
            val storage: SecureKeyValueStorage = rig.store()
            val cause = IllegalStateException("c")
            val expected: List<Pair<StorageError, String>> = listOf(
                StorageError.Unavailable(cause) to "scheduleRetry:c",
                StorageError.AccessDenied() to "askAgainOnUserAction",
                StorageError.Unsupported(UnsupportedReason.NO_KEY_STORE) to "fallBackExplicitly:NO_KEY_STORE",
                StorageError.Undecryptable(null) to "storeLost",
                StorageError.OperationFailed(StorageOperation.GET) to "report:OperationFailed",
                StorageError.InvalidValue("k") to "dropValue:k",
            )
            for ((error: StorageError, first: String) in expected) {
                SampleLog.clear()
                handle(storage, StorageResult.Failure(error))
                assertEquals(first, SampleLog.events.first(), "handle(${error::class.simpleName})")
            }
            // A named key is removed from the store, a null key is the whole store.
            assertTrue(storage.put("k", "v").isSuccess)
            SampleLog.clear()
            handle(storage, StorageResult.Failure(StorageError.Undecryptable("k")))
            assertEquals(listOf("reacquire"), SampleLog.events)
            assertEquals(StorageResult.Success(null), storage.get("k"), "Undecryptable(key) removes that key")

            SampleLog.clear()
            react(storage, StorageResult.Failure(StorageError.Undecryptable("k")))
            react(storage, StorageResult.Failure(StorageError.AccessDenied()))
            assertEquals(listOf("askAgainOnUserAction"), SampleLog.events)
        }
    }

    @Test
    fun `describe takes an else for the operations it does not name`() {
        assertEquals("read", describe(StorageOperation.GET))
        assertEquals("key management, or an operation added later", describe(StorageOperation.CHANGE_KEY))
    }

    @Test
    fun `a decorator that delegates provides protection and ensureKey by itself`() = bounded {
        SecureTestRig(DesktopOs.LINUX).use { rig: SecureTestRig ->
            val inner: SecureKeyValueStorage = rig.store()
            val logging: SecureKeyValueStorage = LoggingSecureStorage(inner)
            assertEquals(SecureStorageProtection.OS_KEY_STORE_USER_SCOPED, logging.protection)
            assertTrue(logging.ensureKey().isSuccess)
            assertTrue(logging.put("k", "v").isSuccess)
            assertEquals(listOf("log:put k"), SampleLog.events)
            assertEquals(StorageResult.Success("v"), inner.get("k"))
        }
    }

    @Test
    fun `requireAppIsolation refuses a store that other programs of the user can open`() = bounded {
        SecureTestRig(DesktopOs.WINDOWS).use { rig: SecureTestRig ->
            val store: SecureKeyValueStorage = rig.store()
            assertFailsWith<IllegalArgumentException> { requireAppIsolation(store) }
        }
    }

    // ---- 03: one serialized record ----

    @Test
    fun `a session saved as one value reads back whole and a malformed value decodes to null`() = bounded {
        val vault: AppKeyEncryptedKeyValueStorage = createVault(directory, dialog("correct horse battery staple"))
        val session = Session("access", "refresh", "user-1")
        assertTrue(saveSession(vault, session).isSuccess)
        val stored: String? = assertIs<StorageResult.Success<String?>>(vault.get("session")).value
        assertEquals(session, decodeSession(assertNotNullValue(stored)))
        assertEquals(session, decodeSession(session.encode()))
        assertNull(decodeSession("only\ttwo"))
    }

    private fun assertNotNullValue(value: String?): String = value ?: error("expected a stored session")

    // ---- 03 recipe: migrating from an app-encrypted file ----

    private class FakeLegacyFile(initial: Map<String, String>) : LegacySecretFile {
        val values: MutableMap<String, String> = initial.toMutableMap()
        val reads: MutableList<String> = mutableListOf()
        var failErase: Boolean = false
        var keyMaterialErased: Boolean = false

        override suspend fun read(key: String): String? {
            reads.add(key)
            return values[key]
        }

        override suspend fun erase(key: String): Boolean {
            if (failErase) return false
            values.remove(key)
            return true
        }

        override suspend fun eraseKeyMaterial(): Boolean {
            keyMaterialErased = true
            return true
        }
    }

    @Test
    fun `the migration copies each record, erases the old copies last, and a rerun changes nothing`() = bounded {
        SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
            val target: SecureKeyValueStorage = rig.store()
            val old = FakeLegacyFile(mapOf("a" to "1", "b" to "2"))

            assertTrue(migrateSecrets(old, target, listOf("a", "b")))
            assertEquals(StorageResult.Success("1"), target.get("a"))
            assertEquals(StorageResult.Success("2"), target.get("b"))
            assertTrue(old.values.isEmpty(), "every old copy is erased")
            assertTrue(old.keyMaterialErased, "the old key material goes once nothing it protected is left")

            old.reads.clear()
            assertTrue(migrateSecrets(old, target, listOf("a", "b")), "a rerun is safe")
            assertEquals(emptyList(), old.reads, "a record already migrated is never read from the old file again")
        }
    }

    @Test
    fun `a value already in the new store is the truth and an old copy never overwrites it`() = bounded {
        SecureTestRig(DesktopOs.LINUX).use { rig: SecureTestRig ->
            val target: SecureKeyValueStorage = rig.store()
            assertTrue(target.put("a", "newer").isSuccess)
            val old = FakeLegacyFile(mapOf("a" to "older"))

            assertTrue(migrateSecrets(old, target, listOf("a")))

            assertEquals(StorageResult.Success("newer"), target.get("a"))
            assertEquals(emptyList(), old.reads, "the old copy is erased without being read")
            assertTrue(old.values.isEmpty())
        }
    }

    @Test
    fun `a failed erase keeps the old key material and the next run finishes the job`() = bounded {
        SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
            val target: SecureKeyValueStorage = rig.store()
            val old = FakeLegacyFile(mapOf("a" to "1"))
            old.failErase = true

            assertFalse(migrateSecrets(old, target, listOf("a")), "an old copy that could not be erased is not complete")
            assertFalse(old.keyMaterialErased, "the old key stays while a ciphertext it protected is still there")
            assertEquals(StorageResult.Success("1"), target.get("a"))

            old.failErase = false
            assertTrue(migrateSecrets(old, target, listOf("a")))
            assertTrue(old.keyMaterialErased)
            assertTrue(old.values.isEmpty())
        }
    }

    /** A target whose read-back differs from what was written, as a broken store would. */
    private class LyingStore(private val inner: SecureKeyValueStorage) : EncryptedKeyValueStorage by inner {
        override suspend fun get(key: String): StorageResult<String?> = when (val result: StorageResult<String?> = inner.get(key)) {
            is StorageResult.Success -> if (result.value == null) result else StorageResult.Success("something else")
            is StorageResult.Failure -> result
        }
    }

    @Test
    fun `a copy that does not read back equal is removed again and the old copy stays the only truth`() = bounded {
        SecureTestRig(DesktopOs.WINDOWS).use { rig: SecureTestRig ->
            val inner: SecureKeyValueStorage = rig.store()
            val old = FakeLegacyFile(mapOf("a" to "1"))

            assertFalse(migrateSecrets(old, LyingStore(inner), listOf("a")))

            assertEquals(StorageResult.Success(null), inner.get("a"), "the unverified copy is removed")
            assertEquals(mapOf("a" to "1"), old.values, "the old copy is still there")
            assertFalse(old.keyMaterialErased)
        }
    }

    // ---- 03 recipes: passphrase store, probe plan, headless ----

    @Test
    fun `the passphrase vault opens on the right passphrase, asks again on a wrong one and retries later when the dialog fails`() = bounded {
        assertTrue(unlock(createVault(directory, dialog("correct horse battery staple"))), "the first unlock creates the vault")
        assertEquals(emptyList(), SampleLog.events)

        assertFalse(unlock(createVault(directory, dialog("wrong"))))
        assertEquals(listOf("showUnlockDialogAgain"), SampleLog.events, "a wrong passphrase is AccessDenied: ask again, on a user action")

        SampleLog.clear()
        val failing = PassphraseDialog { throw IllegalStateException("the dialog was closed") }
        assertFalse(unlock(createVault(directory, failing)))
        assertEquals(listOf("retryLater"), SampleLog.events, "a provider that throws is Unavailable: try later")

        assertTrue(unlock(createVault(directory, dialog("correct horse battery staple"))), "the vault is still intact after a wrong try")
    }

    @Test
    fun `changePassphrase re-keys the vault, the old passphrase is refused and the new one opens it`() = bounded {
        val vault: AppKeyEncryptedKeyValueStorage = createVault(directory, dialog("old passphrase"))
        assertTrue(vault.put("k", "v").isSuccess)

        assertTrue(changePassphrase(vault, dialog("new passphrase")).isSuccess)

        assertIs<StorageError.AccessDenied>(assertIs<StorageResult.Failure>(createVault(directory, dialog("old passphrase")).get("k")).error)
        assertEquals(StorageResult.Success("v"), createVault(directory, dialog("new passphrase")).get("k"))
    }

    @Test
    fun `planFor gives every probe answer a plan`() {
        assertEquals(SecretsPlan.OsKeyStore, planFor(StorageResult.Success(DesktopKeyStore.WINDOWS_DPAPI)))
        fun plan(error: StorageError): SecretsPlan = planFor(StorageResult.Failure(error))
        assertEquals(SecretsPlan.FixRuntime, plan(StorageError.Unsupported(UnsupportedReason.UNSUPPORTED_RUNTIME)))
        assertEquals(SecretsPlan.PassphraseStore, plan(StorageError.Unsupported(UnsupportedReason.NO_KEY_STORE)))
        assertEquals(SecretsPlan.PassphraseStore, plan(StorageError.Unsupported(UnsupportedReason.NO_USER_SESSION)))
        assertEquals(SecretsPlan.InMemoryOnly, plan(StorageError.Unsupported(UnsupportedReason.NATIVE_ACCESS_UNAVAILABLE)))
        assertEquals(SecretsPlan.TryLater, plan(StorageError.Unavailable()))
        assertEquals(SecretsPlan.InMemoryOnly, plan(StorageError.OperationFailed(StorageOperation.ENSURE_KEY)))
    }

    @Test
    fun `the headless raw-key store works with a 32 byte key file and refuses any other length`() = bounded {
        val keyFile: File = File(directory, "agent.key")
        keyFile.writeBytes(ByteArray(32) { it.toByte() })
        val (osStore: SecureKeyValueStorage, rawStore: AppKeyEncryptedKeyValueStorage) = createHeadlessStores(directory, keyFile)
        assertEquals(SecureStorageProtection.OS_KEY_STORE_USER_SCOPED, osStore.protection)
        assertEquals(SecureStorageProtection.APP_PROVIDED_KEY, rawStore.protection)
        assertTrue(rawStore.put("k", "v").isSuccess)
        assertEquals(StorageResult.Success("v"), rawStore.get("k"))

        // The provider is asked on first use only: this instance keeps the key it derived from the header. A new instance asks again.
        keyFile.writeBytes(ByteArray(31))
        assertIs<StorageError.AccessDenied>(assertIs<StorageResult.Failure>(createHeadlessStores(directory, keyFile).second.get("k")).error)
    }

    private companion object {
        const val BOUND_MILLIS: Long = 120_000
    }
}
