package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.util.concurrent.ConcurrentHashMap

/**
 * The data keys of OS-key stores, cached in process memory per identity and shared by every instance over the same file
 * (plan 4.4). A cached key is never trusted by itself: each operation reads the header under the lock and verifies the
 * key-check with it, and a mismatch drops the entry (compare-and-remove, like the Android alias cache) so the next
 * operation fetches again.
 *
 * The arrays are owned by the cache; callers borrow them under the store lock and never keep or wipe them. A replaced or
 * invalidated key is wiped when it is removed (every borrower holds the store lock of the same identity, which the
 * remover holds too).
 */
internal class OsKeyCache {
    private val keys: ConcurrentHashMap<String, ByteArray> = ConcurrentHashMap()

    /** The cached key of [identity], or `null`. Borrowed: valid under the identity's store lock. */
    fun get(identity: StoreIdentity): ByteArray? = keys[identity.cacheKey]

    /** Caches [key] (a copy is stored; the caller keeps and wipes its own) and wipes the entry it replaces. */
    fun put(identity: StoreIdentity, key: ByteArray) {
        keys.put(identity.cacheKey, key.copyOf())?.fill(0)
    }

    /** Drops [stale] if it is still the cached entry (compare by reference) and wipes it. Returns whether it was dropped. */
    fun invalidate(identity: StoreIdentity, stale: ByteArray): Boolean {
        val removed: Boolean = keys.remove(identity.cacheKey, stale)
        if (removed) stale.fill(0)
        return removed
    }

    /** How many identities are cached; for tests. */
    val size: Int get() = keys.size

    internal companion object {
        /** The cache of the process. */
        val shared: OsKeyCache = OsKeyCache()
    }
}
