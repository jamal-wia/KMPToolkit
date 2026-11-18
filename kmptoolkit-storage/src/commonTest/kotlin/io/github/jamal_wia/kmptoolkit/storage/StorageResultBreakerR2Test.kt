package io.github.jamal_wia.kmptoolkit.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Breaker round 2 of group sto-api (STO-2/STO-3, ticket release-5-1-0), decision D-26: the text of a
 * [StorageResult.Success] names the result type and never the value it holds, because a secret read from
 * an encrypted store must not reach a log line just because the whole result was logged; the data-class
 * contract (equals, hashCode, componentN, copy) stays as it is and [StorageResult.Failure] keeps printing
 * its error (an error is documented value-free, see [StorageError.InvalidValue]).
 *
 * Every text path is attacked: the result's own `toString`, a string template, a collection, a nested
 * `Success`, a `kotlin.Result`, a `Pair`, and the message of a failed `kotlin.test` assertion; and the
 * redaction must not call the value's own `toString` (a value whose `toString` throws or copies the secret
 * must not be touched) nor reveal the value's length.
 */
class StorageResultBreakerR2Test {

    private val secret: String = "s3cr3t-refresh-token-Zq81"

    private fun assertRedacted(text: String, what: String, vararg secrets: String = arrayOf(secret)) {
        for (each in secrets) {
            assertFalse(text.contains(each), "$what prints the held value: $text")
        }
        assertTrue(text.contains("Success"), "$what no longer names the result type: $text")
    }

    /** A value whose own text is the secret, so any path that formats the value leaks it. */
    private class Credential(val token: String) {
        override fun toString(): String = "Credential(token=$token)"
    }

    /** A value whose `toString` must never be called by the redaction. */
    private class Untouchable {
        override fun toString(): String = throw IllegalStateException("the redaction formatted the held value")
    }

    // -- D-26: the held value never reaches the text (RED until D-26 is implemented) ------------------

    @Test
    fun `the text of a success of a secret string names the type and not the value`() {
        val result: StorageResult<String?> = StorageResult.Success(secret)

        assertRedacted(result.toString(), "Success<String>.toString()")
        assertRedacted("read: $result", "a string template")
        assertRedacted(buildString { append(result) }, "StringBuilder.append")
    }

    @Test
    fun `the text of a success of a typed read of a PIN names the type and not the number`() = runTest {
        val store = FakeKeyValueStorage()
        store.entries["pin"] = "918273"
        store.entries["account"] = "9182736455463728190"
        store.entries["biometric"] = "true"

        val pin: StorageResult<Int?> = store.getInt("pin")
        val account: StorageResult<Long?> = store.getLong("account")

        assertEquals(StorageResult.Success(918273), pin, "control: the PIN parsed")
        assertRedacted(pin.toString(), "getInt result", "918273")
        assertRedacted(account.toString(), "getLong result", "9182736455463728190")
        assertTrue(store.getBoolean("biometric").toString().contains("Success"), "a Boolean success still names its type")
    }

    @Test
    fun `the text of a success of an object never formats the object`() {
        val credential: StorageResult<Credential> = StorageResult.Success(Credential(secret))
        val untouchable: StorageResult<Untouchable> = StorageResult.Success(Untouchable())

        assertRedacted(credential.toString(), "Success<Credential>.toString()")
        // The redaction itself must not call the value's toString: here that would throw.
        val text: String = untouchable.toString()
        assertTrue(text.contains("Success"), "Success<Untouchable>.toString() no longer names the type: $text")
    }

    @Test
    fun `a success nested in a collection a pair a result or another success stays redacted`() {
        val inner: StorageResult<String?> = StorageResult.Success(secret)

        assertRedacted(listOf(inner).toString(), "List<Success>.toString()")
        assertRedacted(mapOf("token" to inner).toString(), "Map<String, Success>.toString()")
        assertRedacted(Pair("token", inner).toString(), "Pair.toString()")
        assertRedacted(Result.success(inner).toString(), "kotlin.Result.success(Success).toString()")
        assertRedacted(StorageResult.Success(inner).toString(), "Success(Success).toString()")
        assertRedacted(StorageResult.Success(listOf(secret)).toString(), "Success(List).toString()")
    }

    @Test
    fun `the message of a failed assertion over a success does not carry the secret`() {
        val actual: StorageResult<String?> = StorageResult.Success(secret)

        val failure: AssertionError = assertFailsWith<AssertionError> {
            assertEquals(StorageResult.Success("expected-other-value"), actual)
        }

        val message: String = failure.message.orEmpty()
        assertFalse(message.contains(secret), "the assertion message carries the secret: $message")
    }

    @Test
    fun `the text does not reveal the length of the held value`() {
        // The length of a PIN or a token is itself information about the secret (the reasoning of the
        // InvalidValue fix of round 1): two strings of different length print the same.
        val short: String = StorageResult.Success("1").toString()
        val long: String = StorageResult.Success("1".repeat(64)).toString()

        assertEquals(short, long, "the text of a success depends on the held value")
    }

    @Test
    fun `an absent read and a unit write still print as a success`() {
        assertTrue(StorageResult.Success(null).toString().contains("Success"))
        assertTrue(StorageResult.Success(Unit).toString().contains("Success"))
    }

    // -- D-26: the data-class contract is unchanged (must stay GREEN) -------------------------------

    @Test
    fun `equality hashCode componentN and copy still use the held value`() {
        val a: StorageResult.Success<String> = StorageResult.Success(secret)
        val same: StorageResult.Success<String> = StorageResult.Success(secret)
        val other: StorageResult.Success<String> = StorageResult.Success("another-secret")

        assertEquals(a, same)
        assertEquals(a.hashCode(), same.hashCode())
        assertNotEquals(a, other, "two successes that print alike must still differ by value")
        assertEquals<StorageResult<String?>>(StorageResult.Success(null), StorageResult.Success(null))
        assertNotEquals<StorageResult<String?>>(StorageResult.Success(null), StorageResult.Success(""))

        val (value: String) = a
        assertEquals(secret, value)
        assertEquals(secret, a.component1())
        assertEquals(other, a.copy(value = "another-secret"))
        assertEquals(a, a.copy())
        assertEquals(setOf(a), setOf(same), "hash-based collections still see one entry")
    }

    @Test
    fun `a failure still prints its error so a logged failure stays useful`() {
        val failure = StorageResult.Failure(StorageError.InvalidValue("token", IllegalArgumentException("The stored value is not a valid Int")))
        val text: String = failure.toString()

        assertTrue(text.contains("InvalidValue"), "Failure.toString() lost its error: $text")
        assertTrue(text.contains("token"), "Failure.toString() lost the key: $text")
        assertTrue(text.contains("not a valid Int"), "Failure.toString() lost the cause: $text")
        assertTrue(
            StorageResult.Failure(StorageError.OperationFailed(StorageOperation.ENSURE_KEY, platformCode = -25300))
                .toString().contains("-25300"),
            "Failure.toString() lost the platform code",
        )
    }
}
