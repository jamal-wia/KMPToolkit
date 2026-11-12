package io.github.jamal_wia.kmptoolkit.uploader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Leases of hand-off claims whose outcome is not known yet, by item id (rule P3 of the [DefaultUploaderEngine] class
 * KDoc). Entries live for one claim write.
 *
 * Every engine that read the same row computes the same claim number, so after an overlap (a closed engine whose claim
 * write is still pending, and its replacement) the claim cannot tell the two writes apart. The lease can: it is stored
 * exactly, so a hand-off that reserved a lease no other unresolved claim of its item holds recognises its own claim by
 * reading the row back. The set is per process on purpose: two engines over one database in two processes are outside
 * the contract.
 */
internal object ClaimsInDoubt {

    private val held: MutableStateFlow<Map<String, Set<Long>>> = MutableStateFlow(emptyMap())

    /**
     * Returns [lease], or the nearest later value that no unresolved claim of [id] holds, and holds it until [drop].
     * Without an overlap nothing is shifted; in one, the later claim's lease is a millisecond longer. At the very top of
     * the `Long` range, where nothing is longer, the lease is a millisecond shorter instead, so the grants stay
     * distinct there too.
     */
    fun reserve(id: String, lease: Long): Long {
        var granted: Long = lease
        held.update { map ->
            val taken: Set<Long> = map[id].orEmpty()
            var candidate: Long = lease
            while (candidate in taken && candidate < Long.MAX_VALUE) candidate++
            if (candidate in taken) {
                // [lease] and everything above it is held: the top of the range. Look below instead.
                candidate = lease
                while (candidate in taken) candidate--
            }
            granted = candidate
            map + (id to (taken + candidate))
        }
        return granted
    }

    /** Releases the reservation [reserve] granted. */
    fun drop(id: String, lease: Long) {
        held.update { map ->
            val remaining: Set<Long> = map[id].orEmpty() - lease
            if (remaining.isEmpty()) map - id else map + (id to remaining)
        }
    }
}
