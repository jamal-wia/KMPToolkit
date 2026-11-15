package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.AppEncryptionKey
import io.github.jamal_wia.kmptoolkit.storage.EncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.JvmAppKeyEncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.appKeyStoreId
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.AlgorithmParameters
import java.security.Key
import java.security.MessageDigest
import java.security.MessageDigestSpi
import java.security.Provider
import java.security.ProviderException
import java.security.SecureRandom
import java.security.SecureRandomSpi
import java.security.Security
import java.security.spec.AlgorithmParameterSpec
import java.security.spec.KeySpec
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.CipherSpi
import javax.crypto.IllegalBlockSizeException
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactorySpi
import javax.crypto.ShortBufferException
import javax.crypto.spec.PBEKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Group sto-desktop, phase A breaker round 8 (decisions.md D-23), part A8: the JCA boundary [Jca] of round 9 (decisions D-P12)
 * attacked with every failure shape a provider can produce, at every boundary function and through the stores. The contract,
 * from the `Jca` KDoc and D-P12:
 *
 * 1. "whatever a provider call throws, except an `Error`, is one typed `JcaFailure` (an `IllegalStateException`) with the original
 *    as the cause", "never a message from the provider";
 * 2. "wrong key or tampered" is decided in `aesGcmOpen` only, and only for a bad-tag exception WITHOUT a cause;
 * 3. "an `Error` is never typed or caught".
 *
 * Green here (they pin the boundary): every `Exception` shape (checked JCA exceptions, runtime noise, a provider's own
 * `CancellationException`, bad-tag exceptions with a cause, a cause chain of depth 3, a cyclic chain, a `getCause()` that throws,
 * a `getMessage()` that throws) is a `JcaFailure` whose message carries no provider text; every `Error` (OOM, stack overflow,
 * `InternalError`, `LinkageError`, `ExceptionInInitializerError`, a custom `Error`) leaves as the same instance; a random source
 * that fails has its buffer wiped and the PBKDF2 spec is cleared even when an `Error` leaves; under SunJCE a genuinely wrong key
 * is still AccessDenied (app-key store) and a replaced OS key is still "key lost" (quarantine), while every non-mismatch shape
 * on the decryption of an OS-key store answers `OperationFailed` and quarantines nothing.
 *
 * Red (finding m of this round): a `Throwable` that is neither an `Exception` nor an `Error`. Kotlin has no checked exceptions,
 * so a provider written in Kotlin can `throw` any `Throwable` subclass, and Java code can sneaky-throw one. `Jca.provider` catches
 * `Exception` only, so such a failure leaves every boundary function untyped and escapes the store call, although rule 1 says
 * "whatever a provider call throws, except an Error". Mutation by reading: with `catch (error: Throwable) { if (error is Error)
 * throw error; throw JcaFailure(...) }` both red tests are green and nothing else here changes.
 *
 * The stand-in providers of this class sit at position 1 and delegate to SunJCE / SUN while unarmed; an armed engine throws the
 * shape it was given. The Cipher is armed globally for one store call or one boundary call; the digest and the key factory only
 * for the test's own thread. Every provider is removed in `finally`.
 */
class JcaA8BreakerR8Test {

    private val key: ByteArray = ByteArray(32) { index: Int -> (index + 3).toByte() }
    private val nonce: ByteArray = ByteArray(12) { index: Int -> (index + 70).toByte() }
    private val aad: ByteArray = "a8-aad".toByteArray(Charsets.UTF_8)
    private val plain: ByteArray = "a8-plain".toByteArray(Charsets.UTF_8)

    private fun <T> withShapes(block: () -> T): T {
        val provider = A8ShapeProvider()
        check(Security.insertProviderAt(provider, 1) == 1) { "control: the stand-in provider is first" }
        try {
            return block()
        } finally {
            A8ShapeGcmSpi.onDecrypt.set(null)
            A8ShapeGcmSpi.onEncrypt.set(null)
            A8ShapeDigestSpi.armed.set(null)
            A8ShapeKdfSpi.armed.set(null)
            Security.removeProvider(provider.name)
        }
    }

