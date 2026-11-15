package io.github.jamal_wia.kmptoolkit.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Breaker round 1 of group sto-api (STO-3, ticket release-5-1-0): typed reads attacked with garbage,
 * overflow, non-ASCII digits, whitespace, huge input and unpaired surrogates, and the errors they produce
 * checked for a copy of the stored value.
 *
 * Contract: `TypedAccess.kt` file KDoc (one encoding per type; anything else is
 * [StorageError.InvalidValue] naming the key), [StorageError.InvalidValue] ("never retry"), and the
 * [SecureKeyValueStorage] KDoc ("the values are the secrets", key names are not). A typed read of an
 * encrypted store that copies the decrypted value into its error hands the secret to whatever logs the
 * error, which is the reaction the error table asks for.
 */
class TypedAccessBreakerR1Test {

    /** An encrypted store as shared code sees it; the values it holds are secrets. */
    private class SecretStore(
        val inner: FakeKeyValueStorage = FakeKeyValueStorage(),
    ) : SecureKeyValueStorage, KeyValueStorage by inner {
        override val protection: SecureStorageProtection = SecureStorageProtection.OS_KEY_STORE_APP_ISOLATED

        override suspend fun ensureKey(): StorageResult<Unit> = StorageResult.Success(Unit)
    }

    /** Every text an error carries: its own toString and each message and toString down the cause chain. */
    private fun texts(error: StorageError): List<String> {
        val out: MutableList<String> = mutableListOf(error.toString())
        var current: Throwable? = (error as? StorageError.InvalidValue)?.cause
        var depth = 0
        while (current != null && depth < 16) {
            out += current.toString()
            current.message?.let { out += it }
            current = current.cause
            depth++
        }
        return out
    }

    private fun assertNoCopyOf(secret: String, result: StorageResult<*>, what: String) {
        val error: StorageError.InvalidValue = assertIs(result.errorOrNull(), "$what answered $result")
        val leaking: List<String> = texts(error).filter { it.contains(secret) }
        assertTrue(
            leaking.isEmpty(),
            "$what: the error carries the stored secret value in ${leaking.size} text(s), first: ${leaking.firstOrNull()}",
        )
    }

    // -- secret value copied into the error (security) ---------------------------------------------

    @Test
    fun `a typed read of a non-numeric secret never copies the secret into the error`() = runTest {
        val secret = "s3cr3t-refresh-token-abc"
        val store = SecretStore()
        store.inner.entries["token"] = secret

        assertNoCopyOf(secret, store.getInt("token"), "getInt")
        assertNoCopyOf(secret, store.getLong("token"), "getLong")
        assertNoCopyOf(secret, store.getBoolean("token"), "getBoolean")
        assertEquals(StorageResult.Success(secret), store.get("token"), "the value itself is left alone")
    }

    @Test
    fun `a typed read of an all-digit secret that overflows never copies the secret into the error`() = runTest {
        // A PIN or an account number is digits only, passes the decimal check and then overflows: the
        // platform's NumberFormatException names its input.
        val intPin = "9182736455"
        val longPin = "91827364554637281900"
        val store = SecretStore()
        store.inner.entries["pin"] = intPin
        store.inner.entries["account"] = longPin

        assertNoCopyOf(intPin, store.getInt("pin"), "getInt of a 10-digit PIN")
        assertNoCopyOf(longPin, store.getInt("account"), "getInt of a 20-digit number")
        assertNoCopyOf(longPin, store.getLong("account"), "getLong of a 20-digit number")
    }

    // -- boundaries (expected to hold) ---------------------------------------------------------------

    @Test
    fun `values just outside the int and long ranges are InvalidValue and just inside are read`() = runTest {
        val store = FakeKeyValueStorage()
        store.entries["intMaxPlus"] = "2147483648"
        store.entries["intMinMinus"] = "-2147483649"
        store.entries["longMaxPlus"] = "9223372036854775808"
        store.entries["longMinMinus"] = "-9223372036854775809"
        store.entries["intMax"] = "2147483647"
        store.entries["longMin"] = "-9223372036854775808"

        assertIs<StorageError.InvalidValue>(store.getInt("intMaxPlus").errorOrNull())
        assertIs<StorageError.InvalidValue>(store.getInt("intMinMinus").errorOrNull())
        assertEquals(StorageResult.Success(2_147_483_648L), store.getLong("intMaxPlus"))
        assertEquals(StorageResult.Success(-2_147_483_649L), store.getLong("intMinMinus"))
        assertIs<StorageError.InvalidValue>(store.getLong("longMaxPlus").errorOrNull())
        assertIs<StorageError.InvalidValue>(store.getLong("longMinMinus").errorOrNull())
        assertEquals(StorageResult.Success(Int.MAX_VALUE), store.getInt("intMax"))
        assertEquals(StorageResult.Success(Long.MIN_VALUE), store.getLong("longMin"))
    }

