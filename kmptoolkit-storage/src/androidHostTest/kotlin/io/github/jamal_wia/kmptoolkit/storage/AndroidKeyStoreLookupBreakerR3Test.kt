package io.github.jamal_wia.kmptoolkit.storage

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStore
import java.security.KeyStoreException
import java.security.KeyStoreSpi
import java.security.Provider
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Breaker review round 3 of group sto-android (ticket release-5-1-0, STO-4): the production lookup
 * ([AndroidKeyStoreAliases.existingKey], `KeyStore.getEntry`) against a key-store provider that answers the way the
 * keystore 2 `AndroidKeyStoreSpi` (API 31 and later) really answers.
 *
 * Contract: [resolveExistingKey] and [AliasedKeyStore.existingKey] (a lookup failure that is not the platform's proof,
 * "a locked device, a transient fault, anything unknown", is rethrown and nothing is deleted; when `mayDelete` is false
 * every failure is rethrown), the KDoc of `AndroidKeystoreKeySource.resolve` (a later resolution "fails with
 * StorageKeyUnavailableException instead"), plan A-4 / m1 ("never destroy values over an ambiguous fault") and
 * CHANGELOG Breaking 10 ("Any other lookup failure ... deletes nothing and answers Unavailable").
 *
 * The platform shape, read from the framework bytecode of the Robolectric android-all jars for API 31 (12), 34 (14)
 * and 36.1 (17) with `javap -c`, and from AOSP `libcore/ojluni/.../java/security/KeyStoreSpi.java`:
 * - `KeyStore.getEntry(alias, null)` reaches the libcore default `KeyStoreSpi.engineGetEntry` (keystore 2's
 *   `AndroidKeyStoreSpi` does not override it). That default asks `engineIsKeyEntry(alias)` and then
 *   `engineContainsAlias(alias)` (older libcore: `engineContainsAlias` first) and answers `null` when both say no;
 *   only then does it call `engineGetKey`.
 * - keystore 2 `engineIsKeyEntry` and `engineContainsAlias` both go through the private `getKeyMetadata(alias)`,
 *   which calls `KeyStore2.getKeyEntry` and CATCHES every `android.security.KeyStoreException`, logging every code but
 *   KEY_NOT_FOUND (7), and returns `null`. So any daemon failure of `getKeyEntry` (SYSTEM_ERROR after the lost-binder
 *   retry, a busy backend, KEY_PERMANENTLY_INVALIDATED (17)) makes `getEntry` answer `null`, "no key", and never
 *   reaches `engineGetKey`.
 * - keystore 2 `engineGetKey` itself calls the same `getKeyEntry` (through
 *   `AndroidKeyStoreProvider.loadAndroidKeyStoreKeyFromKeystore`) and answers: `null` for KEY_NOT_FOUND only;
 *   `UnrecoverableKeyException("User changed or deleted their auth credentials")` with no cause for 17;
 *   `UnrecoverableKeyException("Failed to obtain information about key", cause = KeyStoreException)` for every other
 *   code. That is the shape [resolveExistingKey] was written for; `getEntry` hides it.
 *
 * [Keystore2LikeSpi] reproduces exactly these three methods and the libcore `engineGetEntry`;
 * `java.security.KeyStoreException` stands in for `android.security.KeyStoreException` (its constructor is hidden).
 * The provider is registered as "AndroidKeyStore" for the length of each test, so the production object
 * [AndroidKeyStoreAliases] and the production `generateKey` run unchanged over it.
 */
@RunWith(RobolectricTestRunner::class)
class AndroidKeyStoreLookupBreakerR3Test {

    private var sequence: Int = 0

    @Before
    fun install() {
        Keystore2Daemon.reset()
        Security.insertProviderAt(Keystore2LikeProvider, 1)
    }

    @After
    fun uninstall() {
        Security.removeProvider(Keystore2LikeProvider.name)
        Keystore2Daemon.reset()
    }

    private fun alias(label: String): String = "breaker.r3.lookup.$label.${sequence++}.${System.identityHashCode(this)}"

    /** Files [alias] with a key, as an earlier process left it, and answers that key. */
    private fun filed(alias: String): SecretKey {
        val key: SecretKey = Keystore2Daemon.newKey()
        Keystore2Daemon.entries[alias] = key
        return key
    }

    // -- Controls: the fake really sits under the production lookup and generator ------------------------------------

    @Test
    fun `control - an empty alias is generated once through the production generator, and found afterwards`() = runTest {
        val alias: String = alias("empty")
        val source = AndroidKeystoreKeySource(alias)

        val generated: SecretKey = source.key()

        assertEquals(1, Keystore2Daemon.generations.get(), "control: the production generateKey ran over the fake provider")
        assertSame(generated, Keystore2Daemon.entries[alias], "control: the generated key is the one filed")
        assertSame(generated, AndroidKeyStoreAliases.existingKey(alias, mayDelete = false), "control: a lookup finds it")
        assertEquals(0, Keystore2Daemon.deletions.get())
    }

    @Test
    fun `control - a key the daemon reports permanently invalidated at the first lookup is replaced`() = runTest {
        val alias: String = alias("dead.first")
        val dead: SecretKey = filed(alias)
        Keystore2Daemon.failCode = KEY_PERMANENTLY_INVALIDATED

        val replacement: SecretKey = AndroidKeystoreKeySource(alias).key()

        assertNotSame(dead, replacement, "plan A-4: a key the platform calls dead is replaced at the first resolution")
        assertEquals(1, Keystore2Daemon.generations.get())
    }

    // -- Attacks -------------------------------------------------------------------------------------------------------

    @Test
    fun `a daemon fault at the first lookup in a process keeps the live key and answers Unavailable`() = runTest {
        val alias: String = alias("fault.first")
        val live: SecretKey = filed(alias)
        Keystore2Daemon.failCode = SYSTEM_ERROR

        assertFailsWith<StorageKeyUnavailableException>(
            "a lookup failure that is not the platform's proof must be rethrown (resolveExistingKey KDoc; CHANGELOG " +
                "Breaking 10: 'Any other lookup failure ... deletes nothing and answers Unavailable'), not taken for an " +
                "empty alias",
        ) {
            AndroidKeystoreKeySource(alias).key()
        }

        assertSame(
            live,
            Keystore2Daemon.entries[alias],
            "the live key must still be filed: a new key generated over it makes every stored value Undecryptable " +
                "(generations=${Keystore2Daemon.generations.get()}, deletions=${Keystore2Daemon.deletions.get()})",
        )
        assertEquals(0, Keystore2Daemon.generations.get(), "nothing may be generated over an ambiguous fault")
    }

    @Test
    fun `a daemon fault at a later lookup in the same process keeps the live key and answers Unavailable`() = runTest {
        val alias: String = alias("fault.later")
        val live: SecretKey = filed(alias)
        val source = AndroidKeystoreKeySource(alias)
        assertSame(live, source.key(), "control: the first resolution finds the live key")
        // A one-off rejection of the handle (the cipher's non-permanent path) makes the next key() look it up again.
        source.invalidate(live)
        Keystore2Daemon.failCode = SYSTEM_ERROR

        assertFailsWith<StorageKeyUnavailableException>(
            "AndroidKeystoreKeySource.resolve KDoc: a later resolution never deletes and 'fails with " +
                "StorageKeyUnavailableException instead'",
        ) {
            source.key()
        }

        assertSame(
            live,
            Keystore2Daemon.entries[alias],
            "the live key must still be filed after a fault at a re-lookup " +
                "(generations=${Keystore2Daemon.generations.get()}, deletions=${Keystore2Daemon.deletions.get()})",
        )
        Keystore2Daemon.failCode = null
        assertSame(live, source.key(), "once the daemon answers again the same key is found")
    }

    @Test
    fun `the platform's invalidation verdict at a later lookup is rethrown, as the lookup contract says`() = runTest {
        val alias: String = alias("dead.later")
        val live: SecretKey = filed(alias)

        Keystore2Daemon.failCode = KEY_PERMANENTLY_INVALIDATED

        val outcome: Result<SecretKey?> = runCatching { AndroidKeyStoreAliases.existingKey(alias, mayDelete = false) }

        assertTrue(
            outcome.isFailure,
            "AliasedKeyStore.existingKey: with mayDelete = false every failure is rethrown and nothing is deleted; it " +
                "answered ${outcome.getOrNull()} (null = 'no key here', on which the caller generates over the alias)",
        )
        assertSame(live, Keystore2Daemon.entries[alias], "nothing deleted at a later lookup")
    }

    private companion object {
        /** keystore 2 `ResponseCode` values, as `getKeyMetadata` and `loadAndroidKeyStoreKeyFromKeystore` read them. */
        const val SYSTEM_ERROR = 4
        const val KEY_PERMANENTLY_INVALIDATED = 17
    }
}

/** The key store daemon behind [Keystore2LikeSpi]: the filed keys and the error code `getKeyEntry` answers with. */
internal object Keystore2Daemon {
    val entries: ConcurrentHashMap<String, SecretKey> = ConcurrentHashMap()

    /** When set, every `KeyStore2.getKeyEntry` fails with this keystore 2 response code. */
    @Volatile
    var failCode: Int? = null

    val generations: AtomicInteger = AtomicInteger()
    val deletions: AtomicInteger = AtomicInteger()
    private val keysMade: AtomicInteger = AtomicInteger()

    fun newKey(): SecretKey {
        val material = ByteArray(32) { index: Int -> (index + 31 * keysMade.incrementAndGet()).toByte() }
        return SecretKeySpec(material, "AES")
    }

    fun reset() {
        entries.clear()
        failCode = null
        generations.set(0)
        deletions.set(0)
    }
}

/** Registered under the platform provider's name, so `KeyGenerator.getInstance("AES", "AndroidKeyStore")` finds it. */
internal object Keystore2LikeProvider : Provider("AndroidKeyStore", 1.0, "keystore 2 lookup shape for host tests") {
    init {
        putService(object : Service(this, "KeyStore", "AndroidKeyStore", Keystore2LikeSpi::class.java.name, null, null) {
            override fun newInstance(constructorParameter: Any?): Any = Keystore2LikeSpi()
        })
        putService(object : Service(this, "KeyGenerator", "AES", Keystore2LikeKeyGenerator::class.java.name, null, null) {
            override fun newInstance(constructorParameter: Any?): Any = Keystore2LikeKeyGenerator()
        })
    }
}

/** `KeyGenerator` that files the generated key under the spec's alias, replacing whatever was there, like the platform. */
internal class Keystore2LikeKeyGenerator : KeyGeneratorSpi() {
    private var alias: String? = null

    override fun engineInit(random: SecureRandom?) = throw UnsupportedOperationException("needs a KeyGenParameterSpec")
    override fun engineInit(keysize: Int, random: SecureRandom?) = throw UnsupportedOperationException("needs a KeyGenParameterSpec")
    override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
        alias = (params as KeyGenParameterSpec).keystoreAlias
    }

    override fun engineGenerateKey(): SecretKey {
        val key: SecretKey = Keystore2Daemon.newKey()
        Keystore2Daemon.entries[checkNotNull(alias)] = key
        Keystore2Daemon.generations.incrementAndGet()
        return key
    }
}