    private fun assertTyped(what: String, original: Throwable, thrown: Throwable?) {
        val left: Throwable = thrown ?: fail("$what: the provider failed with ${original::class.java.name} and the boundary answered instead of throwing")
        val typed: JcaFailure = assertIs<JcaFailure>(
            left,
            "$what: rule 1 (anything but an Error is one JcaFailure) broken by ${original::class.java.name}; ${left::class.java.name} left the boundary",
        )
        // A getCause() that throws replaces the cause with its own exception: still typed, which is what the rule protects (observation in the report).
        if (original !is A8HostileCause) assertSame(original, typed.cause, "$what: the provider's own throwable is the cause, unchanged")
        val text: String = typed.toString()
        assertFalse(CANARY in text, "$what: the JcaFailure carries the provider's message ($text); KDoc: 'never a message from the provider'")
        assertFalse(CANARY in StorageError.OperationFailed(io.github.jamal_wia.kmptoolkit.storage.StorageOperation.GET, cause = typed).toString())
    }

    private fun assertSameError(what: String, original: Error, thrown: Throwable?) {
        assertSame(original, thrown, "$what: rule 3 (an Error is never typed or caught) broken: $thrown")
    }

    // ---- the boundary, shape by shape ----

    @Test
    fun `every Exception shape of the AES-GCM engine is one JcaFailure and only a cause-less bad tag is wrong key`() {
        val sealed: ByteArray = Jca.aesGcmSeal(key, nonce, aad, TAG_BITS, plain)
        withShapes {
            assertContentEquals(plain, Jca.aesGcmOpen(key, nonce, aad, TAG_BITS, sealed), "control: the stand-in decrypts through SunJCE")
            assertNull(Jca.aesGcmOpen(ByteArray(32) { 1 }, nonce, aad, TAG_BITS, sealed), "control: a real mismatch relayed by the stand-in is null")
            assertTrue(A8ShapeGcmSpi.decrypts.get() >= 2, "control: the stand-in is the engine in use")
            A8ShapeGcmSpi.onDecrypt.set { AEADBadTagException("Tag mismatch") }
            assertNull(Jca.aesGcmOpen(key, nonce, aad, TAG_BITS, sealed), "control: the SunJCE shape (a cause-less bad tag) is wrong key")

            for ((name: String, shape: () -> Exception) in exceptionShapes()) {
                val original: Exception = shape()
                A8ShapeGcmSpi.onDecrypt.set { original }
                assertTyped("aesGcmOpen, $name", original, runCatching { Jca.aesGcmOpen(key, nonce, aad, TAG_BITS, sealed) }.exceptionOrNull())
                A8ShapeGcmSpi.onDecrypt.set(null)
                A8ShapeGcmSpi.onEncrypt.set { original }
                assertTyped("aesGcmSeal, $name", original, runCatching { Jca.aesGcmSeal(key, nonce, aad, TAG_BITS, plain) }.exceptionOrNull())
                A8ShapeGcmSpi.onEncrypt.set(null)
            }
            // Even the SunJCE mismatch shape is a provider failure on ENCRYPTION: no seal ever answers "wrong key".
            val sealMismatch = AEADBadTagException("$CANARY Tag mismatch")
            A8ShapeGcmSpi.onEncrypt.set { sealMismatch }
            assertTyped("aesGcmSeal, cause-less bad tag", sealMismatch, runCatching { Jca.aesGcmSeal(key, nonce, aad, TAG_BITS, plain) }.exceptionOrNull())
            A8ShapeGcmSpi.onEncrypt.set(null)

            for ((name: String, shape: () -> Error) in errorShapes()) {
                val original: Error = shape()
                A8ShapeGcmSpi.onDecrypt.set { original }
                assertSameError("aesGcmOpen, $name", original, runCatching { Jca.aesGcmOpen(key, nonce, aad, TAG_BITS, sealed) }.exceptionOrNull())
                A8ShapeGcmSpi.onDecrypt.set(null)
                A8ShapeGcmSpi.onEncrypt.set { original }
                assertSameError("aesGcmSeal, $name", original, runCatching { Jca.aesGcmSeal(key, nonce, aad, TAG_BITS, plain) }.exceptionOrNull())
                A8ShapeGcmSpi.onEncrypt.set(null)
            }
            // A getCause() that overflows the stack is an Error out of the boundary's own decision: never typed, never "wrong key".
            val overflowing = object : AEADBadTagException("$CANARY") {
                override val cause: Throwable? get() = throw StackOverflowError("getCause (breaker A8)")
            }
            A8ShapeGcmSpi.onDecrypt.set { overflowing }
            val thrown: Throwable? = runCatching { Jca.aesGcmOpen(key, nonce, aad, TAG_BITS, sealed) }.exceptionOrNull()
            assertIs<StackOverflowError>(thrown, "a getCause() that throws an Error: the Error leaves, never null and never typed")
            A8ShapeGcmSpi.onDecrypt.set(null)
            assertContentEquals(plain, Jca.aesGcmOpen(key, nonce, aad, TAG_BITS, sealed), "control: the engine works again once disarmed")
        }
    }

