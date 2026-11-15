package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The one typed failure of the JCA boundary: a security provider (or the random source) of this JVM could not do what the
 * storage module asked, in whatever shape it reported that. It is an `IllegalStateException` so that every existing guard
 * (`EncryptedFileGuard.shielded`) answers it as `OperationFailed(operation, key, cause)`, and it is never a verdict about the
 * user's data: nothing here ever means "wrong key", "tampered" or "key lost" (see [Jca.aesGcmOpen] for the one place that decides that).
 */
internal class JcaFailure(message: String, cause: Throwable) : IllegalStateException(message, cause)

/** The random source type of the storage module, so that nothing outside [Jca] names `java.security`. */
internal typealias JcaRandom = SecureRandom

/**
 * THE JCA boundary of `kmptoolkit-storage` (decisions D-P12): every `Cipher`, `SecretKeyFactory`, `SecureRandom`,
 * `MessageDigest` and `Base64` use of the module's JVM code goes through this object, and nothing else imports `java.security`,
 * `javax.crypto` or `java.util.Base64` (`JcaBytecodeScanTest`, on the compiled classes, and `JcaBoundaryScanTest`, on the sources, fail
 * when a class of the desktop code does; the Android store's Keystore code is out of their scope; this object has no nested class, and
 * a class named `Jca$...` fails the bytecode scan whatever file it comes from, so a lambda or local class added here needs a deliberate
 * rule in `ClassFileScan` first). Three rules live
 * here and nowhere else, which is the point of the boundary:
 *
 * 1. **A provider failure is one typed failure.** Whatever a JCA engine throws while it works, short of an `Error` (a
 *    `GeneralSecurityException` such as no such algorithm, a `ProviderException` from a PKCS#11 token or an unreadable entropy
 *    source, an `UnsupportedOperationException` from an engine without AAD support, plain noise such as a `NullPointerException`
 *    from a broken engine, or a `Throwable` that is neither an `Exception` nor an `Error`) leaves as a [JcaFailure] with the
 *    original as the cause. The JCA specifies different exceptions for different engines and every audit round of 6.0.0 found one
 *    more shape; catching every `Throwable` of the provider call but an `Error` closes the class. Only the provider call itself is
 *    inside the catch: the callers' own argument checks stay outside and keep their types. An `InterruptedException` is typed like
 *    the rest and the thread's interrupt status is restored, so the code that runs on that thread sees it: a direct caller keeps the
 *    status. Inside a store call it lasts only until the call's work on that thread ends: `InterruptHygiene` clears a status that
 *    this boundary put back (never one that was set when the call reached the thread, never one that appeared otherwise), at the
 *    end of the call's block and at every suspension, because the dispatcher's thread is not the library's and kotlinx.coroutines
 *    does not clear a worker's status between two tasks, so a status left behind would refuse the file I/O of the next, unrelated
 *    coroutine on that worker, or of a caller that kotlinx.coroutines resumes inline on that worker (`Dispatchers.Unconfined`). The
 *    interrupt stays visible as the cause of the typed failure, and the caller's own code after the call does not see the status,
 *    on whichever thread it resumes (unless its thread was already interrupted when the call reached it). Only an `InterruptedException` that
 *    the provider call throws itself is an interrupt: one the JDK wraps (an SPI constructor that throws during `getInstance` leaves
 *    as a `NoSuchAlgorithmException` with it as the cause) or that another exception carries as its cause is NOT looked for, because
 *    a cause may come from another thread and an invented interrupt does more harm than one hidden by a provider constructor that
 *    blocks interruptibly (pinned by `JcaBoundaryRound11Test`).
 * 2. **"Wrong key or tampered" is decided in one place** ([aesGcmOpen]), and only a GCM tag that does not verify says it. Nothing a
 *    provider fails with in any other way can read as lost data (that verdict makes an app delete things).
 * 3. **An `Error` is never typed.** `OutOfMemoryError`, `StackOverflowError` and `InternalError` are `VirtualMachineError`s; after
 *    one the JVM's state is not the library's to judge, so the boundary does not catch them, and neither does anything above it.
 *    The one that matters here is the JDK's DRBG `SecureRandom` (the default on Windows and wherever `securerandom.source` is not
 *    exactly `/dev/random` or `/dev/urandom`): a seed source it cannot read is an `InternalError`, which leaves the store call as
 *    that `Error`. Nothing is created or held when it does (the call's own `finally` blocks run; pinned by a test).
 *
 * Stateless; safe from any thread. Key arguments are borrowed: never kept and never wiped here (the JDK's own copies, such as the
 * `SecretKeySpec`'s, cannot be wiped from outside the JDK).
 */
internal object Jca {
    private const val AES_GCM: String = "AES/GCM/NoPadding"
    private const val SHA_256: String = "SHA-256"
    private const val PBKDF2_HMAC_SHA256: String = "PBKDF2WithHmacSHA256"

