package io.github.jamal_wia.kmptoolkit.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

// path-hygiene-file: logical-paths the spellings are keys of one lock, never opened as files

/**
 * L-1: [PathLocks] serializes every spelling of one file. The shared state is an in-memory counter, not a file: on a
 * case-sensitive volume the spellings below are different files, and the lock is lexical by contract.
 */
class PathLocksTest {

    @Test
    fun `eight spellings of two paths serialize a read-modify-write exactly per path`() = runTest(timeout = 120.seconds) {
        val counterSpellings: List<String> = listOf(
            "/data/Counter", "/data/counter", "/data/COUNTER", "/data/./counter", "/data/x/../counter", "/data//Counter",
        )
        val cafeSpellings: List<String> = listOf("/data/caf\u00e9", "/data/cafe\u0301")
        var counter: Int = 0
        var cafe: Int = 0
        withContext(Dispatchers.Default) {
            withTimeout(100_000) {
                val workers: List<kotlinx.coroutines.Deferred<Unit>> =
                    counterSpellings.map { spelling ->
                        async {
                            repeat(200) {
                                PathLocks.withLock(spelling) {
                                    val read: Int = counter
                                    yield()
                                    counter = read + 1
                                }
                            }
                        }
                    } + cafeSpellings.map { spelling ->
                        async {
                            repeat(200) {
                                PathLocks.withLock(spelling) {
                                    val read: Int = cafe
                                    yield()
                                    cafe = read + 1
                                }
                            }
                        }
                    }
                workers.awaitAll()
            }
        }
        assertEquals(6 * 200, counter, "an update of the counter path was lost")
        assertEquals(2 * 200, cafe, "an update of the cafe path was lost")
    }

    @Test
    fun `tryWithLock follows the same keys`() = runTest {
        var inner: Boolean = false
        var outer: Boolean = false
        PathLocks.withLock("/data/Key") {
            inner = PathLocks.tryWithLock("/data/key") { }
            outer = PathLocks.tryWithLock("/data/other") { }
        }
        assertFalse(inner, "another spelling of a held path must be refused")
        assertTrue(outer)
        assertTrue(PathLocks.tryWithLock("/data/Key") { })
    }
}