    @Test
    fun `the digest, the key factory and the random source type every Exception, pass every Error and leave no secret behind`() {
        val input: ByteArray = "a8-digest".toByteArray(Charsets.UTF_8)
        val expected: ByteArray = MessageDigest.getInstance("SHA-256", checkNotNull(Security.getProvider("SUN"))).digest(input)
        withShapes {
            assertContentEquals(expected, Jca.sha256(input), "control: the stand-in digests through SUN")
            for ((name: String, shape: () -> Exception) in exceptionShapes()) {
                val original: Exception = shape()
                A8ShapeDigestSpi.armed.set(A8Armed(Thread.currentThread()) { original })
                assertTyped("sha256, $name", original, runCatching { Jca.sha256(input) }.exceptionOrNull())
            }
            for ((name: String, shape: () -> Error) in errorShapes()) {
                val original: Error = shape()
                A8ShapeDigestSpi.armed.set(A8Armed(Thread.currentThread()) { original })
                assertSameError("sha256, $name", original, runCatching { Jca.sha256(input) }.exceptionOrNull())
            }
            A8ShapeDigestSpi.armed.set(null)
            assertContentEquals(expected, Jca.sha256(input), "control: the digest works again once disarmed")

            // The key factory: an Error leaves as itself and the spec's copy of the password is cleared on the way out.
            val password: CharArray = "a8-password".toCharArray()
            assertEquals(32, Jca.pbkdf2Sha256(password, ByteArray(16) { 5 }, 1, 32).size, "control: the stand-in derives through SunJCE")
            for ((name: String, shape: () -> Error) in errorShapes()) {
                val original: Error = shape()
                A8ShapeKdfSpi.armed.set(A8Armed(Thread.currentThread()) { original })
                A8ShapeKdfSpi.lastSpec.set(null)
                assertSameError("pbkdf2Sha256, $name", original, runCatching { Jca.pbkdf2Sha256(password, ByteArray(16) { 5 }, 1, 32) }.exceptionOrNull())
                val spec: PBEKeySpec = checkNotNull(A8ShapeKdfSpi.lastSpec.get()) { "control: the stand-in factory saw the spec ($name)" }
                assertTrue(runCatching { spec.password }.isFailure, "pbkdf2Sha256, $name: the spec's password copy must be cleared on every way out")
            }
            A8ShapeKdfSpi.armed.set(null)
            assertContentEquals("a8-password".toCharArray(), password, "the caller's password is borrowed, never changed")
        }

        // The random source: a typed failure wipes whatever the engine had written; an Error leaves as itself.
        for ((name: String, shape: () -> Exception) in exceptionShapes()) {
            val original: Exception = shape()
            val random: A8ShapeRandom = a8ShapeRandom { original }
            assertTyped("randomBytes, $name", original, runCatching { Jca.randomBytes(random.random, 32) }.exceptionOrNull())
            val written: ByteArray = checkNotNull(random.spi().lastBuffer) { "control: the engine got the buffer ($name)" }
            assertTrue(written.all { byte: Byte -> byte == 0.toByte() }, "randomBytes, $name: the half-written buffer must be wiped")
        }
        for ((name: String, shape: () -> Error) in errorShapes()) {
            val original: Error = shape()
            val random: A8ShapeRandom = a8ShapeRandom { original }
            assertSameError("randomBytes, $name", original, runCatching { Jca.randomBytes(random.random, 32) }.exceptionOrNull())
        }
    }

