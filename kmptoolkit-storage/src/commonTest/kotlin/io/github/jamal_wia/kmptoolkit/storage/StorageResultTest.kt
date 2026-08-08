package io.github.jamal_wia.kmptoolkit.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `getStringOrNull returns a stored value`() {
        val storage = FakeKeyValueStorage()
        storage.put("k", "v")

        assertEquals("v", storage.getStringOrNull("k"))
    }

    @Test
    fun `getStringOrNull returns null for a key that was never written`() {
        assertNull(FakeKeyValueStorage().getStringOrNull("missing"))
    }

    @Test
    fun `getStringOrNull collapses a failed read into null`() {
        val storage = FakeKeyValueStorage()
        storage.entries["k"] = "v"
        storage.failReadsWith = StorageError.Undecryptable("k")

        assertNull(storage.getStringOrNull("k"))
    }

    @Test
    fun `getStringOrNull preserves an empty value rather than reporting it absent`() {
        val storage = FakeKeyValueStorage()
        storage.put("k", "")

        assertEquals("", storage.getStringOrNull("k"))
    }
}
