package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Plan R7 and 4.4: key identity (name + directory fingerprint) and the per-identity key cache. Group `sto-desktop`, phase B (6d). */
class IdentityAndKeyCacheTest {
    private val root: File = Files.createTempDirectory("kmptoolkit-identity").toFile()

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun dir(name: String): File = File(root, name).also { it.mkdirs() }

    @Test
    fun `an identity is the service plus 32 hex characters of the directory fingerprint`() {
        val identity: StoreIdentity = StoreIdentity.of("app.settings", dir("a"))
        assertEquals("app.settings.kmptoolkit.securestorage", identity.service)
        assertTrue(Regex("[0-9a-f]{32}").matches(identity.directoryHex), identity.directoryHex)
        assertEquals("dir-${identity.directoryHex}", identity.macAccount)
        assertEquals(mapOf("service" to identity.service, "directory" to identity.directoryHex), identity.linuxAttributes)
        assertContentEquals("kmptoolkit-storage:app.settings.kmptoolkit.securestorage".toByteArray(), identity.windowsEntropy)
        assertEquals(16, identity.headerFingerprint.size)
    }

    @Test
    fun `two same-name stores in two directories get two identities and a store keeps its identity`() {
        val a: StoreIdentity = StoreIdentity.of("n", dir("a"))
        val b: StoreIdentity = StoreIdentity.of("n", dir("b"))
        assertNotEquals(a.directoryHex, b.directoryHex)
        assertNotEquals(a.cacheKey, b.cacheKey)
        assertFalse(a.headerFingerprint.contentEquals(b.headerFingerprint))
        assertEquals(a.directoryHex, StoreIdentity.of("n", dir("a")).directoryHex, "stable")
        assertNotEquals(a.cacheKey, StoreIdentity.of("other", dir("a")).cacheKey, "the name is part of the identity")
        assertFalse(a.headerFingerprint.contentEquals(StoreIdentity.of("other", dir("a")).headerFingerprint))
    }

    @Test
    fun `a directory spelled with a dot segment or a case variant is the same identity (folded like the lock key)`() {
        val a: StoreIdentity = StoreIdentity.of("n", dir("Case"))
        assertEquals(a.directoryHex, StoreIdentity.of("n", File(File(File(root, "Case"), ".."), "Case")).directoryHex)
        assertEquals(a.directoryHex, StoreIdentity.of("n", File(root, "CASE")).directoryHex, "one identity for case variants, as jvmStorageLockKey folds")
    }

    @Test
    fun `the key cache stores a copy, compares by reference on invalidate and wipes what it drops`() {
        val cache = OsKeyCache()
        val identity: StoreIdentity = StoreIdentity.of("n", dir("a"))
        assertNull(cache.get(identity))

        val mine = ByteArray(32) { 7 }
        cache.put(identity, mine)
        mine.fill(0)
        val cached: ByteArray = cache.get(identity)!!
        assertContentEquals(ByteArray(32) { 7 }, cached, "the cache keeps its own copy")

        assertFalse(cache.invalidate(identity, ByteArray(32) { 7 }), "an equal-looking array that is not the cached one drops nothing")
        assertSame(cached, cache.get(identity))

        val replacement = ByteArray(32) { 9 }
        cache.put(identity, replacement)
        assertContentEquals(ByteArray(32), cached, "a replaced key is wiped")
        val second: ByteArray = cache.get(identity)!!
        assertTrue(cache.invalidate(identity, second))
        assertContentEquals(ByteArray(32), second, "an invalidated key is wiped")
        assertNull(cache.get(identity))
        assertEquals(0, cache.size)
        assertFalse(cache.invalidate(identity, second), "dropping twice is harmless")
    }

    @Test
    fun `a stale invalidate never removes a newer key`() {
        val cache = OsKeyCache()
        val identity: StoreIdentity = StoreIdentity.of("n", dir("a"))
        cache.put(identity, ByteArray(32) { 1 })
        val stale: ByteArray = cache.get(identity)!!
        cache.put(identity, ByteArray(32) { 2 })
        val fresh: ByteArray = cache.get(identity)!!
        assertFalse(cache.invalidate(identity, stale), "the entry was replaced meanwhile")
        assertSame(fresh, cache.get(identity))
        assertContentEquals(ByteArray(32) { 2 }, fresh)
    }

    @Test
    fun `the shared cache is one per process and concurrent identities do not interfere`() {
        val counter = AtomicInteger()
        val identities: List<StoreIdentity> = List(8) { StoreIdentity.of("n$it", dir("d$it")) }
        val cache = OsKeyCache()
        val threads: List<Thread> = identities.mapIndexed { index: Int, identity: StoreIdentity ->
            Thread { repeat(200) { cache.put(identity, ByteArray(32) { index.toByte() }); counter.incrementAndGet() } }
        }
        threads.forEach(Thread::start)
        threads.forEach { it.join(60_000) }
        assertEquals(8 * 200, counter.get())
        for ((index: Int, identity: StoreIdentity) in identities.withIndex()) assertContentEquals(ByteArray(32) { index.toByte() }, cache.get(identity))
        assertSame(OsKeyCache.shared, OsKeyCache.shared)
    }
}