    /** RED (m): rule 1 at the boundary for a `Throwable` that is neither an `Exception` nor an `Error`, at every boundary function. */
    @Test
    fun `a provider failure that is neither an Exception nor an Error is typed at every boundary function`() {
        val sealed: ByteArray = Jca.aesGcmSeal(key, nonce, aad, TAG_BITS, plain)
        val broken: MutableList<String> = mutableListOf()
        fun probe(what: String, original: Throwable, call: () -> Any?) {
            val thrown: Throwable? = runCatching { call() }.exceptionOrNull()
            if (thrown !is JcaFailure || thrown.cause !== original) broken += "$what: ${thrown?.let { it::class.java.name } ?: "answered without throwing"}"
        }
        withShapes {
            val raw = A8RawProviderFailure()
            A8ShapeGcmSpi.onDecrypt.set { raw }
            probe("aesGcmOpen", raw) { Jca.aesGcmOpen(key, nonce, aad, TAG_BITS, sealed) }
            A8ShapeGcmSpi.onDecrypt.set(null)
            A8ShapeGcmSpi.onEncrypt.set { raw }
            probe("aesGcmSeal", raw) { Jca.aesGcmSeal(key, nonce, aad, TAG_BITS, plain) }
            A8ShapeGcmSpi.onEncrypt.set(null)
            A8ShapeDigestSpi.armed.set(A8Armed(Thread.currentThread()) { raw })
            probe("sha256", raw) { Jca.sha256(plain) }
            A8ShapeDigestSpi.armed.set(null)
            A8ShapeKdfSpi.armed.set(A8Armed(Thread.currentThread()) { raw })
            probe("pbkdf2Sha256", raw) { Jca.pbkdf2Sha256("a8".toCharArray(), ByteArray(16), 1, 32) }
            A8ShapeKdfSpi.armed.set(null)
        }
        val raw = A8RawProviderFailure()
        val random: A8ShapeRandom = a8ShapeRandom { raw }
        probe("randomBytes", raw) { Jca.randomBytes(random.random, 32) }
        val written: ByteArray? = random.spi().lastBuffer
        if (written == null || written.any { byte: Byte -> byte != 0.toByte() }) broken += "randomBytes: the half-written buffer was not wiped"
        assertEquals(
            emptyList(),
            broken,
            "D-P12 rule 1 / Jca KDoc: 'whatever a provider call throws, except an Error, is one typed JcaFailure'; a Throwable that is " +
                "neither an Exception nor an Error (legal to throw from Kotlin, sneaky-thrown from Java) left these boundary functions untyped",
        )
    }

    /**
     * `base64DecodeExactly` (the Linux key's text): every truncation and extension of a valid text, every single-byte change and a
     * seeded set of arbitrary texts either decode to exactly the asked size or answer `null`; nothing throws (no buffer-size
     * arithmetic, no decoder exception leaves). `seed=` is in every message.
     */
    @Test
    fun `base64 decoding answers exactly the asked size or null for any text and never throws`() {
        val keyBytes = ByteArray(32) { index: Int -> (index * 7 + 1).toByte() }
        val text: ByteArray = Jca.base64Encode(keyBytes)
        assertContentEquals(keyBytes, Jca.base64DecodeExactly(text, 32), "control: a valid text decodes to the key")
        assertNull(Jca.base64DecodeExactly(text, 31), "a text of 32 bytes asked as 31")
        assertNull(Jca.base64DecodeExactly(text, 33), "a text of 32 bytes asked as 33")
        assertContentEquals(ByteArray(0), Jca.base64DecodeExactly(ByteArray(0), 0), "the empty text is zero bytes")
        val seed = 20261132L
        val random = kotlin.random.Random(seed)
        val candidates: MutableList<ByteArray> = mutableListOf()
        for (length in 0..text.size + 8) candidates += ByteArray(length) { index: Int -> if (index < text.size) text[index] else '='.code.toByte() }
        for (index in text.indices) for (value in listOf('-', '_', '=', ' ', '\n', '\u0000', 'A').map { it.code.toByte() }) {
            candidates += text.copyOf().also { bytes: ByteArray -> bytes[index] = value }
        }
        repeat(2000) { candidates += random.nextBytes(random.nextInt(0, 120)) }
        for (candidate: ByteArray in candidates) {
            for (size in listOf(0, 1, 31, 32, 33, 89)) {
                val decoded: ByteArray? = runCatching { Jca.base64DecodeExactly(candidate, size) }
                    .getOrElse { error: Throwable -> fail("seed=$seed: ${candidate.toList()} asked as $size threw $error") }
                if (decoded != null) assertEquals(size, decoded.size, "seed=$seed: ${candidate.toList()} asked as $size")
            }
        }
    }

