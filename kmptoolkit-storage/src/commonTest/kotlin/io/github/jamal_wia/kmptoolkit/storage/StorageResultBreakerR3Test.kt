package io.github.jamal_wia.kmptoolkit.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Breaker round 3 of group sto-api (STO-2/STO-3, ticket release-5-1-0), decision D-26 after its fix
 * (fea00f30): the text paths round 2 did not try. A result reaches a log not only through its own
 * `toString` but through an exception message built from it (`error(result)`, `check(...) { "$result" }`),
 * that exception's cause chain and stack-trace text, a consumer's own data class that holds a result,
 * arrays and sequences, a map that uses the result as a key, and a [StorageResult.Failure] produced by a typed
 * read that a consumer wraps into its own exception. The value of a success must reach none of them; the
 * failure must still print its error.
 */
class StorageResultBreakerR3Test {

    private val secret: String = "s3cr3t-pin-918273645"

    private fun assertNoSecret(text: String, what: String, vararg secrets: String = arrayOf(secret)) {
        for (each in secrets) assertFalse(text.contains(each), "$what prints the held value: $text")
    }

    /** What a consumer's session holder looks like: a data class that keeps the last read result. */
    private data class SessionState(val user: String, val token: StorageResult<String?>)

    @Test
    fun `an exception built from a success and its cause chain and stack trace carry no value`() {
        val result: StorageResult<String?> = StorageResult.Success(secret)

        val fromError: IllegalStateException = assertFailsWith { error(result) }
        val fromCheck: IllegalStateException = assertFailsWith { check(false) { "unexpected $result" } }
        val fromRequire: IllegalArgumentException = assertFailsWith { require(false) { result } }
        val wrapped = RuntimeException("load failed", IllegalStateException("inner: $result"))

        for ((what: String, throwable: Throwable) in listOf(
            "error(result)" to fromError,
            "check { result }" to fromCheck,
            "require { result }" to fromRequire,
            "a wrapper around an exception built from the result" to wrapped,
        )) {
            assertNoSecret(throwable.toString(), "$what toString")
            assertNoSecret(throwable.message.orEmpty(), "$what message")
            assertNoSecret(throwable.cause?.toString().orEmpty(), "$what cause")
            assertNoSecret(throwable.stackTraceToString(), "$what stack trace")
        }
        assertTrue(fromError.message.orEmpty().contains("Success"), "the message lost the result type: ${fromError.message}")
    }

    @Test
    fun `a consumer data class arrays sequences and map keys holding a success carry no value`() {
        val result: StorageResult<String?> = StorageResult.Success(secret)
        val typed: StorageResult<Long?> = StorageResult.Success(918273645L)

        val texts: List<Pair<String, String>> = listOf(
            "consumer data class" to SessionState("u1", result).toString(),
            "copy of the consumer data class" to SessionState("u1", result).copy(user = "u2").toString(),
            "array contentToString" to arrayOf<Any?>(result, typed).contentToString(),
            "nested array contentDeepToString" to arrayOf<Any?>(arrayOf<Any?>(result)).contentDeepToString(),
            "sequence joinToString" to sequenceOf(result, typed).joinToString(),
            "map key" to mapOf(result to 1, typed to 2).toString(),
            "StringBuilder append(Any?)" to StringBuilder().append(result as Any?).append(typed as Any?).toString(),
            "set" to setOf(result, typed).toString(),
            "triple" to Triple(result, typed, StorageResult.Success(listOf(secret))).toString(),
        )
        for ((what: String, text: String) in texts) {
            assertNoSecret(text, what, secret, "918273645")
            assertTrue(text.contains("Success"), "$what lost the result type: $text")
        }
    }

    @Test
    fun `a typed read failure wrapped by the consumer prints its key and error and never the stored value`() = runTest {
        val store = FakeKeyValueStorage()
        store.entries["pin"] = secret
        store.entries["flag"] = "TRUE-$secret"

        val failures: List<StorageResult<*>> = listOf(store.getInt("pin"), store.getLong("pin"), store.getBoolean("flag"))
        for (failure: StorageResult<*> in failures) {
            val error: StorageError = (failure as StorageResult.Failure).error
            val wrapped = IllegalStateException("could not read: $failure", error.let { (it as StorageError.InvalidValue).cause })
            assertNoSecret(failure.toString(), "typed read failure")
            assertNoSecret(wrapped.stackTraceToString(), "a wrapped typed read failure")
            assertTrue(failure.toString().contains("InvalidValue"), "the failure lost its case: $failure")
        }
    }

    @Test
    fun `a typed boolean success of a secret flag prints no value and keeps equality on it`() = runTest {
        val store = FakeKeyValueStorage()
        assertEquals(StorageResult.Success(Unit), store.putBoolean("biometric_enrolled", true))
        assertEquals(StorageResult.Success(Unit), store.putLong("account", Long.MIN_VALUE))

        val flag: StorageResult<Boolean?> = store.getBoolean("biometric_enrolled")
        val account: StorageResult<Long?> = store.getLong("account")

        assertEquals(StorageResult.Success<Boolean?>(true), flag)
        assertEquals(StorageResult.Success<Long?>(Long.MIN_VALUE), account)
        assertNoSecret(flag.toString(), "getBoolean success", "true")
        assertNoSecret(account.toString(), "getLong success", Long.MIN_VALUE.toString(), "9223372036854775808")
        assertEquals(flag.toString(), StorageResult.Success<Boolean?>(false).toString(), "the text tells true from false")
    }
}
