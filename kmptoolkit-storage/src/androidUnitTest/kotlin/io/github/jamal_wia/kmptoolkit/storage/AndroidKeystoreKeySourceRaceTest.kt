package io.github.jamal_wia.kmptoolkit.storage

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Concurrent first use of one alias must end with one key, the one every caller got back.
 *
 * The AndroidKeyStore cannot be driven from a JVM test, so these run [AndroidKeystoreKeySource]
 * against [RacyKeyStore]: a key store with the real one's two dangerous properties — generating
 * under an alias silently replaces what it held, and deleting an unrecoverable alias deletes
 * whatever the alias holds by then — whose lookup holds every caller in the gap between "looked"
 * and "acted" until all of them have looked. Without a lock that spans the lookup and the
 * generation, every caller misses and every caller generates; that is the failure these tests pin.
 */
class AndroidKeystoreKeySourceRaceTest {

    @Test
    fun `concurrent first calls on separate instances all get the one key that was kept`() = runTest {
        val store = RacyKeyStore(callers = CALLERS)

        val keys: List<SecretKey> = (1..CALLERS)
            .map { async(Dispatchers.IO) { AndroidKeystoreKeySource(ALIAS, store).key() } }
            .awaitAll()

        assertEquals(1, store.generated.get(), "keys generated")
        keys.forEach { key -> assertSame(store.liveKey(ALIAS), key) }
    }

    @Test
    fun `concurrent first calls on one shared instance all get the one key that was kept`() = runTest {
        val store = RacyKeyStore(callers = CALLERS)
        val source = AndroidKeystoreKeySource(ALIAS, store)

        val keys: List<SecretKey> = (1..CALLERS)
            .map { async(Dispatchers.IO) { source.key() } }
            .awaitAll()

        assertEquals(1, store.generated.get(), "keys generated")
        keys.forEach { key -> assertSame(store.liveKey(ALIAS), key) }
    }

    @Test
    fun `concurrent recovery from an unrecoverable key never deletes the replacement`() = runTest {
        // The device lock was changed: the alias is there, its key is not. Every caller sees the dead
        // entry; a caller that acted on that stale sighting after another had already replaced it
        // would delete the replacement and generate yet another.
        val store = RacyKeyStore(callers = CALLERS, unrecoverable = setOf(ALIAS))

        val keys: List<SecretKey> = (1..CALLERS)
            .map { async(Dispatchers.IO) { AndroidKeystoreKeySource(ALIAS, store).key() } }
            .awaitAll()

        assertEquals(1, store.generated.get(), "keys generated")
        keys.forEach { key -> assertSame(store.liveKey(ALIAS), key) }
    }

    @Test
    fun `different aliases each get their own key`() = runTest {
        val store = RacyKeyStore(callers = 2)

        val first: SecretKey = AndroidKeystoreKeySource("first", store).key()
        val second: SecretKey = AndroidKeystoreKeySource("second", store).key()

        assertSame(store.liveKey("first"), first)
        assertSame(store.liveKey("second"), second)
        assertEquals(2, store.generated.get(), "keys generated")
    }

    private class RacyKeyStore(
        callers: Int,
        unrecoverable: Set<String> = emptySet(),
    ) : AliasedKeyStore {

        private sealed interface Entry
        private class Live(val key: SecretKey) : Entry
        private object Dead : Entry

        private val entries: ConcurrentHashMap<String, Entry> =
            ConcurrentHashMap(unrecoverable.associateWith { Dead })

        /**
         * Holds each lookup open until every caller has looked, or [WINDOW_MS] passes. Callers that
         * are not serialized all arrive together and all act on what they saw; serialized callers
         * arrive one at a time, so each waits out the window alone — slow, but only by the window.
         */
        private val looked: CountDownLatch = CountDownLatch(callers)

        val generated: AtomicInteger = AtomicInteger()

        fun liveKey(alias: String): SecretKey? = (entries[alias] as? Live)?.key

        override fun existingKey(alias: String): SecretKey? {
            val seen: Entry? = entries[alias]
            looked.countDown()
            looked.await(WINDOW_MS, TimeUnit.MILLISECONDS)
            return when (seen) {
                is Live -> seen.key
                // As deleteEntry(alias) does: removes whatever the alias holds *now*.
                Dead -> {
                    entries.remove(alias)
                    null
                }
                null -> null
            }
        }

        override fun generateKey(alias: String): SecretKey {
            val bytes = ByteArray(KEY_BYTES).also(SecureRandom()::nextBytes)
            val key: SecretKey = SecretKeySpec(bytes, "AES")
            entries[alias] = Live(key)
            generated.incrementAndGet()
            return key
        }
    }

    private companion object {
        const val ALIAS = "race.alias"
        const val CALLERS = 8
        const val WINDOW_MS = 100L
        const val KEY_BYTES = 32
    }
}
