package io.github.jamal_wia.kmptoolkit.storage

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import androidx.test.core.app.ApplicationProvider
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Group sto-android, step 4e (plan 3.2 "AndroidUserAuthentication", 4.9, S16, decisions D-11 and D-28): the Android
 * user-authentication option, its parameters, its error mapping (outside the validity window the call is
 * [StorageError.AccessDenied] and the key is kept), its interplay with key invalidation by an enrolment change, and its
 * lifecycle under cancellation and a seeded storm.
 *
 * The platform cannot be driven from a host test. The validity window is a fake [AuthKey]: its `getEncoded` is
 * refused with `UserNotAuthenticatedException` while the shared [User] is not authenticated, which reaches the production
 * cipher through the real `Cipher.init` path and arrives wrapped, as on a device; an enrolment change is the same
 * handle refusing with `KeyPermanentlyInvalidatedException`. Not observed on a device: the real window, the real
 * invalidation by enrolment and the prompt flow.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidUserAuthenticationTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private var sequence: Int = 0

    @Before
    fun resetKeys() {
        TestKeys.reset()
    }

    private fun preferences(): SharedPreferences =
        context.getSharedPreferences("userauth.${sequence++}.${System.identityHashCode(this)}", Context.MODE_PRIVATE)

    /** The user's authentication state, shared by every key: the window is the user's, not a key's. */
    private class User(authenticated: Boolean = false) {
        @Volatile
        var authenticated: Boolean = authenticated
    }

    /** A handle the platform refuses without an authenticated user, and for ever once [dead] (an enrolment change). */
    private class AuthKey(private val real: SecretKey, private val user: User) : SecretKey {
        @Volatile
        var dead: Boolean = false

        override fun getAlgorithm(): String = real.algorithm
        override fun getFormat(): String = real.format
        override fun getEncoded(): ByteArray = when {
            dead -> throw KeyPermanentlyInvalidatedException("biometric enrolment changed")
            !user.authenticated -> throw UserNotAuthenticatedException("authentication required")
            else -> real.encoded
        }
    }

    /** A key store that generates [AuthKey]s, counting everything, and can behave as a device without a lock screen. */
    private class AuthKeyStore(val user: User, private val hold: CountDownLatch? = null, private val entered: CountDownLatch? = null) :
        AliasedKeyStore {
        @Volatile
        var entry: AuthKey? = null

        @Volatile
        var lockScreenSet: Boolean = true

        val lookups: AtomicInteger = AtomicInteger()
        val generated: AtomicInteger = AtomicInteger()
        val deleted: AtomicInteger = AtomicInteger()
        val optionsSeen: MutableList<AndroidSecureStorageOptions> = Collections.synchronizedList(mutableListOf())
        private val firstLookup: AtomicInteger = AtomicInteger()

        override fun existingKey(alias: String, mayDelete: Boolean): SecretKey? {
            lookups.incrementAndGet()
            if (hold != null && firstLookup.getAndIncrement() == 0) {
                entered?.countDown()
                check(hold.await(30, TimeUnit.SECONDS)) { "the test never released the held lookup" }
            }
            return entry
        }

        override fun generateKey(alias: String): SecretKey = error("the options overload is the one the source calls")

        override fun generateKey(alias: String, options: AndroidSecureStorageOptions): SecretKey {
            optionsSeen += options
            // What the platform says for a key that needs authentication on a device without a secure lock screen.
            if (!lockScreenSet) throw IllegalStateException("Secure lock screen must be enabled to create keys requiring user authentication")
            val key = AuthKey(TestKeys.generate(), user)
            entry = key
            generated.incrementAndGet()
            return key
        }

        override fun deleteKey(alias: String) {
            deleted.incrementAndGet()
            entry = null
        }
    }

    private val authenticated: AndroidSecureStorageOptions =
        AndroidSecureStorageOptions(userAuthentication = AndroidUserAuthentication(validitySeconds = 30))

    private fun storeOver(
        keyStore: AliasedKeyStore,
        options: AndroidSecureStorageOptions = authenticated,
        preferences: SharedPreferences = preferences(),
        dispatcher: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Unconfined,
        alias: String = "userauth.alias.${sequence++}",
    ): AndroidSecureKeyValueStorage =
        AndroidSecureKeyValueStorage(preferences, KeystoreValueCipher(AndroidKeystoreKeySource(alias, keyStore, options)), dispatcher)

    // -- The option classes -------------------------------------------------------------------------------------

    @Test
    fun `the options default to no user authentication and the new class is validated`() {
        assertNull(AndroidSecureStorageOptions().userAuthentication)
        val auth = AndroidUserAuthentication(validitySeconds = 1)
        assertTrue(auth.allowDeviceCredential, "the device credential is allowed unless refused")
        assertEquals(1, auth.validitySeconds)
        assertEquals(Int.MAX_VALUE, AndroidUserAuthentication(Int.MAX_VALUE).validitySeconds)
        for (bad: Int in listOf(0, -1, Int.MIN_VALUE)) {
            assertFailsWith<IllegalArgumentException>("validitySeconds=$bad") { AndroidUserAuthentication(bad) }
        }
        assertEquals("AndroidUserAuthentication(validitySeconds=30, allowDeviceCredential=false)", AndroidUserAuthentication(30, false).toString())
        assertEquals(
            "AndroidSecureStorageOptions(keyBacking=DEFAULT, unlockedDeviceRequired=false, userAuthentication=null)",
            AndroidSecureStorageOptions().toString(),
        )
    }

    @Test
    fun `the platform parameters name the authenticators from API 30 and refuse a biometric-only request below it`() {
        val credential = AndroidUserAuthentication(45, allowDeviceCredential = true)
        val biometric = AndroidUserAuthentication(45, allowDeviceCredential = false)
        val both: Int = KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL

        for (sdk: Int in listOf(24, 28, 29)) {
            val spec: UserAuthenticationSpec = userAuthenticationSpecOf(sdk, credential)
            assertEquals(45, spec.validitySeconds, "sdk=$sdk")
            assertNull(spec.authenticators, "below API 30 the platform has only the window, sdk=$sdk")
            assertFailsWith<KeyHardwareUnavailableException>("a biometric-only request cannot be honoured at sdk=$sdk") {
                userAuthenticationSpecOf(sdk, biometric)
            }
        }
        for (sdk: Int in listOf(30, 31, 34, 35)) {
            assertEquals(both, userAuthenticationSpecOf(sdk, credential).authenticators, "sdk=$sdk")
            assertEquals(KeyProperties.AUTH_BIOMETRIC_STRONG, userAuthenticationSpecOf(sdk, biometric).authenticators, "sdk=$sdk")
            assertEquals(45, userAuthenticationSpecOf(sdk, biometric).validitySeconds)
        }
    }

    @Test
    fun `the options object the factory was given reaches the key store at generation`() = runTest {
        val keyStore = AuthKeyStore(User(authenticated = true))

        assertEquals(StorageResult.Success(Unit), storeOver(keyStore).ensureKey())

        assertSame(authenticated, keyStore.optionsSeen.single())
        assertNotNull(keyStore.optionsSeen.single().userAuthentication)
    }

    // -- The window: AccessDenied outside it, the key kept -----------------------------------------------------

    @Test
    fun `outside the window every call that needs the key is AccessDenied, the key is kept, and the next call inside it succeeds`() = runTest {
        val user = User(authenticated = true)
        val keyStore = AuthKeyStore(user)
        val preferences: SharedPreferences = preferences()
        val storage = storeOver(keyStore, preferences = preferences)
        assertEquals(StorageResult.Success(Unit), storage.put("token", "before the window closed"))

        user.authenticated = false
        for ((label: String, error: StorageError?) in listOf(
            "get" to storage.get("token").errorOrNull(),
            "put" to storage.put("other", "v").errorOrNull(),
            "ensureKey" to storage.ensureKey().errorOrNull(),
        )) {
            assertIs<StorageError.AccessDenied>(error, "$label outside the window was $error")
            assertIs<UserNotAuthenticatedException>(error.cause, "$label keeps the platform's reason as the cause: ${error.cause}")
        }
        assertEquals(1, keyStore.generated.get(), "no new key")
        assertEquals(0, keyStore.deleted.get(), "an unauthenticated user never deletes the key")
        assertEquals(1, keyStore.lookups.get(), "and never drops the cached key to look it up again")
        assertNull(preferences.getString("other", null), "a refused put stores nothing")

        user.authenticated = true
        assertEquals(StorageResult.Success<String?>("before the window closed"), storage.get("token"))
        assertEquals(StorageResult.Success(Unit), storage.put("other", "after"))
        assertEquals(StorageResult.Success<String?>("after"), storage.get("other"))
        assertEquals(1, keyStore.generated.get())
    }

    @Test
    fun `the calls that use no key answer without authentication`() = runTest {
        val user = User(authenticated = true)
        val keyStore = AuthKeyStore(user)
        val storage = storeOver(keyStore)
        assertEquals(StorageResult.Success(Unit), storage.put("token", "v"))
        user.authenticated = false

        assertEquals(StorageResult.Success<String?>(null), storage.get("never written"), "no entry, no key used")
        assertEquals(StorageResult.Success(Unit), storage.remove("token"))
        assertEquals(StorageResult.Success(Unit), storage.clear())
        assertEquals(StorageResult.Success(AndroidKeySecurityLevel.UNKNOWN), storage.androidKeySecurityLevel(), "the level is asked of the key, never of the cipher")
        assertEquals(0, keyStore.deleted.get())
    }

    @Test
    fun `the same refusal without user authentication is a locked device, Unavailable, not AccessDenied`() = runTest {
        val keyStore = AuthKeyStore(User(authenticated = false))
        keyStore.entry = AuthKey(TestKeys.generate(), keyStore.user)
        val storage = storeOver(keyStore, AndroidSecureStorageOptions(unlockedDeviceRequired = true))

        for ((label: String, error: StorageError?) in listOf(
            "put" to storage.put("k", "v").errorOrNull(),
            "ensureKey" to storage.ensureKey().errorOrNull(),
        )) {
            assertIs<StorageError.Unavailable>(error, "$label was $error")
        }
        assertEquals(0, keyStore.deleted.get())
    }

    @Test
    fun `a store with both options answers AccessDenied for either refusal`() = runTest {
        val keyStore = AuthKeyStore(User(authenticated = false))
        keyStore.entry = AuthKey(TestKeys.generate(), keyStore.user)
        val options = AndroidSecureStorageOptions(unlockedDeviceRequired = true, userAuthentication = AndroidUserAuthentication(10))

        assertIs<StorageError.AccessDenied>(storeOver(keyStore, options).ensureKey().errorOrNull())
    }

    @Test
    fun `an unauthenticated migration pass stops without stamping and finishes once the user has authenticated`() = runTest {
        val user = User(authenticated = true)
        val keyStore = AuthKeyStore(user)
        val preferences: SharedPreferences = preferences()
        val alias = "userauth.pass"
        val writer = storeOver(keyStore, preferences = preferences, alias = alias)
        assertEquals(StorageResult.Success(Unit), writer.put("a", "1"))
        preferences.edit().remove(AndroidSecureKeyValueStorage.FORMAT_KEY).commit()
        val storage = storeOver(keyStore, preferences = preferences, alias = alias)
        user.authenticated = false

        assertIs<StorageError.AccessDenied>(storage.get("a").errorOrNull())
        assertFalse(preferences.contains(AndroidSecureKeyValueStorage.FORMAT_KEY), "a pass that could not run never stamps")

        user.authenticated = true
        assertEquals(StorageResult.Success<String?>("1"), storage.get("a"))
        assertTrue(preferences.contains(AndroidSecureKeyValueStorage.FORMAT_KEY), "the pass finished after authentication")
    }

    // -- Invalidation by an enrolment change (A-4 with an authentication-bound key) -------------------------------

    @Test
    fun `a key the platform invalidated after an enrolment change is replaced by a new authenticated one and old values are lost`() = runTest {
        val user = User(authenticated = true)
        val keyStore = AuthKeyStore(user)
        val storage = storeOver(keyStore)
        assertEquals(StorageResult.Success(Unit), storage.put("old", "lost with the key"))
        checkNotNull(keyStore.entry).dead = true

        assertEquals(StorageResult.Success(Unit), storage.put("new", "written under the new key"), "writes succeed (A-4)")

        assertEquals(2, keyStore.generated.get(), "one new key")
        assertEquals(1, keyStore.deleted.get(), "exactly the dead key was deleted")
        assertTrue(keyStore.optionsSeen.all { it === authenticated }, "the new key is generated with the same options")
        assertIs<StorageError.Undecryptable>(storage.get("old").errorOrNull(), "values of the invalidated key are lost")
        assertEquals(StorageResult.Success<String?>("written under the new key"), storage.get("new"))
        user.authenticated = false
        assertIs<StorageError.AccessDenied>(storage.get("new").errorOrNull(), "the new key is bound too")
    }

    @Test
    fun `replacing the key on a device that no longer has a lock screen is Unavailable and caches nothing, then recovers`() = runTest {
        val user = User(authenticated = true)
        val keyStore = AuthKeyStore(user)
        val storage = storeOver(keyStore)
        assertEquals(StorageResult.Success(Unit), storage.put("old", "v"))
        checkNotNull(keyStore.entry).dead = true
        keyStore.lockScreenSet = false

        for ((label: String, error: StorageError?) in listOf(
            "put" to storage.put("k", "v").errorOrNull(),
            "ensureKey" to storage.ensureKey().errorOrNull(),
            "get" to storage.get("old").errorOrNull(),
        )) {
            assertIs<StorageError.Unavailable>(error, "$label without a lock screen was $error")
        }
        assertEquals(1, keyStore.generated.get(), "no key could be generated, and a failed generation is not cached")

        keyStore.lockScreenSet = true
        assertEquals(StorageResult.Success(Unit), storage.put("k", "v"))
        assertEquals(2, keyStore.generated.get(), "exactly one key once the user has set a lock screen")
    }

    @Test
    fun `a first use on a device without a lock screen is Unavailable, never an unauthenticated key`() = runTest {
        val keyStore = AuthKeyStore(User(authenticated = true)).also { it.lockScreenSet = false }
        val preferences: SharedPreferences = preferences()
        val storage = storeOver(keyStore, preferences = preferences)

        assertIs<StorageError.Unavailable>(storage.put("k", "v").errorOrNull())
        assertNull(keyStore.entry, "nothing was generated")
        assertTrue(preferences.all.isEmpty(), "a refused first write leaves the file empty")
    }

    // -- Cancellation --------------------------------------------------------------------------------------------

    @Test
    fun `a cancellation thrown by an authentication-bound key source while the caller is not cancelled propagates`() = runTest {
        val cancelling = object : SecretKeySource {
            override val userAuthenticationRequired: Boolean = true
            override suspend fun key(): SecretKey = throw CancellationException("source cancelled")
        }
        val storage = AndroidSecureKeyValueStorage(preferences(), KeystoreValueCipher(cancelling), Dispatchers.Unconfined)

        assertFailsWith<CancellationException> { storage.ensureKey() }
        assertFailsWith<CancellationException> { storage.put("k", "v") }
    }

    @Test
    fun `a dismissed prompt leaves nothing behind, and a later authentication works on the same key`() = runTest(timeout = 60.seconds) {
        val user = User(authenticated = false)
        val keyStore = AuthKeyStore(user)
        val storage = storeOver(keyStore)
        // The app's prompt, modelled as a job that is cancelled (dismissed) instead of completing the authentication.
        val prompt = CompletableDeferred<Unit>()
        val appFlow: Job = launch(start = CoroutineStart.UNDISPATCHED) {
            prompt.await()
            user.authenticated = true
        }
        assertIs<StorageError.AccessDenied>(storage.ensureKey().errorOrNull(), "control: the user is not authenticated")

        appFlow.cancel()
        assertFalse(user.authenticated, "a dismissed prompt authenticates nobody")
        assertIs<StorageError.AccessDenied>(storage.ensureKey().errorOrNull(), "still AccessDenied after the dismissal")
        assertEquals(0, keyStore.deleted.get())
        assertEquals(1, keyStore.generated.get(), "the key made by the first call is still the only one")

        user.authenticated = true
        assertEquals(StorageResult.Success(Unit), storage.put("k", "v"))
        assertEquals(StorageResult.Success<String?>("v"), storage.get("k"))
        assertEquals(1, keyStore.generated.get(), "one key for the whole story")
    }

    @Test
    fun `a call cancelled while it waits for the alias lock leaves the lock usable and the mapping intact`() = runTest(timeout = 60.seconds) {
        val user = User(authenticated = true)
        val hold = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val keyStore = AuthKeyStore(user, hold, entered)
        val alias = "userauth.cancel.alias"
        val holderStore = storeOver(keyStore, dispatcher = Dispatchers.IO, alias = alias)
        val waiterStore = storeOver(keyStore, dispatcher = Dispatchers.Unconfined, alias = alias)
        var holderOutcome: StorageResult<Unit>? = null
        var waiterOutcome: StorageResult<Unit>? = null
        try {
            val holder: Job = launch(Dispatchers.IO) { holderOutcome = holderStore.ensureKey() }
            withContext(Dispatchers.IO) { assertTrue(entered.await(30, TimeUnit.SECONDS), "the holder never reached the key store") }

            val waiter: Job = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { waiterOutcome = waiterStore.ensureKey() }
            assertTrue(waiter.isActive, "control: the waiter is suspended on the alias lock")
            waiter.cancel()
            withTimeout(30.seconds) { waiter.join() }
            assertTrue(waiter.isCancelled)
            assertNull(waiterOutcome, "a cancelled call reported a result instead of throwing")

            hold.countDown()
            withContext(Dispatchers.Default) { withTimeout(30.seconds) { holder.join() } }
            assertEquals(StorageResult.Success(Unit), holderOutcome)
        } finally {
            hold.countDown()
        }

        user.authenticated = false
        assertIs<StorageError.AccessDenied>(withContext(Dispatchers.Default) { waiterStore.ensureKey().errorOrNull() })
        assertEquals(1, keyStore.generated.get())
        assertEquals(0, keyStore.deleted.get())
    }

    // -- A seeded storm over a window that opens and closes ---------------------------------------------------------

    @Test
    fun `a storm of writers and readers while the window flips only ever sees Success or AccessDenied and loses nothing`() = runTest(timeout = 120.seconds) {
        val seed = 77
        val user = User(authenticated = true)
        val keyStore = AuthKeyStore(user)
        val preferences: SharedPreferences = preferences()
        val alias = "userauth.storm"
        val stores: List<AndroidSecureKeyValueStorage> = List(2) { storeOver(keyStore, preferences = preferences, dispatcher = Dispatchers.IO, alias = alias) }
        val written: MutableMap<String, String> = Collections.synchronizedMap(mutableMapOf())
        val failures: MutableList<String> = Collections.synchronizedList(mutableListOf())

        withContext(Dispatchers.Default) {
            withTimeout(90.seconds) {
                coroutineScope {
                    repeat(12) { worker: Int ->
                        launch {
                            val random = Random(seed * 1000 + worker)
                            val storage: AndroidSecureKeyValueStorage = stores[worker % 2]
                            repeat(40) { step: Int ->
                                if (random.nextInt(4) == 0) user.authenticated = random.nextBoolean()
                                when (random.nextInt(3)) {
                                    0 -> {
                                        val key = "w$worker-$step"
                                        when (val result: StorageResult<Unit> = storage.put(key, "v$step")) {
                                            is StorageResult.Success -> written[key] = "v$step"
                                            is StorageResult.Failure ->
                                                if (result.error !is StorageError.AccessDenied) failures += "put $key ${result.error}"
                                        }
                                    }
                                    1 -> storage.get("w${random.nextInt(12)}-${random.nextInt(40)}").let { result: StorageResult<String?> ->
                                        val error: StorageError? = result.errorOrNull()
                                        if (error != null && error !is StorageError.AccessDenied) failures += "get $error"
                                    }
                                    else -> storage.ensureKey().errorOrNull()?.let { error: StorageError ->
                                        if (error !is StorageError.AccessDenied) failures += "ensureKey $error"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (failures.isNotEmpty()) fail("seed=$seed: ${failures.size} unexpected failure(s), first ${failures.first()}")
        assertEquals(1, keyStore.generated.get(), "seed=$seed: one key for the whole storm")
        assertEquals(0, keyStore.deleted.get(), "seed=$seed: an unauthenticated user never deletes the key")
        assertTrue(written.isNotEmpty(), "seed=$seed: control: some writes landed")
        user.authenticated = true
        for ((key: String, value: String) in written) {
            assertEquals(StorageResult.Success<String?>(value), stores[0].get(key), "seed=$seed: $key")
        }
    }
}