    // ---- through the stores ----

    /** No regression of the wrong-key verdict under SunJCE: a wrong app key is AccessDenied, a replaced OS key is "key lost". */
    @Test
    fun `under SunJCE a genuinely wrong key is still wrong key through the stores`() = runBlocking {
        withTimeout(HANG_BOUND) {
            val directory: File = Files.createTempDirectory("kmptoolkit-a8-appkey").toFile()
            try {
                val right = AppEncryptionKey.Raw { ByteArray(32) { 1 } }
                val wrong = AppEncryptionKey.Raw { ByteArray(32) { 2 } }
                val owner = JvmAppKeyEncryptedKeyValueStorage("a8", directory, right, Dispatchers.IO, CrossProcessLock())
                assertEquals(StorageResult.Success(Unit), owner.put("k", "v"), "control: the app-key store exists")
                val file = File(directory, "${appKeyStoreId("a8")}.bin")
                val before: ByteArray = file.readBytes()
                val other = JvmAppKeyEncryptedKeyValueStorage("a8", directory, wrong, Dispatchers.IO, CrossProcessLock())
                val answer: StorageResult<String?> = other.get("k")
                assertIs<StorageError.AccessDenied>(assertIs<StorageResult.Failure>(answer, "a wrong app key: $answer").error, "a wrong app key is AccessDenied")
                assertContentEquals(before, file.readBytes(), "a wrong key changes nothing")
            } finally {
                directory.deleteRecursively()
            }

            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                assertEquals(StorageResult.Success(Unit), rig.store().put("k", "v"), "control: the OS-key store exists")
                val item: Map.Entry<Pair<String, String>, ByteArray> = rig.fake.macItems.entries.single()
                rig.fake.macItems[item.key] = ByteArray(item.value.size) { 0x42 }
                val answer: StorageResult<Unit> = rig.store(cache = OsKeyCache()).put("k2", "v2")
                assertEquals(StorageResult.Success(Unit), answer, "a replaced OS key is 'key lost': the put starts a new store")
                assertTrue(rig.fileNames().any { ".undecryptable-" in it }, "the unreadable file is quarantined, not deleted: ${rig.fileNames()}")
            }
        }
        Unit
    }

    /** Every non-mismatch shape on the decryption of an OS-key store with data: OperationFailed, nothing quarantined, nothing changed. */
    @Test
    fun `no provider failure shape on decryption makes an OS-key store quarantine its data`() = runBlocking {
        withTimeout(HANG_BOUND) {
            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                assertEquals(StorageResult.Success(Unit), rig.store().put("k", "v"), "control: the store exists")
                val before: ByteArray = rig.dataFile().readBytes()
                val names: List<String> = rig.fileNames()
                for ((name: String, shape: () -> Exception) in exceptionShapes()) {
                    val cold: EncryptedKeyValueStorage = rig.store(cache = OsKeyCache())
                    val outcome: Result<StorageResult<Unit>> = withShapes {
                        A8ShapeGcmSpi.onDecrypt.set { shape() }
                        runBlocking { runCatching { cold.put("k2", "v2") } }
                    }
                    assertTrue(outcome.isSuccess, "$name: an exception escaped put: ${outcome.exceptionOrNull()}")
                    val failure: StorageResult.Failure = assertIs<StorageResult.Failure>(outcome.getOrThrow(), "$name: a provider failure was read as 'key lost'")
                    assertIs<StorageError.OperationFailed>(failure.error, "$name: ${failure.error}")
                    assertEquals(names, rig.fileNames(), "$name: nothing may be quarantined or left behind")
                    assertContentEquals(before, rig.dataFile().readBytes(), "$name: the store file must be byte-identical")
                }
                assertEquals(StorageResult.Success("v"), rig.store(cache = OsKeyCache()).get("k"), "the data reads once the provider works")
            }
        }
        Unit
    }

    /** RED (m): rule 1 through a store: a provider failure that is neither an Exception nor an Error must answer OperationFailed. */
    @Test
    fun `a provider failure that is neither an Exception nor an Error answers OperationFailed and leaves nothing behind`() = runBlocking {
        withTimeout(HANG_BOUND) {
            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                assertEquals(StorageResult.Success(Unit), rig.store().put("k", "v"), "control: the store exists")
                val before: ByteArray = rig.dataFile().readBytes()
                val names: List<String> = rig.fileNames()
                val cold: EncryptedKeyValueStorage = rig.store(cache = OsKeyCache())
                val raw = A8RawProviderFailure()
                val outcome: Result<StorageResult<String?>> = withShapes {
                    A8ShapeGcmSpi.onDecrypt.set { raw }
                    runBlocking { runCatching { cold.get("k") } }
                }
                assertEquals(names, rig.fileNames(), "nothing may be quarantined or left behind")
                assertContentEquals(before, rig.dataFile().readBytes(), "the store file must be byte-identical")
                assertEquals(StorageResult.Success(Unit), cold.put("k3", "v3"), "the same instance writes once the provider works (no lock left held)")
                val escaped: Throwable? = outcome.exceptionOrNull()
                assertNull(
                    escaped,
                    "D-P12 rule 1: 'whatever a provider call throws, except an Error, is one typed JcaFailure ... so a store answers " +
                        "OperationFailed'; a Throwable that is neither an Exception nor an Error escaped get() as $escaped",
                )
                val failure: StorageResult.Failure = assertIs<StorageResult.Failure>(outcome.getOrThrow())
                val error: StorageError.OperationFailed = assertIs<StorageError.OperationFailed>(failure.error)
                assertSame(raw, (error.cause as? JcaFailure)?.cause, "the provider's throwable is kept as the cause of the JcaFailure")
            }
        }
        Unit
    }

    private companion object {
        const val TAG_BITS: Int = 128
        const val CANARY: String = "A8-CANARY-PROVIDER-TEXT"
        val HANG_BOUND = 120.seconds

        /** Every Exception shape a provider can throw from an engine call, each with the canary in its message. */
        fun exceptionShapes(): List<Pair<String, () -> Exception>> = listOf(
            "AEADBadTagException with a cause (PKCS#11)" to { AEADBadTagException("$CANARY").apply { initCause(ProviderException("token")) } },
            "AEADBadTagException with a cause chain of depth 3" to {
                AEADBadTagException("$CANARY").apply { initCause(ProviderException("a", IllegalStateException("b", IOException("c")))) }
            },
            "AEADBadTagException with a cyclic cause chain" to {
                val first = ProviderException("$CANARY first")
                val second = IllegalStateException("$CANARY second", first)
                first.initCause(second)
                AEADBadTagException("$CANARY").apply { initCause(first) }
            },
            "AEADBadTagException whose getCause() throws" to {
                A8HostileCause()
            },
            "ProviderException whose getMessage() throws" to {
                object : ProviderException() {
                    override val message: String get() = throw IllegalStateException("getMessage broke (breaker A8)")
                }
            },
            "BadPaddingException" to { BadPaddingException("$CANARY") },
            "IllegalBlockSizeException" to { IllegalBlockSizeException("$CANARY") },
            "ShortBufferException" to { ShortBufferException("$CANARY") },
            "ProviderException" to { ProviderException("$CANARY") },
            "UnsupportedOperationException" to { UnsupportedOperationException("$CANARY") },
            "NullPointerException" to { NullPointerException("$CANARY") },
            "IllegalArgumentException from inside the engine" to { IllegalArgumentException("$CANARY") },
            "a provider's own CancellationException" to { java.util.concurrent.CancellationException("$CANARY") },
            "InterruptedException" to { InterruptedException("$CANARY") },
            "IOException" to { IOException("$CANARY") },
            "an exception with suppressed exceptions and a cause" to {
                ProviderException("$CANARY", IllegalStateException("cause")).apply { addSuppressed(IOException("suppressed")) }
            },
        )

        /** Every kind of Error, which the boundary must let through as the same instance. */
        fun errorShapes(): List<Pair<String, () -> Error>> = listOf(
            "OutOfMemoryError" to { OutOfMemoryError("$CANARY") },
            "StackOverflowError" to { StackOverflowError("$CANARY") },
            "InternalError (the DRBG's seed failure)" to { InternalError("$CANARY") },
            "NoClassDefFoundError (a LinkageError)" to { NoClassDefFoundError("$CANARY") },
            "ExceptionInInitializerError" to { ExceptionInInitializerError("$CANARY") },
            "a custom Error (ThreadDeath-like)" to { A8CustomError() },
        )
    }
}

