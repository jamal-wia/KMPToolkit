package io.github.jamal_wia.kmptoolkit.storage

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail
import kotlinx.coroutines.test.runTest

/**
 * Breaker round 3 of group sto-api (STO-2/STO-3, ticket release-5-1-0): the typed accessors over a seeded
 * sweep of the whole Int and Long ranges and their one-past-the-end neighbours, cross-type reads, and
 * valid-but-odd decimal forms (very long runs of leading zeros) on every target, Kotlin/Native included.
 *
 * Contract (TypedAccess.kt, 04-api-reference.md): `Int`/`Long` are stored as their decimal string, an
 * optional `-` and ASCII digits; a value that is not of the requested type is [StorageError.InvalidValue]
 * naming the key; what `put` wrote reads back unchanged, also through the other integer type where it fits.
 */
class TypedAccessBreakerR3Test {

    /** The decimal string of `|text| + 1` for a non-negative decimal [digits]: no big-integer type needed. */
    private fun incrementDigits(digits: String): String {
        val chars: CharArray = digits.toCharArray()
        var index: Int = chars.lastIndex
        while (index >= 0) {
            if (chars[index] == '9') {
                chars[index] = '0'
                index--
            } else {
                chars[index] = chars[index] + 1
                return chars.concatToString()
            }
        }
        return "1" + chars.concatToString()
    }

    /** The decimal string one step further from zero than [value]'s own: past the end for an extreme. */
    private fun outward(value: String): String =
        if (value.startsWith("-")) "-" + incrementDigits(value.drop(1)) else incrementDigits(value)

    @Test
    fun `a seeded sweep of ints and longs reads back exactly and one step past each extreme is InvalidValue`() = runTest {
        val seed = 3301
        val random = Random(seed)
        val store = FakeKeyValueStorage()
        val ints: List<Int> = listOf(Int.MIN_VALUE, Int.MIN_VALUE + 1, -1, 0, 1, Int.MAX_VALUE - 1, Int.MAX_VALUE) +
            List(2_000) { random.nextInt() }
        val longs: List<Long> = listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, -1L, 0L, 1L, Long.MAX_VALUE - 1, Long.MAX_VALUE) +
            List(2_000) { random.nextLong() }

        for (value: Int in ints) {
            assertEquals(StorageResult.Success(Unit), store.putInt("i", value), "seed=$seed putInt($value)")
            assertEquals(StorageResult.Success<Int?>(value), store.getInt("i"), "seed=$seed getInt after putInt($value)")
            assertEquals(StorageResult.Success<Long?>(value.toLong()), store.getLong("i"), "seed=$seed getLong after putInt($value)")
        }
        for (value: Long in longs) {
            assertEquals(StorageResult.Success(Unit), store.putLong("l", value), "seed=$seed putLong($value)")
            assertEquals(StorageResult.Success<Long?>(value), store.getLong("l"), "seed=$seed getLong after putLong($value)")
            val asInt: StorageResult<Int?> = store.getInt("l")
            if (value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
                assertEquals(StorageResult.Success<Int?>(value.toInt()), asInt, "seed=$seed getInt of a long that fits ($value)")
            } else {
                val error: StorageError = (asInt as? StorageResult.Failure)?.error
                    ?: fail("seed=$seed getInt of $value, which does not fit, answered $asInt")
                assertEquals(StorageError.InvalidValue::class, error::class, "seed=$seed getInt of $value")
                assertEquals("l", (error as StorageError.InvalidValue).key, "seed=$seed")
            }
        }
        for (past: String in listOf(outward(Int.MIN_VALUE.toString()), outward(Int.MAX_VALUE.toString()))) {
            store.entries["p"] = past
            assertIs<StorageError.InvalidValue>(store.getInt("p").errorOrNull(), "seed=$seed getInt($past)")
        }
        for (past: String in listOf(outward(Long.MIN_VALUE.toString()), outward(Long.MAX_VALUE.toString()))) {
            store.entries["p"] = past
            assertIs<StorageError.InvalidValue>(store.getLong("p").errorOrNull(), "seed=$seed getLong($past)")
        }
        assertEquals("9223372036854775808", outward(Long.MAX_VALUE.toString()), "the oracle itself is wrong")
    }

    @Test
    fun `very long runs of leading zeros are the decimal form and read as their number`() = runTest {
        val store = FakeKeyValueStorage()
        val zeros: String = "0".repeat(100_000)
        store.entries["i"] = zeros + "42"
        store.entries["n"] = "-" + zeros + Int.MAX_VALUE
        store.entries["l"] = zeros + Long.MAX_VALUE
        store.entries["z"] = "-$zeros"
        store.entries["over"] = zeros + outward(Long.MAX_VALUE.toString())

        assertEquals(StorageResult.Success<Int?>(42), store.getInt("i"))
        assertEquals(StorageResult.Success<Int?>(-Int.MAX_VALUE), store.getInt("n"))
        assertEquals(StorageResult.Success<Long?>(Long.MAX_VALUE), store.getLong("l"))
        assertEquals(StorageResult.Success<Long?>(0L), store.getLong("z"))
        assertIs<StorageError.InvalidValue>(store.getLong("over").errorOrNull())
        assertEquals(zeros + "42", store.entries["i"], "a typed read changed the stored text")
    }
}
