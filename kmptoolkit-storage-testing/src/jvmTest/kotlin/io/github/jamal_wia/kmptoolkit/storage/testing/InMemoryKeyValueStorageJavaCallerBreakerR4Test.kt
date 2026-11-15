@file:Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN", "UNCHECKED_CAST")

package io.github.jamal_wia.kmptoolkit.storage.testing

import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.coroutines.test.runTest

/**
 * Breaker round 4 of group sto-api (STO-2/STO-3, plan 3.8, ticket release-5-1-0): the round 3 fix of `writes`
 * attacked from the caller its own KDoc names, a Java test.
 *
 * Contract (KDoc of `writes`, a891077d): "Each read returns a copy, like [contents]: the list is the caller's own,
 * so sorting or clearing it (a Java caller can, without a cast) neither rewrites the record nor disturbs a
 * concurrent `put`." KDoc of `contents`: "Each read returns a copy that is the caller's own, so changing it never
 * changes the store." A Java test sees `java.util.List<String> getWrites()` and `java.util.Map<String, String>
 * getContents()` and calls `Collections.sort`, `clear()` or `put` on them with no cast. The promise has no size
 * condition, so it must hold for an empty record and a one-key record as for a longer one.
 *
 * The cast to the `java.util` interface below is exactly the static type a Java caller holds: it compiles to a plain
 * CHECKCAST, not to Kotlin's read-only-collection check, so each call below is the call a Java test makes. Every
 * size is tried before the test fails, so the message lists each size that broke.
 */
class InMemoryKeyValueStorageJavaCallerBreakerR4Test {

    private suspend fun fixtureWith(size: Int): InMemoryKeyValueStorage {
        val storage = InMemoryKeyValueStorage()
        for (index: Int in size downTo 1) {
            assertEquals(StorageResult.Success(Unit), storage.put("k$index", "v$index"))
        }
        return storage
    }

    @Test
    fun `a Java caller can sort and clear a writes copy of any size and the record stays whole`() = runTest {
        val broken: MutableList<String> = mutableListOf()
        for (size: Int in 0..3) {
            val storage: InMemoryKeyValueStorage = fixtureWith(size)
            val expected: List<String> = (size downTo 1).map { "k$it" }
            // Control: the record holds what was written.
            assertEquals(expected, storage.writes, "size=$size: control")

            val javaView: java.util.List<String> = storage.writes as java.util.List<String>
            // sort(null) is what Collections.sort(list) calls.
            runCatching { javaView.sort(null) }.exceptionOrNull()?.let { broken += "size=$size: sort threw $it" }
            runCatching { javaView.clear() }.exceptionOrNull()?.let { broken += "size=$size: clear threw $it" }

            assertEquals(expected, storage.writes, "size=$size: the record changed through a copy")
            assertEquals(StorageResult.Success(Unit), storage.put("next", "v"))
            assertEquals(expected + "next", storage.writes, "size=$size: a later write did not append to the record")
        }
        if (broken.isNotEmpty()) fail("the caller's own copy of writes refused a Java caller: $broken")
    }

    @Test
    fun `a Java caller can change a contents copy of any size and the store stays whole`() = runTest {
        val broken: MutableList<String> = mutableListOf()
        for (size: Int in 0..3) {
            val storage: InMemoryKeyValueStorage = fixtureWith(size)
            val expected: Map<String, String> = (1..size).associate { "k$it" to "v$it" }
            assertEquals(expected, storage.contents, "size=$size: control")

            val javaView: java.util.Map<String, String> = storage.contents as java.util.Map<String, String>
            runCatching<Unit> {
                javaView.clear()
                javaView.put("ghost", "v")
            }.exceptionOrNull()?.let { broken += "size=$size: clear/put threw $it" }

            assertEquals(expected, storage.contents, "size=$size: the store changed through a copy")
        }
        if (broken.isNotEmpty()) fail("the caller's own copy of contents refused a Java caller: $broken")
    }
}
