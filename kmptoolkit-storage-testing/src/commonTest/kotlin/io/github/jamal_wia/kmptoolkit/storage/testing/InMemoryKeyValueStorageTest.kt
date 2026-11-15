package io.github.jamal_wia.kmptoolkit.storage.testing

import io.github.jamal_wia.kmptoolkit.storage.DeviceIdProvider
import io.github.jamal_wia.kmptoolkit.storage.SecureStorageProtection
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.errorOrNull
import io.github.jamal_wia.kmptoolkit.storage.getBoolean
import io.github.jamal_wia.kmptoolkit.storage.getInt
import io.github.jamal_wia.kmptoolkit.storage.getLong
import io.github.jamal_wia.kmptoolkit.storage.getOrNull
import io.github.jamal_wia.kmptoolkit.storage.isSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

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
    fun `a key that was never written reads back as absent`() = runTest {
        assertEquals(StorageResult.Success(null), storage.get("missing"))
    }

    @Test
    fun `a written value reads back`() = runTest {
        storage.put("k", "v")

        assertEquals(StorageResult.Success("v"), storage.get("k"))
    }

    @Test
    fun `an empty value is stored rather than treated as absent`() = runTest {
        storage.put("k", "")

        assertEquals(StorageResult.Success(""), storage.get("k"))
    }

    @Test
    fun `writing a key twice keeps the second value`() = runTest {
        storage.put("k", "first")

        storage.put("k", "second")

        assertEquals(StorageResult.Success("second"), storage.get("k"))
    }

    @Test
    fun `remove deletes the value`() = runTest {
        storage.put("k", "v")

        assertTrue(storage.remove("k").isSuccess)

        assertEquals(StorageResult.Success(null), storage.get("k"))
    }

    @Test
    fun `removing a key that was never written succeeds and changes nothing`() = runTest {
        storage.put("kept", "v")

        assertTrue(storage.remove("never-written").isSuccess)

        assertEquals(StorageResult.Success("v"), storage.get("kept"))
    }

    @Test
    fun `clear empties the store`() = runTest {
        storage.put("a", "1")
        storage.put("b", "2")

        assertTrue(storage.clear().isSuccess)

        assertEquals(emptyMap(), storage.contents)
    }

    @Test
    fun `clear on an already empty store succeeds`() = runTest {
        assertTrue(storage.clear().isSuccess)
    }

    @Test
    fun `two instances are independent stores`() = runTest {
        val other = InMemoryKeyValueStorage()

        storage.put("k", "mine")

        assertNull(other.get("k").getOrNull())
    }

    @Test
    fun `contents reflects every stored entry`() = runTest {
        storage.put("a", "1")
        storage.put("b", "2")

        assertEquals(mapOf("a" to "1", "b" to "2"), storage.contents)
    }

    @Test
    fun `contents is a snapshot rather than a live view`() = runTest {
        storage.put("a", "1")
        val snapshot: Map<String, String> = storage.contents

        storage.put("b", "2")

        assertEquals(mapOf("a" to "1"), snapshot)
    }

    @Test
    fun `writes records every write in order including overwrites`() = runTest {
        storage.put("a", "1")
        storage.put("a", "2")
        storage.put("b", "3")

        assertEquals(listOf("a", "a", "b"), storage.writes)
    }

    @Test
    fun `writes keeps a key that was later removed`() = runTest {
        storage.put("a", "1")
        storage.remove("a")

        assertEquals(listOf("a"), storage.writes)
    }

    @Test
    fun `writes is a snapshot rather than a live view`() = runTest {
        storage.put("a", "1")
        val snapshot: List<String> = storage.writes

        storage.put("b", "2")

        assertEquals(listOf("a"), snapshot)
    }

    @Test
    fun `a snapshot of writes or contents is the callers own whatever its size`() = runTest {
        // Sizes 0, 1 and 2 or more are copied into different list and map types; none may alias the fixture.
        for (size: Int in 0..3) {
            val fixture = InMemoryKeyValueStorage()
            val keys: List<String> = (1..size).map { "k$it" }
            keys.forEach { fixture.put(it, "v") }

            runCatching {
                @Suppress("UNCHECKED_CAST")
                val log: MutableList<String> = fixture.writes as MutableList<String>
                log.clear()
                log.add("ghost")
            }
            runCatching {
                @Suppress("UNCHECKED_CAST")
                val entries: MutableMap<String, String> = fixture.contents as MutableMap<String, String>
                entries.clear()
                entries["ghost"] = "v"
            }

            assertEquals(keys, fixture.writes, "size=$size: the write log was rewritten through a snapshot")
            assertEquals(keys.associateWith { "v" }, fixture.contents, "size=$size: the contents were changed through a snapshot")
            fixture.put("next", "v")
            assertEquals(keys + "next", fixture.writes, "size=$size: a later write landed on a rewritten log")
        }
    }

    @Test
    fun `a failed write is not recorded`() = runTest {
        storage.failNextOperationWith = StorageError.Unavailable()

        storage.put("a", "1")

        assertEquals(emptyList(), storage.writes)
    }

    @Test
    fun `a scripted error fails the next read`() = runTest {
        val error = StorageError.Undecryptable("k")
        storage.put("k", "v")
        storage.failNextOperationWith = error

        assertEquals(error, storage.get("k").errorOrNull())
    }

    @Test
    fun `a scripted error clears itself after one operation`() = runTest {
        storage.put("k", "v")
        storage.failNextOperationWith = StorageError.Undecryptable("k")

        storage.get("k")

        assertEquals("v", storage.get("k").getOrNull())
    }

    @Test
    fun `a scripted error applies to whichever operation comes next`() = runTest {
        // Not "the operation the error names": the knob is armed against the next call of any kind,
        // so a test that scripts a REMOVE failure and then writes gets the failure on the write.
        storage.failNextOperationWith = StorageError.OperationFailed(StorageOperation.REMOVE)

        val failure: StorageError? = storage.put("k", "v").errorOrNull()

        assertEquals(StorageError.OperationFailed(StorageOperation.REMOVE), failure)
        assertTrue(storage.remove("k").isSuccess)
    }

    @Test
    fun `a failed write leaves the previous value in place`() = runTest {
        storage.put("k", "original")
        storage.failNextOperationWith = StorageError.Unavailable()

        storage.put("k", "replacement")

        assertEquals("original", storage.get("k").getOrNull())
    }

    @Test
    fun `a failed remove leaves the value in place`() = runTest {
        storage.put("k", "v")
        storage.failNextOperationWith = StorageError.Unavailable()

        assertEquals(false, storage.remove("k").isSuccess)

        assertEquals("v", storage.get("k").getOrNull())
    }

    @Test
    fun `a failed clear leaves the store intact`() = runTest {
        storage.put("k", "v")
        storage.failNextOperationWith = StorageError.Unavailable()

        assertEquals(false, storage.clear().isSuccess)

        assertEquals(mapOf("k" to "v"), storage.contents)
    }

    @Test
    fun `clearing the scripted error before it is consumed restores normal behavior`() = runTest {
        storage.failNextOperationWith = StorageError.Unavailable()
        storage.failNextOperationWith = null

        assertTrue(storage.put("k", "v").isSuccess)
    }

    @Test
    fun `it stands in for a DeviceIdProvider's storage`() = runTest {
        val first: String = DeviceIdProvider(storage).current()

        assertEquals(first, DeviceIdProvider(storage).current())

        storage.clear()
        assertNotEquals(first, DeviceIdProvider(storage).current())
    }

    @Test
    fun `a put with an unpaired surrogate fails with InvalidValue and changes nothing`() = runTest {
        storage.put("k", "old")

        for ((key: String, value: String) in listOf("k" to "a\uD800b", "k" to "a\uDC00b", "\uD800" to "v", "a\uDC00" to "v")) {
            val error: StorageError? = storage.put(key, value).errorOrNull()
            assertTrue(
                error is StorageError.InvalidValue && error.key == key,
                "put of a key/value with an unpaired surrogate answered $error",
            )
        }

        assertEquals(mapOf("k" to "old"), storage.contents)
        assertEquals(listOf("k"), storage.writes)
    }

    // Mirrors KeyValueStorageContractTest (kmptoolkit-storage), which this module cannot extend: a change
    // to either pair of cases has to be made in both places.
    @Test
    fun `a typed read of a stored value of another type is InvalidValue naming the key and leaves the value alone`() = runTest {
        storage.put("k", "forty-two")

        val results: List<StorageResult<Any?>> = listOf(storage.getInt("k"), storage.getLong("k"), storage.getBoolean("k"))

        for (result: StorageResult<Any?> in results) {
            val error: StorageError? = result.errorOrNull()
            assertTrue(error is StorageError.InvalidValue && error.key == "k", "expected InvalidValue(k), was $result")
        }
        assertEquals(StorageResult.Success("forty-two"), storage.get("k"))
        assertEquals(mapOf("k" to "forty-two"), storage.contents)
    }

    @Test
    fun `a typed read of an absent key is Success null rather than InvalidValue`() = runTest {
        assertEquals(StorageResult.Success(null), storage.getInt("missing"))
        assertEquals(StorageResult.Success(null), storage.getLong("missing"))
        assertEquals(StorageResult.Success(null), storage.getBoolean("missing"))
    }

    @Test
    fun `protection defaults to NONE and takes the value a test gives it`() {
        assertEquals(SecureStorageProtection.NONE, storage.protection)
        assertEquals(
            SecureStorageProtection.OS_KEY_STORE_USER_SCOPED,
            InMemoryKeyValueStorage(SecureStorageProtection.OS_KEY_STORE_USER_SCOPED).protection,
        )
    }

    @Test
    fun `ensureKey succeeds and follows an armed failure then clears it`() = runTest {
        assertEquals(StorageResult.Success(Unit), storage.ensureKey())

        storage.failNextOperationWith = StorageError.AccessDenied()

        assertEquals(StorageError.AccessDenied(), storage.ensureKey().errorOrNull())
        assertEquals(StorageResult.Success(Unit), storage.ensureKey())
    }

    @Test
    fun `a put with a well-formed surrogate pair is stored`() = runTest {
        storage.put("k", "😀")

        assertEquals(StorageResult.Success("😀"), storage.get("k"))
    }

    @Test
    fun `an invalid put does not consume an armed failure`() = runTest {
        storage.failNextOperationWith = StorageError.Unavailable()

        storage.put("k", "\uD800")

        assertEquals(StorageError.Unavailable(), storage.get("k").errorOrNull())
    }

    @Test
    fun `a result read from the fixture prints no stored value`() = runTest {
        storage.put("token", "s3cr3t-token-Qw71")
        storage.put("pin", "918273")

        val texts: List<String> = listOf(storage.get("token").toString(), storage.getInt("pin").toString())

        for (text in texts) {
            assertTrue(text.contains("Success"), "the fixture's result no longer names its type: $text")
            assertTrue(!text.contains("s3cr3t") && !text.contains("918273"), "the fixture's result printed the stored value: $text")
        }
    }

    @Test
    fun `a failure of the fixture prints its error and never a stored or refused value`() = runTest {
        storage.put("pin", "s3cr3t-not-a-number")

        val typed: String = storage.getInt("pin").toString()
        val refused: String = storage.put("token", "s3cr3t-refused\uD800").toString()

        assertTrue(typed.contains("InvalidValue") && typed.contains("pin"), "the failure lost its error or key: $typed")
        assertTrue(refused.contains("InvalidValue") && refused.contains("token"), "the failure lost its error or key: $refused")
        assertTrue(!typed.contains("s3cr3t") && !refused.contains("s3cr3t"), "a failure text carries a value: $typed | $refused")
    }
}
