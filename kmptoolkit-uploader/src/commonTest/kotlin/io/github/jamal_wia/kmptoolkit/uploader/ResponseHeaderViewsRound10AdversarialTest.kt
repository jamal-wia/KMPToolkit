package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Round 10 (reopening R8-S7): `UploadResponse.headers` is documented as read-only, "a cast to a mutable map fails". The map
 * itself is wrapped, but a view of it (`keys`, `values`, `entries`) is the view of the very `LinkedHashMap` the response
 * keeps, which a cast reaches: clearing the key set, removing through an iterator or writing through an entry changes the
 * headers every later reader of the response sees (the hooks of one handler, the journal entry written afterwards).
 */
class ResponseHeaderViewsRound10AdversarialTest {

    private fun response() = UploadResponse(200, mapOf("a" to "1", "b" to "2"), ByteArray(0), false)

    private inline fun attempt(block: () -> Unit) {
        try {
            block()
        } catch (_: ClassCastException) {
        } catch (_: UnsupportedOperationException) {
        }
    }

    @Test
    fun `clearing the key set of the headers through a cast leaves the headers alone`() {
        val response = response()
        attempt { (response.headers.keys as MutableSet<String>).clear() }
        assertEquals(mapOf("a" to "1", "b" to "2"), response.headers)
    }

    @Test
    fun `removing through an iterator of the headers' keys leaves the headers alone`() {
        val response = response()
        attempt {
            val iterator = (response.headers.keys.iterator() as MutableIterator<String>)
            iterator.next()
            iterator.remove()
        }
        assertEquals(mapOf("a" to "1", "b" to "2"), response.headers)
    }

    @Test
    fun `writing through an entry of the headers leaves the headers alone`() {
        val response = response()
        attempt {
            @Suppress("UNCHECKED_CAST")
            val entry = response.headers.entries.first() as MutableMap.MutableEntry<String, String>
            entry.setValue("hijacked")
        }
        assertEquals(mapOf("a" to "1", "b" to "2"), response.headers)
    }

    @Test
    fun `clearing the values of the headers through a cast leaves the headers alone`() {
        val response = response()
        attempt { (response.headers.values as MutableCollection<String>).clear() }
        assertEquals(mapOf("a" to "1", "b" to "2"), response.headers)
    }

    @Test
    fun `a response that came back from the engine's size cap keeps read-only views too`() {
        val big = UploadResponse(200, (0 until 5000).associate { "h$it" to "v".repeat(40) }, ByteArray(0), false)
        val capped = big.enforced(0)
        attempt { (capped.headers.keys as MutableSet<String>).clear() }
        assertEquals(true, capped.headers.isNotEmpty())
    }
}