    /**
     * Runs one provider call; whatever it throws, except an `Error`, becomes a [JcaFailure] naming [what] (never a message from the
     * provider). That is every `Throwable` that is not an `Error`, not only every `Exception`: Kotlin has no checked exceptions, so
     * a provider written in Kotlin may throw any `Throwable` subclass, and a Java one can sneaky-throw one.
     */
    private inline fun <T> provider(what: String, block: () -> T): T =
        try {
            block()
        } catch (failure: JcaFailure) {
            throw failure
        } catch (error: Throwable) {
            if (error is Error) throw error
            // A provider that was interrupted (a PKCS#11 token call, a blocking entropy read) says so by throwing InterruptedException,
            // which clears the thread's status: the status is put back, so a caller that polls it still sees the interrupt.
            if (error is InterruptedException) {
                Thread.currentThread().interrupt()
                // Inside a store call the status lasts only until the call's work on this thread ends (InterruptHygiene).
                InterruptHygiene.noteRestored()
            }
            throw JcaFailure("The $what provider failed", error)
        }

    /** The platform's default `SecureRandom`. A provider set that cannot make one is a [JcaFailure]. */
    fun newRandom(): JcaRandom = provider("random source") { SecureRandom() }

    /**
     * [size] random bytes from [random]. A source that fails is a [JcaFailure], and an `Error` leaves as itself; either way
     * whatever the source had written is wiped (wiping is not catching: rule 3 holds).
     */
    fun randomBytes(random: JcaRandom, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var drawn = false
        try {
            provider("random source") { random.nextBytes(bytes) }
            drawn = true
        } finally {
            if (!drawn) bytes.fill(0)
        }
        return bytes
    }

    /** SHA-256 of [input]; exact SHA-256 whichever provider serves it (the digests address OS items and file names). A failing provider is a [JcaFailure]. */
    fun sha256(input: ByteArray): ByteArray = provider(SHA_256) { MessageDigest.getInstance(SHA_256).digest(input) }

    /**
     * AES-GCM encryption with a given 96-bit [nonce]: the ciphertext followed by the tag. The sizes of [key] and [nonce] are the
     * caller's to check. Any provider failure is a [JcaFailure].
     */
    fun aesGcmSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, tagBits: Int, plaintext: ByteArray): ByteArray =
        provider("AES-GCM") {
            val cipher: Cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(tagBits, nonce))
            cipher.updateAAD(aad)
            cipher.doFinal(plaintext)
        }

    /**
     * The plaintext of [sealed], or `null` ONLY when the GCM tag does not verify: the single place in the module that decides
     * "wrong key or tampered". Every other outcome is a [JcaFailure].
     *
     * The JDK's own mismatch is an `AEADBadTagException` with no cause (`GaloisCounterMode`: "Tag mismatch"). A PKCS#11 provider
     * (served first under a FIPS-configured JDK) reports a token error (`CKR_GENERAL_ERROR`) as an `AEADBadTagException` WITH the
     * `PKCS11Exception` as its cause, so a bad-tag exception that carries a cause is a provider failure, decided by structure and
     * never by reading text. The price, stated in the platform notes: such a provider attaches a cause to its real tag failures
     * too, so under it a wrong key or a tampered file also answers `OperationFailed`. That is the safe direction: an environment
     * problem is never allowed to delete data.
     */
    fun aesGcmOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, tagBits: Int, sealed: ByteArray): ByteArray? =
        provider("AES-GCM") {
            try {
                val cipher: Cipher = Cipher.getInstance(AES_GCM)
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(tagBits, nonce))
                cipher.updateAAD(aad)
                cipher.doFinal(sealed)
            } catch (mismatch: AEADBadTagException) {
                if (mismatch.cause != null) throw mismatch
                null
            }
        }

    /**
     * Raw PBKDF2-HMAC-SHA256 of [password]: [lengthBytes] bytes. The spec is made outside the provider call, so a bad parameter
     * keeps its own `IllegalArgumentException`; the spec's copy of the password is wiped on every way out. Any provider failure
     * is a [JcaFailure].
     */
    fun pbkdf2Sha256(password: CharArray, salt: ByteArray, iterations: Int, lengthBytes: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, lengthBytes * 8)
        try {
            return provider("PBKDF2") {
                SecretKeyFactory.getInstance(PBKDF2_HMAC_SHA256).generateSecret(spec).encoded
                    ?: throw IllegalStateException("The derived key has no encoding")
            }
        } finally {
            spec.clearPassword()
        }
    }

    /** The base64 text of [bytes] as bytes. The caller owns and wipes the result. */
    fun base64Encode(bytes: ByteArray): ByteArray = Base64.getEncoder().encode(bytes)

    /**
     * The [size] bytes that the base64 text [text] decodes to, or `null` when it is not valid base64 or does not decode to exactly
     * [size] bytes. The decoded bytes are written into a buffer of this function's own that is wiped on every way out, so no
     * decoded key is ever left in an array nobody can wipe (the JDK's one-argument `decode` allocates its own and leaves it behind
     * when the input turns out malformed). The result is owned by the caller, who wipes it.
     */
    fun base64DecodeExactly(text: ByteArray, size: Int): ByteArray? {
        val buffer = ByteArray((text.size + 3) / 4 * 3)
        try {
            val written: Int = try {
                Base64.getDecoder().decode(text, buffer)
            } catch (_: IllegalArgumentException) {
                return null
            }
            return if (written == size) buffer.copyOf(size) else null
        } finally {
            buffer.fill(0)
        }
    }
}
