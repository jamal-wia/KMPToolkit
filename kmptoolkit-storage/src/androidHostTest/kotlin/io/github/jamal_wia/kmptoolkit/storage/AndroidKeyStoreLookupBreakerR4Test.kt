package io.github.jamal_wia.kmptoolkit.storage

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.PrivateKey
import java.security.Provider
import java.security.ProviderException
import java.security.SecureRandom
import java.security.Security
import java.security.UnrecoverableKeyException
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Breaker review round 4 of group sto-android (ticket release-5-1-0, STO-4): every other way the production lookup
 * ([AndroidKeyStoreAliases.existingKey], `KeyStore.getKey` since the round-3 fix) could decide "there is no key" and let
 * [AndroidKeystoreKeySource] generate a new key over a live one.
 *
 * Contract: [AliasedKeyStore.existingKey] ("`null` when there is none to use: never filed, or filed but PROVEN
 * permanently invalidated"; every other failure "is rethrown instead and nothing is deleted"), the KDoc of
 * `AndroidKeystoreKeySource.resolve` (only the first resolution in a process may delete, and only on proof), plan A-4 /
 * m1 ("never destroy values over an ambiguous fault") and CHANGELOG Breaking 10.
 *
 * [Keystore2R4Spi] is a keystore-2-shaped provider registered as "AndroidKeyStore" for the length of each test, so the
 * production object [AndroidKeyStoreAliases] and the production `generateKey` run unchanged over it. Its `engineGetKey`
 * throws whatever [Keystore2R4Daemon.lookupFailure] produces (the shapes of keystore 2 `engineGetKey`, read from the
 * android-all 14 bytecode, and the shapes a binder or provider fault can take), and answers the filed [Key] otherwise,
 * whatever its type. The daemon's `KeyStoreException` is the real `android.security.KeyStoreException`, built through
 * its hidden constructor ([platformKeyStoreException]).
 */
@RunWith(RobolectricTestRunner::class)
class AndroidKeyStoreLookupBreakerR4Test {

    private var sequence: Int = 0

    @Before
    fun install() {
        Keystore2R4Daemon.reset()
        Security.insertProviderAt(Keystore2R4Provider, 1)
    }

    @After
    fun uninstall() {
        Security.removeProvider(Keystore2R4Provider.name)
        Keystore2R4Daemon.reset()
    }

    private fun alias(label: String): String = "breaker.r4.lookup.$label.${sequence++}.${System.identityHashCode(this)}"

    private fun filed(alias: String): SecretKey {
        val key: SecretKey = Keystore2R4Daemon.newKey()
        Keystore2R4Daemon.entries[alias] = key
        return key
    }

    /** keystore 2 `engineGetKey` for any daemon code but 7 and 17. */
    private fun daemonFault(code: Int): UnrecoverableKeyException =
        UnrecoverableKeyException("Failed to obtain information about key").also {
            it.initCause(platformKeyStoreException(code, "daemon answered $code"))
        }

    // -- Control ------------------------------------------------------------------------------------------------------

    @Test
    fun `control - the provider sits under the production lookup and the daemon exception is the platform class`() = runTest {
        val alias: String = alias("control")
        val live: SecretKey = filed(alias)
        assertSame(live, AndroidKeystoreKeySource(alias).key(), "control: the production lookup reads the fake provider")
        val platform: Exception = platformKeyStoreException(SYSTEM_ERROR, "x")
        assertEquals("android.security.KeyStoreException", platform.javaClass.name, "control: the real platform class")
        assertEquals(0, Keystore2R4Daemon.generations.get())
    }

    // -- Attacks on the "no key" decision ----------------------------------------------------------------------------

    @Test
    fun `a lookup that throws something other than UnrecoverableKeyException never generates over the live key`() = runTest {
        val shapes: List<Pair<String, () -> Exception>> = listOf(
            "ProviderException (binder / provider fault)" to { ProviderException("Keystore operation failed") },
            "NullPointerException (no keystore service)" to { NullPointerException("service") },
            "IllegalStateException" to { IllegalStateException("not ready") },
            "java.security.KeyStoreException" to { java.security.KeyStoreException("uninitialized") },
            "LOCKED (2)" to { daemonFault(LOCKED) },
            "UNINITIALIZED (3)" to { daemonFault(UNINITIALIZED) },
            "SYSTEM_ERROR (4)" to { daemonFault(SYSTEM_ERROR) },
            "PERMISSION_DENIED (6)" to { daemonFault(PERMISSION_DENIED) },
            "BACKEND_BUSY (24)" to { daemonFault(BACKEND_BUSY) },
            "a cause-less UnrecoverableKeyException with another message" to { UnrecoverableKeyException("Key algorithm unknown") },
        )
        for ((label: String, shape: () -> Exception) in shapes) {
            val alias: String = alias("shape")
            val live: SecretKey = filed(alias)
            val generationsBefore: Int = Keystore2R4Daemon.generations.get()
            Keystore2R4Daemon.lookupFailure = shape

            assertFailsWith<StorageKeyUnavailableException>("$label at the first lookup must answer Unavailable") {
                AndroidKeystoreKeySource(alias).key()
            }
            assertSame(live, Keystore2R4Daemon.entries[alias], "$label: the live key must stay filed")
            assertEquals(generationsBefore, Keystore2R4Daemon.generations.get(), "$label: nothing generated")

            Keystore2R4Daemon.lookupFailure = null
            assertSame(live, AndroidKeystoreKeySource(alias).key(), "$label: once the lookup answers, the same key is found")
        }
        assertEquals(0, Keystore2R4Daemon.deletions.get(), "no shape is proof, nothing is deleted")
    }

    @Test
    fun `an alias that holds a key that is not a secret key is not taken for an empty alias and overwritten`() = runTest {
        val alias: String = alias("foreign")
        val foreign: PrivateKey = ForeignPrivateKey
        Keystore2R4Daemon.entries[alias] = foreign

        val outcome: Result<SecretKey> = runCatching { AndroidKeystoreKeySource(alias).key() }

        assertSame(
            foreign,
            Keystore2R4Daemon.entries[alias],
            "AliasedKeyStore.existingKey answers null only for an alias never filed or proven dead; a filed key of " +
                "another type must not be replaced by generateKey (outcome=$outcome, " +
                "generations=${Keystore2R4Daemon.generations.get()})",
        )
        assertEquals(0, Keystore2R4Daemon.generations.get(), "nothing generated over a filed key")
        assertTrue(outcome.isFailure, "the store cannot use that alias: a typed failure, not a new key ($outcome)")
    }

    @Test
    fun `a failed first resolution keeps the first-resolution right to replace a key proven dead`() = runTest {
        val alias: String = alias("first.failed")
        val dead: SecretKey = filed(alias)
        val source = AndroidKeystoreKeySource(alias)
        Keystore2R4Daemon.lookupFailure = { daemonFault(SYSTEM_ERROR) }
        assertFailsWith<StorageKeyUnavailableException>("control: the fault answers Unavailable") { source.key() }
        assertSame(dead, Keystore2R4Daemon.entries[alias], "control: nothing replaced over the fault")

        // The daemon now gives its proof (keystore 2 engineGetKey for KEY_PERMANENTLY_INVALIDATED).
        Keystore2R4Daemon.lookupFailure = { UnrecoverableKeyException(PLATFORM_KEY_INVALIDATED_MESSAGE) }
        val replacement: SecretKey = source.key()

        assertNotSame(dead, replacement, "no resolution has succeeded yet, so this is still the first one and may replace")
        assertEquals(1, Keystore2R4Daemon.deletions.get())
        assertEquals(1, Keystore2R4Daemon.generations.get())
        assertSame(replacement, Keystore2R4Daemon.entries[alias])
    }

    @Test
    fun `a proven dead key whose replacement cannot be generated yet is Unavailable, caches nothing, and recovers once`() = runTest {
        val alias: String = alias("dead.nogen")
        filed(alias)
        Keystore2R4Daemon.lookupFailure = { UnrecoverableKeyException(PLATFORM_KEY_INVALIDATED_MESSAGE) }
        Keystore2R4Daemon.generationFailure = { IllegalStateException("Secure lock screen must be enabled") }
        val source = AndroidKeystoreKeySource(alias)

        assertIs<StorageKeyUnavailableException>(runCatching { source.key() }.exceptionOrNull())
        assertEquals(1, Keystore2R4Daemon.deletions.get(), "control: the dead key was deleted on proof")

        Keystore2R4Daemon.lookupFailure = null
        Keystore2R4Daemon.generationFailure = null
        val fresh: SecretKey = source.key()

        assertEquals(1, Keystore2R4Daemon.generations.get(), "exactly one new key once generation works")
        assertSame(fresh, Keystore2R4Daemon.entries[alias])
        assertSame(fresh, AndroidKeystoreKeySource(alias).key(), "another instance shares it from the cache")
    }

    @Test
    fun `a seeded storm of first uses and re-lookups over several instances while the daemon flaps never replaces the key`() =
        runTest(timeout = 120.seconds) {
            val seed = 4401
            val alias: String = alias("storm")
            val live: SecretKey = filed(alias)
            val schedule = Random(seed)
            val faults: BooleanArray = BooleanArray(STORM_LOOKUP_CAP) { schedule.nextInt(3) == 0 }
            val codes: IntArray = IntArray(STORM_LOOKUP_CAP) { listOf(LOCKED, SYSTEM_ERROR, BACKEND_BUSY)[schedule.nextInt(3)] }
            val lookups = AtomicInteger()
            Keystore2R4Daemon.lookupHook = {
                val index: Int = lookups.getAndIncrement()
                if (index < STORM_LOOKUP_CAP && faults[index]) throw daemonFault(codes[index])
            }
            val problems: MutableList<String> = Collections.synchronizedList(mutableListOf())

            withContext(Dispatchers.Default) {
                withTimeout(60.seconds) {
                    coroutineScope {
                        (0 until 12).map { worker: Int ->
                            async {
                                val random = Random(seed * 1000 + worker)
                                repeat(40) { step: Int ->
                                    // A new instance per call, as every createSecureKeyValueStorage call makes one.
                                    val source = AndroidKeystoreKeySource(alias)
                                    if (random.nextInt(4) == 0) source.invalidate(live)
                                    val outcome: Result<SecretKey> = runCatching { source.key() }
                                    val key: SecretKey? = outcome.getOrNull()
                                    val error: Throwable? = outcome.exceptionOrNull()
                                    if (key != null && key !== live) problems += "worker $worker step $step: a key other than the live one"
                                    if (error != null && error !is StorageKeyUnavailableException) {
                                        problems += "worker $worker step $step: $error"
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
            }

            assertEquals(emptyList(), problems.toList(), "seed=$seed: only the live key or Unavailable")
            assertTrue(lookups.get() > 1, "seed=$seed control: the storm re-looked the alias up (${lookups.get()} lookups)")
            assertEquals(0, Keystore2R4Daemon.generations.get(), "seed=$seed: nothing generated over the live key")
            assertEquals(0, Keystore2R4Daemon.deletions.get(), "seed=$seed: nothing deleted")
            assertSame(live, Keystore2R4Daemon.entries[alias], "seed=$seed: the live key is still filed")
        }

    private companion object {
        const val LOCKED = 2
        const val UNINITIALIZED = 3
        const val SYSTEM_ERROR = 4
        const val PERMISSION_DENIED = 6
        const val BACKEND_BUSY = 24
        const val STORM_LOOKUP_CAP = 4096
    }
}

/**
 * The real `android.security.KeyStoreException(int, String)`, whose constructor the compile-time `android.jar` hides;
 * the Robolectric runtime has the framework class.
 */
internal fun platformKeyStoreException(code: Int, message: String): Exception =
    Class.forName("android.security.KeyStoreException")
        .getConstructor(Int::class.javaPrimitiveType, String::class.java)
        .newInstance(code, message) as Exception

/** A filed key that is not a secret key, as an asymmetric entry under the same alias would answer. */
private object ForeignPrivateKey : PrivateKey {
    private fun readResolve(): Any = ForeignPrivateKey
    override fun getAlgorithm(): String = "EC"
    override fun getFormat(): String? = null
    override fun getEncoded(): ByteArray? = null
}

/** The daemon behind [Keystore2R4Spi]. */
internal object Keystore2R4Daemon {
    val entries: ConcurrentHashMap<String, Key> = ConcurrentHashMap()

    /** When set, every `engineGetKey` throws what it produces. */
    @Volatile
    var lookupFailure: (() -> Exception)? = null

    /** Runs at every `engineGetKey` before the answer; may throw. */
    @Volatile
    var lookupHook: (() -> Unit)? = null

    /** When set, every key generation throws what it produces and files nothing. */
    @Volatile
    var generationFailure: (() -> Exception)? = null

    val generations: AtomicInteger = AtomicInteger()
    val deletions: AtomicInteger = AtomicInteger()
    private val keysMade: AtomicInteger = AtomicInteger()

    fun newKey(): SecretKey {
        val made: Int = keysMade.incrementAndGet()
        return SecretKeySpec(ByteArray(32) { index: Int -> (index + 17 * made).toByte() }, "AES")
    }

    fun reset() {
        entries.clear()
        lookupFailure = null
        lookupHook = null
        generationFailure = null
        generations.set(0)
        deletions.set(0)
    }
}

internal object Keystore2R4Provider : Provider("AndroidKeyStore", 1.0, "keystore 2 lookup shape, breaker round 4") {
    private fun readResolve(): Any = Keystore2R4Provider

    init {
        putService(object : Service(this, "KeyStore", "AndroidKeyStore", Keystore2R4Spi::class.java.name, null, null) {
            override fun newInstance(constructorParameter: Any?): Any = Keystore2R4Spi()
        })
        putService(object : Service(this, "KeyGenerator", "AES", Keystore2R4KeyGenerator::class.java.name, null, null) {
            override fun newInstance(constructorParameter: Any?): Any = Keystore2R4KeyGenerator()
        })
    }
}

internal class Keystore2R4KeyGenerator : KeyGeneratorSpi() {
    private var alias: String? = null

    override fun engineInit(random: SecureRandom?) = throw UnsupportedOperationException("needs a KeyGenParameterSpec")
    override fun engineInit(keysize: Int, random: SecureRandom?) = throw UnsupportedOperationException("needs a KeyGenParameterSpec")
    override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
        alias = (params as KeyGenParameterSpec).keystoreAlias
    }

    override fun engineGenerateKey(): SecretKey {
        Keystore2R4Daemon.generationFailure?.let { failure: () -> Exception -> throw failure() }
        val key: SecretKey = Keystore2R4Daemon.newKey()
        Keystore2R4Daemon.entries[checkNotNull(alias)] = key
        Keystore2R4Daemon.generations.incrementAndGet()
        return key
    }
}

internal class Keystore2R4Spi : KeyStoreSpi() {

    override fun engineGetKey(alias: String, password: CharArray?): Key? {
        Keystore2R4Daemon.lookupHook?.invoke()
        Keystore2R4Daemon.lookupFailure?.let { failure: () -> Exception -> throw failure() }
        return Keystore2R4Daemon.entries[alias]
    }

    override fun engineIsKeyEntry(alias: String): Boolean = Keystore2R4Daemon.entries.containsKey(alias)
    override fun engineContainsAlias(alias: String): Boolean = Keystore2R4Daemon.entries.containsKey(alias)

    override fun engineDeleteEntry(alias: String) {
        Keystore2R4Daemon.deletions.incrementAndGet()
        Keystore2R4Daemon.entries.remove(alias)
    }

    override fun engineLoad(stream: InputStream?, password: CharArray?) {}
    override fun engineStore(stream: OutputStream?, password: CharArray?) = throw UnsupportedOperationException()
    override fun engineAliases(): Enumeration<String> = Collections.enumeration(Keystore2R4Daemon.entries.keys.toList())
    override fun engineSize(): Int = Keystore2R4Daemon.entries.size
    override fun engineGetCertificate(alias: String?): Certificate? = null
    override fun engineGetCertificateChain(alias: String?): Array<Certificate>? = null
    override fun engineGetCreationDate(alias: String?): Date? = null
    override fun engineIsCertificateEntry(alias: String?): Boolean = false
    override fun engineGetCertificateAlias(cert: Certificate?): String? = null
    override fun engineSetKeyEntry(alias: String?, key: Key?, password: CharArray?, chain: Array<out Certificate>?) =
        throw UnsupportedOperationException()
    override fun engineSetKeyEntry(alias: String?, key: ByteArray?, chain: Array<out Certificate>?) =
        throw UnsupportedOperationException()
    override fun engineSetCertificateEntry(alias: String?, cert: Certificate?) = throw UnsupportedOperationException()
}
