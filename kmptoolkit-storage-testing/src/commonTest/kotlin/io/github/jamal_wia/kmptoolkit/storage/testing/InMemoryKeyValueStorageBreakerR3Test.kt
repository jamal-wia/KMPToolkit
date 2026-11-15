package io.github.jamal_wia.kmptoolkit.storage.testing

import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

/**
 * Breaker round 3 of group sto-api (STO-2/STO-3, plan 3.8, D-15, D-19, ticket release-5-1-0): the fixture's
 * observation properties and its one-shot knob under equal (not identical) errors.
 *
 * Contract: `writes` is "every key this instance has ever been asked to write, in call order" and `contents`
 * is "a snapshot of the current contents" (class KDoc, 06-testing.md); a snapshot handed to a test is the
 * test's own copy. A Java test sees `java.util.List<String> getWrites()` and can call `add`, `clear` or
 * `Collections.sort` on it without any cast, so a snapshot that aliases the fixture's own record lets an
 * ordinary assertion helper rewrite the fixture's history. The Kotlin cast below is the same call a Java
 * caller makes implicitly.
 */
class InMemoryKeyValueStorageBreakerR3Test {

    /** Mutates [list] the way a Java caller can; a read-only or copied list may refuse or absorb it. */
    private fun tryToRewrite(list: List<String>) {
        runCatching {
            @Suppress("UNCHECKED_CAST")
            val mutable: MutableList<String> = list as MutableList<String>
            mutable.reverse()
            mutable.add("ghost")
            mutable.removeAt(0)
        }
    }

    @Test
    fun `mutating a writes snapshot does not rewrite the fixtures record`() = runTest {
        val storage = InMemoryKeyValueStorage()
        assertEquals(StorageResult.Success(Unit), storage.put("a", "1"))
        assertEquals(StorageResult.Success(Unit), storage.put("b", "2"))
        // Control: the record existed before the attempt.
        assertEquals(listOf("a", "b"), storage.writes)

        tryToRewrite(storage.writes)

        assertEquals(listOf("a", "b"), storage.writes, "a snapshot handed to the test aliased the fixture's own write log")
        assertEquals(StorageResult.Success(Unit), storage.put("c", "3"))
        assertEquals(listOf("a", "b", "c"), storage.writes, "later writes landed on a rewritten log")
    }

    @Test
    fun `mutating a contents snapshot does not change the store`() = runTest {
        val storage = InMemoryKeyValueStorage()
        assertEquals(StorageResult.Success(Unit), storage.put("a", "1"))
        assertEquals(StorageResult.Success(Unit), storage.put("b", "2"))
        assertEquals(mapOf("a" to "1", "b" to "2"), storage.contents)

        runCatching {
            @Suppress("UNCHECKED_CAST")
            val mutable: MutableMap<String, String> = storage.contents as MutableMap<String, String>
            mutable["a"] = "ghost"
            mutable.remove("b")
        }

        assertEquals(StorageResult.Success<String?>("1"), storage.get("a"))
        assertEquals(StorageResult.Success<String?>("2"), storage.get("b"))
    }

    @Test
    fun `re-arming an equal error after it was consumed fails exactly one more operation`() = runTest {
        // Two Unavailable() are equal but not identical: the knobs live in an equality-compared snapshot,
        // so an equal re-arm must still count as a new arm and an equal double arm as one.
        val storage = InMemoryKeyValueStorage()
        storage.failNextOperationWith = StorageError.Unavailable()
        assertEquals(StorageResult.Failure(StorageError.Unavailable()), storage.get("k"))
        storage.failNextOperationWith = StorageError.Unavailable()
        assertEquals(StorageResult.Failure(StorageError.Unavailable()), storage.put("k", "v"))
        assertEquals(StorageResult.Success<String?>(null), storage.get("k"), "the failed put had an effect")

        storage.failNextOperationWith = StorageError.Unavailable()
        storage.failNextOperationWith = StorageError.Unavailable()
        assertEquals(StorageResult.Failure(StorageError.Unavailable()), storage.remove("k"))
        assertEquals(StorageResult.Success(Unit), storage.clear(), "an equal double arm failed two operations")
        assertEquals(emptyList(), storage.writes)
    }

    @Test
    fun `ensureKey answers a scripted ENSURE_KEY or CHANGE_KEY failure unchanged and writes nothing`() = runTest {
        val storage = InMemoryKeyValueStorage()
        assertEquals(StorageResult.Success(Unit), storage.put("token", "abc"))
        val ensure = StorageError.OperationFailed(StorageOperation.ENSURE_KEY, key = null, platformCode = -1)
        val change = StorageError.OperationFailed(StorageOperation.CHANGE_KEY)

        storage.failNextOperationWith = ensure
        val first: StorageResult<Unit> = storage.ensureKey()
        storage.failEveryOperationWith = change
        val second: StorageResult<Unit> = storage.ensureKey()
        storage.failEveryOperationWith = null
        val third: StorageResult<Unit> = storage.ensureKey()

        assertSame(ensure, (first as StorageResult.Failure).error)
        assertSame(change, (second as StorageResult.Failure).error)
        assertEquals(StorageResult.Success(Unit), third)
        assertEquals(mapOf("token" to "abc"), storage.contents)
        assertEquals(listOf("token"), storage.writes)
    }
}
