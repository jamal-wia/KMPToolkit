package io.github.jamal_wia.kmptoolkit.storage.testing

import io.github.jamal_wia.kmptoolkit.storage.DeviceIdProvider
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.errorOrNull
import io.github.jamal_wia.kmptoolkit.storage.getOrNull
import io.github.jamal_wia.kmptoolkit.storage.getStringOrNull
import io.github.jamal_wia.kmptoolkit.storage.isSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fake is held to the same contract as the real stores.
 *
 * It restates that contract rather than sharing the production module's own contract suite —
 * Kotlin's `internal` does not cross a module boundary, and a test source set cannot be published
 * for another module to extend. The cost is that the two could drift, so the cases here are derived
 * from the same documented contract and any change to it has to be made in both places.
 */
class InMemoryKeyValueStorageTest {

    private val storage = InMemoryKeyValueStorage()

    @Test
    fun `a key that was never written reads back as absent`() {
        assertEquals(StorageResult.Success(null), storage.get("missing"))
    }

    @Test
    fun `a written value reads back`() {
        storage.put("k", "v")

        assertEquals(StorageResult.Success("v"), storage.get("k"))
    }

    @Test
    fun `an empty value is stored rather than treated as absent`() {
        storage.put("k", "")

        assertEquals(StorageResult.Success(""), storage.get("k"))
    }

    @Test
    fun `writing a key twice keeps the second value`() {
        storage.put("k", "first")

        storage.put("k", "second")

        assertEquals(StorageResult.Success("second"), storage.get("k"))
    }

    @Test
    fun `remove deletes the value`() {
        storage.put("k", "v")

        assertTrue(storage.remove("k").isSuccess)

        assertEquals(StorageResult.Success(null), storage.get("k"))
    }

    @Test
    fun `removing a key that was never written succeeds and changes nothing`() {
        storage.put("kept", "v")

        assertTrue(storage.remove("never-written").isSuccess)

        assertEquals(StorageResult.Success("v"), storage.get("kept"))
    }

    @Test
    fun `clear empties the store`() {
        storage.put("a", "1")
        storage.put("b", "2")

        assertTrue(storage.clear().isSuccess)

        assertEquals(emptyMap(), storage.contents)
    }

    @Test
    fun `clear on an already empty store succeeds`() {
        assertTrue(storage.clear().isSuccess)
    }

    @Test
    fun `two instances are independent stores`() {
        val other = InMemoryKeyValueStorage()

        storage.put("k", "mine")

        assertNull(other.getStringOrNull("k"))
    }

    @Test
    fun `contents reflects every stored entry`() {
        storage.put("a", "1")
        storage.put("b", "2")

        assertEquals(mapOf("a" to "1", "b" to "2"), storage.contents)
    }

    @Test
    fun `contents is a snapshot rather than a live view`() {
        storage.put("a", "1")
        val snapshot: Map<String, String> = storage.contents

        storage.put("b", "2")

        assertEquals(mapOf("a" to "1"), snapshot)
    }

    @Test
    fun `writes records every write in order including overwrites`() {
        storage.put("a", "1")
        storage.put("a", "2")
        storage.put("b", "3")

        assertEquals(listOf("a", "a", "b"), storage.writes)
    }

    @Test
    fun `writes keeps a key that was later removed`() {
        storage.put("a", "1")
        storage.remove("a")

        assertEquals(listOf("a"), storage.writes)
    }

    @Test
    fun `writes is a snapshot rather than a live view`() {
        storage.put("a", "1")
        val snapshot: List<String> = storage.writes

        storage.put("b", "2")

        assertEquals(listOf("a"), snapshot)
    }

    @Test
    fun `a failed write is not recorded`() {
        storage.failNextOperationWith = StorageError.Unavailable()

        storage.put("a", "1")

        assertEquals(emptyList(), storage.writes)
    }

    @Test
    fun `a scripted error fails the next read`() {
        val error = StorageError.Undecryptable("k")
        storage.put("k", "v")
        storage.failNextOperationWith = error

        assertEquals(error, storage.get("k").errorOrNull())
    }

    @Test
    fun `a scripted error clears itself after one operation`() {
        storage.put("k", "v")
        storage.failNextOperationWith = StorageError.Undecryptable("k")

        storage.get("k")

        assertEquals("v", storage.get("k").getOrNull())
    }

    @Test
    fun `a scripted error applies to whichever operation comes next`() {
        // Not "the operation the error names": the knob is armed against the next call of any kind,
        // so a test that scripts a REMOVE failure and then writes gets the failure on the write.
        storage.failNextOperationWith = StorageError.OperationFailed(StorageOperation.REMOVE)

        val failure: StorageError? = storage.put("k", "v").errorOrNull()

        assertEquals(StorageError.OperationFailed(StorageOperation.REMOVE), failure)
        assertTrue(storage.remove("k").isSuccess)
    }

    @Test
    fun `a failed write leaves the previous value in place`() {
        storage.put("k", "original")
        storage.failNextOperationWith = StorageError.Unavailable()

        storage.put("k", "replacement")

        assertEquals("original", storage.get("k").getOrNull())
    }

    @Test
    fun `a failed remove leaves the value in place`() {
        storage.put("k", "v")
        storage.failNextOperationWith = StorageError.Unavailable()

        assertEquals(false, storage.remove("k").isSuccess)

        assertEquals("v", storage.get("k").getOrNull())
    }

    @Test
    fun `a failed clear leaves the store intact`() {
        storage.put("k", "v")
        storage.failNextOperationWith = StorageError.Unavailable()

        assertEquals(false, storage.clear().isSuccess)

        assertEquals(mapOf("k" to "v"), storage.contents)
    }

    @Test
    fun `clearing the scripted error before it is consumed restores normal behavior`() {
        storage.failNextOperationWith = StorageError.Unavailable()
        storage.failNextOperationWith = null

        assertTrue(storage.put("k", "v").isSuccess)
    }

    @Test
    fun `it stands in for a DeviceIdProvider's storage`() {
        val first: String = DeviceIdProvider(storage).current()

        assertEquals(first, DeviceIdProvider(storage).current())

        storage.clear()
        assertNotEquals(first, DeviceIdProvider(storage).current())
    }
}