/** A bad-tag exception whose `getCause()` throws. */
class A8HostileCause : AEADBadTagException("A8-CANARY-PROVIDER-TEXT") {
    override val cause: Throwable? get() = throw IllegalStateException("getCause broke (breaker A8)")
}

/** A provider failure that is neither an `Exception` nor an `Error` (legal in Kotlin, sneaky-thrown in Java). */
class A8RawProviderFailure : Throwable("a provider failure that is neither an Exception nor an Error (breaker A8)")

/** An `Error` that is not a JDK one, the way `ThreadDeath` is a plain `Error` subclass. */
class A8CustomError : Error("a custom Error (breaker A8)")

/** Serves AES-GCM, SHA-256 and PBKDF2-HMAC-SHA256 through the shape engines of breaker A8. */
class A8ShapeProvider : Provider("KmpToolkitBreakerA8Shapes", "1.0", "breaker A8: engines that fail in a given shape") {
    init {
        put("Cipher.AES/GCM/NoPadding", A8ShapeGcmSpi::class.java.name)
        put("MessageDigest.SHA-256", A8ShapeDigestSpi::class.java.name)
        put("SecretKeyFactory.PBKDF2WithHmacSHA256", A8ShapeKdfSpi::class.java.name)
    }
}

/** SunJCE's AES-GCM; an armed direction throws its shape at `doFinal` (where no provider fail-over exists). */
class A8ShapeGcmSpi : CipherSpi() {
    private var delegate: Cipher? = null
    private var mode: Int = 0

