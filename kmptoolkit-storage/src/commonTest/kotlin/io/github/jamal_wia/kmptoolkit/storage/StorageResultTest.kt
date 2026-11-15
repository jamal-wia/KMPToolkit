package io.github.jamal_wia.kmptoolkit.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class StorageResultTest {

    @Test
    fun `getOrNull returns the value of a success`() {
        assertEquals("v", StorageResult.Success("v").getOrNull())
    }

    @Test
    fun `getOrNull returns null for a failure`() {
        assertNull(StorageResult.Failure(StorageError.Unavailable()).getOrNull())
    }

    @Test
    fun `a successful absent read is distinguishable from a failed read`() {
        // Both give null through getOrNull; only the result itself tells them apart, which is the
        // whole reason get() does not simply return String?.
        val absent: StorageResult<String?> = StorageResult.Success(null)
        val failed: StorageResult<String?> = StorageResult.Failure(StorageError.Undecryptable("k"))

        assertNull(absent.getOrNull())
        assertNull(failed.getOrNull())
        assertTrue(absent.isSuccess)
        assertFalse(failed.isSuccess)
    }

    @Test
    fun `errorOrNull returns the error of a failure`() {
        val error = StorageError.Undecryptable("k")

        assertEquals(error, StorageResult.Failure(error).errorOrNull())
    }

    @Test
    fun `errorOrNull returns null for a success`() {
        assertNull(StorageResult.Success("v").errorOrNull())
    }

    @Test
    fun `getStringOrNull returns a stored value`() = runTest {
        val storage = FakeKeyValueStorage()
        storage.put("k", "v")

        assertEquals("v", storage.getStringOrNull("k"))
    }

    @Test
    fun `getStringOrNull returns null for a key that was never written`() = runTest {
        assertNull(FakeKeyValueStorage().getStringOrNull("missing"))
    }

    @Test
    fun `getStringOrNull collapses a failed read into null`() = runTest {
        val storage = FakeKeyValueStorage()
        storage.entries["k"] = "v"
        storage.failReadsWith = StorageError.Undecryptable("k")

        assertNull(storage.getStringOrNull("k"))
    }

    @Test
    fun `getStringOrNull preserves an empty value rather than reporting it absent`() = runTest {
        val storage = FakeKeyValueStorage()
        storage.put("k", "")

        assertEquals("", storage.getStringOrNull("k"))
    }

    // -- D-26: the text of a result is safe to log -------------------------------------------------------

    @Test
    fun `the text of a success never contains the held value`() {
        val secret = "s3cr3t-token-Qw71"

        val text: String = StorageResult.Success(secret).toString()

        assertFalse(text.contains(secret), "Success.toString() printed the held value: $text")
        assertTrue(text.contains("Success"), "Success.toString() no longer names the result: $text")
        assertEquals(text, StorageResult.Success("another").toString(), "the text must not depend on the value")
    }

    @Test
    fun `the text of a typed read never contains the stored number or flag`() = runTest {
        val storage = FakeKeyValueStorage()
        storage.entries["pin"] = "918273"
        storage.entries["account"] = "9182736455463728190"

        val texts: List<String> = listOf(
            storage.getInt("pin").toString(),
            storage.getLong("account").toString(),
        )

        for (text in texts) {
            assertFalse(text.contains("918273"), "a typed read printed the stored number: $text")
            assertTrue(text.contains("Success"), "a typed read no longer names the result: $text")
        }
    }

    @Test
    fun `a failed typed read prints its error and never the stored value`() = runTest {
        val storage = FakeKeyValueStorage()
        storage.entries["pin"] = "s3cr3t-not-a-number"

        val failure: StorageResult<Int?> = storage.getInt("pin")
        val text: String = failure.toString()

        assertTrue(text.contains("InvalidValue"), "the failure lost its error: $text")
        assertTrue(text.contains("pin"), "the failure lost its key: $text")
        assertFalse(text.contains("s3cr3t"), "the failure printed the stored value: $text")
    }

    @Test
    fun `a put refused for its value prints the key and never the value`() {
        val failure: StorageResult.Failure? = unpairedSurrogateFailure("token", "s3cr3t-value\uD800")

        val text: String = failure.toString()

        assertTrue(text.contains("InvalidValue") && text.contains("token"), "the failure lost its error or key: $text")
        assertFalse(text.contains("s3cr3t"), "the refused value reached the failure text: $text")
    }

    @Test
    fun `redacting the text leaves equality hashing and copy on the held value`() {
        val a: StorageResult.Success<String> = StorageResult.Success("one")

        assertEquals(StorageResult.Success("one"), a)
        assertEquals(StorageResult.Success("one").hashCode(), a.hashCode())
        assertNotEquals(StorageResult.Success("two"), a, "two successes with equal text still differ by value")
        assertEquals("two", a.copy(value = "two").value)
    }
}