/** The lookup methods of keystore 2 `AndroidKeyStoreSpi` and the libcore `engineGetEntry`; see the test class KDoc. */
internal class Keystore2LikeSpi : KeyStoreSpi() {

    /** `AndroidKeyStoreSpi.getKeyMetadata`: every daemon failure is logged and swallowed into `null`. */
    private fun keyMetadata(alias: String): SecretKey? {
        if (Keystore2Daemon.failCode != null) return null
        return Keystore2Daemon.entries[alias]
    }

    override fun engineIsKeyEntry(alias: String): Boolean = keyMetadata(alias) != null

    override fun engineContainsAlias(alias: String): Boolean = keyMetadata(alias) != null

    /** `AndroidKeyStoreSpi.engineGetKey` over `AndroidKeyStoreProvider.loadAndroidKeyStoreKeyFromKeystore`. */
    override fun engineGetKey(alias: String, password: CharArray?): Key? = when (val code: Int? = Keystore2Daemon.failCode) {
        null -> Keystore2Daemon.entries[alias]
        7 -> null
        17 -> {
            val proof = KeyPermanentlyInvalidatedException(
                "User changed or deleted their auth credentials",
                KeyStoreException("error code 17"),
            )
            throw UnrecoverableKeyException(proof.message)
        }
        else -> throw UnrecoverableKeyException("Failed to obtain information about key")
            .also { it.initCause(KeyStoreException("error code $code")) }
    }

    /** libcore `KeyStoreSpi.engineGetEntry` (Android-added type check, Android-removed password requirement). */
    override fun engineGetEntry(alias: String?, protParam: KeyStore.ProtectionParameter?): KeyStore.Entry? {
        if (alias == null) return null
        if (!engineIsKeyEntry(alias)) {
            if (!engineContainsAlias(alias)) return null
            throw UnsupportedOperationException("certificate entries are not modelled")
        }
        return (engineGetKey(alias, null) as? SecretKey)?.let { key: SecretKey -> KeyStore.SecretKeyEntry(key) }
    }

    override fun engineDeleteEntry(alias: String) {
        Keystore2Daemon.deletions.incrementAndGet()
        Keystore2Daemon.entries.remove(alias)
    }

    override fun engineLoad(stream: InputStream?, password: CharArray?) {}
    override fun engineStore(stream: OutputStream?, password: CharArray?) = throw UnsupportedOperationException()
    override fun engineAliases(): Enumeration<String> = Collections.enumeration(Keystore2Daemon.entries.keys.toList())
    override fun engineSize(): Int = Keystore2Daemon.entries.size
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