    private fun target(): Cipher = checkNotNull(delegate) { "not initialised" }

    override fun engineSetMode(mode: String?) = Unit
    override fun engineSetPadding(padding: String?) = Unit
    override fun engineGetBlockSize(): Int = 16
    override fun engineGetOutputSize(inputLen: Int): Int = target().getOutputSize(inputLen)
    override fun engineGetIV(): ByteArray? = delegate?.iv
    override fun engineGetParameters(): AlgorithmParameters? = delegate?.parameters

    private fun open(opmode: Int): Cipher {
        mode = opmode
        return Cipher.getInstance("AES/GCM/NoPadding", checkNotNull(Security.getProvider("SunJCE")) { "control: SunJCE exists" })
            .also { delegate = it }
    }

    override fun engineInit(opmode: Int, key: Key?, random: SecureRandom?) {
        open(opmode).init(opmode, key, random)
    }

    override fun engineInit(opmode: Int, key: Key?, params: AlgorithmParameterSpec?, random: SecureRandom?) {
        open(opmode).init(opmode, key, params, random)
    }

    override fun engineInit(opmode: Int, key: Key?, params: AlgorithmParameters?, random: SecureRandom?) {
        open(opmode).init(opmode, key, params, random)
    }

    override fun engineUpdateAAD(src: ByteArray?, offset: Int, len: Int) {
        target().updateAAD(src, offset, len)
    }

    override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray? = target().update(input, inputOffset, inputLen)

    override fun engineUpdate(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int): Int =
        target().update(input, inputOffset, inputLen, output, outputOffset)

    private fun beforeFinal() {
        if (mode == Cipher.DECRYPT_MODE) {
            decrypts.incrementAndGet()
            onDecrypt.get()?.let { shape: () -> Throwable -> throw shape() }
        }
        if (mode == Cipher.ENCRYPT_MODE) onEncrypt.get()?.let { shape: () -> Throwable -> throw shape() }
    }

    override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int): ByteArray {
        beforeFinal()
        return target().doFinal(input, inputOffset, inputLen)
    }

    override fun engineDoFinal(input: ByteArray?, inputOffset: Int, inputLen: Int, output: ByteArray?, outputOffset: Int): Int {
        beforeFinal()
        return target().doFinal(input, inputOffset, inputLen, output, outputOffset)
    }

    companion object {
        val onDecrypt: AtomicReference<(() -> Throwable)?> = AtomicReference(null)
        val onEncrypt: AtomicReference<(() -> Throwable)?> = AtomicReference(null)
        val decrypts: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger()
    }
}