    @Test
    fun `text that is not exactly the written form of a number is InvalidValue for every typed read`() = runTest {
        val garbage: List<String> = listOf(
            "", "-", "--1", "+0", "1-", " 1", "1 ", "\t1", "1\n", "1\r\n", "1_000", "1,000", "1.0", "1e3", "0x1F",
            "NaN", "Infinity", "-Infinity", "١٢", "１２", "१", "1\u0000", " 1", "​1",
            "\uD800", "1\uDC00", "\uDC00\uD800",
        )
        val store = FakeKeyValueStorage()
        garbage.forEachIndexed { index: Int, text: String -> store.entries["g$index"] = text }

        garbage.forEachIndexed { index: Int, text: String ->
            val key = "g$index"
            for ((name: String, result: StorageResult<Any?>) in listOf("getInt" to store.getInt(key), "getLong" to store.getLong(key))) {
                val error: StorageError? = result.errorOrNull()
                assertTrue(
                    error is StorageError.InvalidValue && error.key == key,
                    "$name of stored text #$index (${text.length} chars) answered $result",
                )
            }
            assertEquals(text, store.entries[key], "text #$index changed by a read")
        }
    }

    @Test
    fun `anything but exactly true or false is InvalidValue for getBoolean`() = runTest {
        val garbage: List<String> = listOf("", "TRUE", "True", "FALSE", "1", "0", "yes", " true", "true ", "true\n", "t", "\uD800")
        val store = FakeKeyValueStorage()
        garbage.forEachIndexed { index: Int, text: String -> store.entries["b$index"] = text }

        garbage.forEachIndexed { index: Int, _: String ->
            val result: StorageResult<Boolean?> = store.getBoolean("b$index")
            val error: StorageError? = result.errorOrNull()
            assertTrue(error is StorageError.InvalidValue && error.key == "b$index", "getBoolean of text #$index answered $result")
        }
    }

    @Test
    fun `leading zeros and minus zero are the decimal form and read as their number`() = runTest {
        // Documented as "an optional minus and ASCII digits"; this pins that wording rather than a stricter one.
        val store = FakeKeyValueStorage()
        store.entries["zeros"] = "007"
        store.entries["minusZero"] = "-0"

        assertEquals(StorageResult.Success(7), store.getInt("zeros"))
        assertEquals(StorageResult.Success(0L), store.getLong("minusZero"))
    }

    @Test
    fun `a million-digit value and a million-character garbage value are InvalidValue without a crash`() = runTest {
        // A regex engine that recurses per repetition would overflow its stack here (Kotlin/Native too).
        val store = FakeKeyValueStorage()
        store.entries["digits"] = "9".repeat(1_000_000)
        store.entries["minusDigits"] = "-" + "1".repeat(1_000_000)
        store.entries["garbage"] = "9".repeat(999_999) + "x"

        for (key: String in listOf("digits", "minusDigits", "garbage")) {
            assertIs<StorageError.InvalidValue>(store.getInt(key).errorOrNull(), "getInt($key)")
            assertIs<StorageError.InvalidValue>(store.getLong(key).errorOrNull(), "getLong($key)")
            assertIs<StorageError.InvalidValue>(store.getBoolean(key).errorOrNull(), "getBoolean($key)")
        }
    }

    @Test
    fun `typed reads under keys with unpaired surrogates are absent rather than a crash`() = runTest {
        val store = FakeKeyValueStorage()

        for (key: String in listOf("\uD800", "a\uDC00", "\uDC00\uD800", "")) {
            assertEquals(StorageResult.Success(null), store.getInt(key), "getInt of key ${key.length} chars")
            assertEquals(StorageResult.Success(null), store.getLong(key))
            assertEquals(StorageResult.Success(null), store.getBoolean(key))
        }
    }

    @Test
    fun `the or-variants never throw on any garbage and fall back to the default`() = runTest {
        val store = FakeKeyValueStorage()
        store.entries["k"] = "9".repeat(40) + "\uD800"

        assertEquals(-1, store.getIntOr("k", -1))
        assertEquals(-1L, store.getLongOr("k", -1L))
        assertEquals(true, store.getBooleanOr("k", true))
    }
}
