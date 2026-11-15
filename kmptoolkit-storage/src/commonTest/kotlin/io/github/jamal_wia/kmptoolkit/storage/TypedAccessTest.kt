package io.github.jamal_wia.kmptoolkit.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

/**
 * The typed extensions' encoding and failure rules, from the KDoc in `TypedAccess.kt`: one encoding
 * per type, a value that does not parse is an InvalidValue failure rather than an absent key, and the `...Or`
 * variants fall back to the default on absent, unreadable and unparseable alike.
 */
class TypedAccessTest {

    private val storage = FakeKeyValueStorage()

    @Test
    fun `typed values are stored as their canonical strings`() = runTest {
        storage.putInt("i", -42)
        storage.putLong("l", Long.MAX_VALUE)
        storage.putBoolean("b", true)

        assertEquals("-42", storage.entries["i"])
        assertEquals("9223372036854775807", storage.entries["l"])
        assertEquals("true", storage.entries["b"])
    }

    @Test
    fun `typed values round-trip at their extremes`() = runTest {
        storage.putInt("int", Int.MIN_VALUE)
        storage.putLong("long", Long.MIN_VALUE)
        storage.putBoolean("f", false)

        assertEquals(StorageResult.Success(Int.MIN_VALUE), storage.getInt("int"))
        assertEquals(StorageResult.Success(Long.MIN_VALUE), storage.getLong("long"))
        assertEquals(StorageResult.Success(false), storage.getBoolean("f"))
    }

    @Test
    fun `an absent key reads back as null not as a failure`() = runTest {
        assertEquals(StorageResult.Success(null), storage.getInt("missing"))
        assertEquals(StorageResult.Success(null), storage.getLong("missing"))
        assertEquals(StorageResult.Success(null), storage.getBoolean("missing"))
    }

    @Test
    fun `a value that does not parse is InvalidValue naming the key never OperationFailed`() = runTest {
        storage.entries["i"] = "forty-two"
        storage.entries["l"] = "12.5"
        storage.entries["b"] = "TRUE"
        storage.entries["plus"] = "+42"
        storage.entries["space"] = " 7"

        listOf(storage.getInt("i"), storage.getLong("l"), storage.getBoolean("b"), storage.getInt("plus"), storage.getLong("space"))
            .zip(listOf("i", "l", "b", "plus", "space"))
            .forEach { (result, key) ->
                val error = assertIs<StorageError.InvalidValue>(result.errorOrNull())
                assertEquals(key, error.key)
                assertIs<IllegalArgumentException>(error.cause)
            }
    }

    @Test
    fun `an int read of a value beyond the int range is InvalidValue`() = runTest {
        storage.putLong("big", Int.MAX_VALUE.toLong() + 1)

        assertEquals("big", assertIs<StorageError.InvalidValue>(storage.getInt("big").errorOrNull()).key)
    }

    @Test
    fun `a store failure passes through unchanged`() = runTest {
        val unavailable = StorageError.Unavailable()
        storage.failReadsWith = unavailable

        assertEquals(StorageResult.Failure(unavailable), storage.getInt("k"))
    }

    @Test
    fun `the or-variants fall back on absent and unparseable and unreadable values`() = runTest {
        assertEquals(7, storage.getIntOr("missing", 7))

        storage.entries["bad"] = "x"
        assertEquals(7L, storage.getLongOr("bad", 7L))
        assertEquals(true, storage.getBooleanOr("bad", true))

        storage.failReadsWith = StorageError.Unavailable()
        assertEquals("fallback", storage.getStringOr("k", "fallback"))
    }

    @Test
    fun `the or-variants return a stored value including an empty string`() = runTest {
        storage.put("s", "")
        storage.putInt("i", 3)

        assertEquals("", storage.getStringOr("s", "fallback"))
        assertEquals(3, storage.getIntOr("i", 7))
    }

    @Test
    fun `the InvalidValue cause names the requested type and carries neither the value nor another exception`() = runTest {
        // One value per rejection path: not a decimal, not a boolean, a decimal the platform parse rejects.
        val notDecimal = "hunter2-secret"
        val overflowing = "99999999999999999999"
        storage.entries["word"] = notDecimal
        storage.entries["digits"] = overflowing

        val cases: List<Triple<StorageResult<*>, String, String>> = listOf(
            Triple(storage.getInt("word"), notDecimal, "Int"),
            Triple(storage.getLong("word"), notDecimal, "Long"),
            Triple(storage.getBoolean("word"), notDecimal, "Boolean"),
            Triple(storage.getInt("digits"), overflowing, "Int"),
            Triple(storage.getLong("digits"), overflowing, "Long"),
        )
        for ((result: StorageResult<*>, value: String, type: String) in cases) {
            val error: StorageError.InvalidValue = assertIs(result.errorOrNull(), "$type of a rejected value")
            val cause: Throwable = assertIs<IllegalArgumentException>(error.cause)
            assertNull(cause.cause, "$type: the platform parse exception is not chained")
            assertEquals(true, cause.message?.contains(type), "$type: the cause names the requested type: ${cause.message}")
            assertFalse(error.toString().contains(value), "$type: toString of the error holds the value: $error")
            assertFalse(cause.toString().contains(value), "$type: toString of the cause holds the value: $cause")
        }
    }
}