/** SUN's SHA-256; armed for one thread, `engineDigest` throws the shape (MessageDigest has no fail-over once an engine is chosen). */
class A8ShapeDigestSpi : MessageDigestSpi() {
    private val real: MessageDigest = MessageDigest.getInstance("SHA-256", checkNotNull(Security.getProvider("SUN")) { "control: SUN exists" })
    private val seen = ByteArrayOutputStream()

    override fun engineGetDigestLength(): Int = 32

    override fun engineUpdate(input: Byte) {
        real.update(input)
        seen.write(input.toInt())
    }

    override fun engineUpdate(input: ByteArray, offset: Int, len: Int) {
        real.update(input, offset, len)
        seen.write(input, offset, len)
    }

    override fun engineDigest(): ByteArray {
        seen.reset()
        val arm: A8Armed? = armed.get()
        if (arm != null && arm.thread === Thread.currentThread()) {
            real.reset()
            throw arm.shape()
        }
        return real.digest()
    }

    override fun engineReset() {
        real.reset()
        seen.reset()
    }

    companion object {
        val armed: AtomicReference<A8Armed?> = AtomicReference(null)
    }
}

/** SunJCE's PBKDF2; armed for one thread, `engineGenerateSecret` records the spec and throws the shape. */
class A8ShapeKdfSpi : SecretKeyFactorySpi() {
    override fun engineGenerateSecret(keySpec: KeySpec?): SecretKey {
        lastSpec.set(keySpec as? PBEKeySpec)
        val arm: A8Armed? = armed.get()
        if (arm != null && arm.thread === Thread.currentThread()) throw arm.shape()
        return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256", checkNotNull(Security.getProvider("SunJCE")))
            .generateSecret(keySpec)
    }

    override fun engineGetKeySpec(key: SecretKey?, keySpec: Class<*>?): KeySpec = throw UnsupportedOperationException("breaker A8")

    override fun engineTranslateKey(key: SecretKey?): SecretKey = throw UnsupportedOperationException("breaker A8")

    companion object {
        val armed: AtomicReference<A8Armed?> = AtomicReference(null)
        val lastSpec: AtomicReference<PBEKeySpec?> = AtomicReference(null)
    }
}

/** A shape armed for one thread only (the digest and the key factory are asked by other threads of the JVM too). */
class A8Armed(val thread: Thread, val shape: () -> Throwable)

/** A random source whose engine writes a pattern into the buffer it is given and then throws the shape. */
internal class A8ShapeRandom(val random: SecureRandom, private val provider: A8ShapeRandomProvider) {
    fun spi(): A8ShapeRandomSpi = checkNotNull(provider.created.get()) { "control: the engine was made" }
}

internal fun a8ShapeRandom(shape: () -> Throwable): A8ShapeRandom {
    val provider = A8ShapeRandomProvider()
    val random: SecureRandom = SecureRandom.getInstance("A8Shape", provider)
    checkNotNull(provider.created.get()) { "control: the engine was made" }.shape = shape
    return A8ShapeRandom(random, provider)
}

/** Serves the algorithm `A8Shape` and remembers the engine it made. */
class A8ShapeRandomProvider : Provider("KmpToolkitBreakerA8Random", "1.0", "breaker A8: a random source that fails in a given shape") {
    val created: AtomicReference<A8ShapeRandomSpi?> = AtomicReference(null)

    init {
        putService(
            object : Service(this, "SecureRandom", "A8Shape", A8ShapeRandomSpi::class.java.name, null, null) {
                override fun newInstance(constructorParameter: Any?): Any = A8ShapeRandomSpi().also { created.set(it) }
            },
        )
    }
}

/** Writes 0x5A into the buffer, then throws [shape]. */
class A8ShapeRandomSpi : SecureRandomSpi() {
    @Volatile var shape: (() -> Throwable)? = null
    @Volatile var lastBuffer: ByteArray? = null

    override fun engineSetSeed(seed: ByteArray?) = Unit

    override fun engineNextBytes(bytes: ByteArray) {
        lastBuffer = bytes
        bytes.fill(0x5A)
        shape?.let { failure: () -> Throwable -> throw failure() }
    }

    override fun engineGenerateSeed(numBytes: Int): ByteArray = ByteArray(numBytes)
}
