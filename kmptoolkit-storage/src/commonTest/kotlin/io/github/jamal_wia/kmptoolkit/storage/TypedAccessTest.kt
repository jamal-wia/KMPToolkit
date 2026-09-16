package io.github.jamal_wia.kmptoolkit.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The typed extensions' encoding and failure rules, from the KDoc in `TypedAccess.kt`: one encoding
 * per type, a value that does not parse is a GET failure rather than an absent key, and the `...Or`
 * variants fall back to the default on absent, unreadable and unparseable alike.
 */
class TypedAccessTest {

    private val storage = FakeKeyValueStorage()

    @Test
    fun `typed values are stored as their canonical strings`() {
        storage.putInt("i", -42)
        storage.putLong("l", Long.MAX_VALUE)
        storage.putBoolean("b", true)

        assertEquals("-42", storage.entries["i"])
        assertEquals("9223372036854775807", storage.entries["l"])
        assertEquals("true", storage.entries["b"])
    }

    @Test
    fun `typed values round-trip at their extremes`() {
        storage.putInt("int", Int.MIN_VALUE)
        storage.putLong("long", Long.MIN_VALUE)
        storage.putBoolean("f", false)

        assertEquals(StorageResult.Success(Int.MIN_VALUE), storage.getInt("int"))
        assertEquals(StorageResult.Success(Long.MIN_VALUE), storage.getLong("long"))
        assertEquals(StorageResult.Success(false), storage.getBoolean("f"))
    }

    @Test
    fun `an absent key reads back as null not as a failure`() {
        assertEquals(StorageResult.Success(null), storage.getInt("missing"))
        assertEquals(StorageResult.Success(null), storage.getLong("missing"))
        assertEquals(StorageResult.Success(null), storage.getBoolean("missing"))
    }

    @Test
    fun `a value that does not parse is a get failure naming the key`() {
        storage.entries["i"] = "forty-two"
        storage.entries["l"] = "12.5"
        storage.entries["b"] = "TRUE"
        storage.entries["plus"] = "+42"
        storage.entries["space"] = " 7"

        listOf(storage.getInt("i"), storage.getLong("l"), storage.getBoolean("b"), storage.getInt("plus"), storage.getLong("space"))
            .zip(listOf("i", "l", "b", "plus", "space"))
            .forEach { (result, key) ->
                val error = assertIs<StorageError.OperationFailed>(result.errorOrNull())
                assertEquals(StorageOperation.GET, error.operation)
                assertEquals(key, error.key)
                assertIs<IllegalArgumentException>(error.cause)
            }
    }

    @Test
    fun `an int read of a value beyond the int range is a failure`() {
        storage.putLong("big", Int.MAX_VALUE.toLong() + 1)

        assertIs<StorageResult.Failure>(storage.getInt("big"))
    }

    @Test
    fun `a store failure passes through unchanged`() {
        val unavailable = StorageError.Unavailable()
        storage.failReadsWith = unavailable

        assertEquals(StorageResult.Failure(unavailable), storage.getInt("k"))
    }

    @Test
    fun `the or-variants fall back on absent and unparseable and unreadable values`() {
        assertEquals(7, storage.getIntOr("missing", 7))

        storage.entries["bad"] = "x"
        assertEquals(7L, storage.getLongOr("bad", 7L))
        assertEquals(true, storage.getBooleanOr("bad", true))

        storage.failReadsWith = StorageError.Unavailable()
        assertEquals("fallback", storage.getStringOr("k", "fallback"))
    }

    @Test
    fun `the or-variants return a stored value including an empty string`() {
        storage.put("s", "")
        storage.putInt("i", 3)

        assertEquals("", storage.getStringOr("s", "fallback"))
        assertEquals(3, storage.getIntOr("i", 7))
    }
}
