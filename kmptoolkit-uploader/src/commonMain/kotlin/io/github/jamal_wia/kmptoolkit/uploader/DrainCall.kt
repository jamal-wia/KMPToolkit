package io.github.jamal_wia.kmptoolkit.uploader

/**
 * The turn bookkeeping of one call of `drain()` (and of one pass of the engine's own loop), rule P9: an item gets **at
 * most one turn** per call. An attempt that ran, an attempt that failed (outside its handler or not), a confirming
 * delete that failed or a park the drain made uses the turn up; a later sweep of the same call leaves the item for the
 * next trigger or alarm. A confirming delete that **succeeded** uses nothing (the row is gone, and the tail of its
 * ordering channel must still run in the same call). The turn bounds every clock: neither a zero backoff, nor a clock
 * at `Long.MAX_VALUE` (every gate reads as due), nor a store whose point read disagrees with its list read can keep one
 * call spinning.
 *
 * One instance per call, used from the one coroutine that runs the pass, so it needs no synchronisation.
 */
internal class DrainCall {

    private val spent: MutableSet<String> = HashSet()

    private val skippedAsMoved: MutableSet<String> = HashSet()

    private var heldSwept: Boolean = false

    /**
     * Whether the sweep of held rows that are out of rotation (PARKED rows still owed their confirming delete, P7) is
     * due: `true` once per call, for its first sweep. Later sweeps of the same call leave those rows alone, so a store
     * that keeps failing costs one delete per row per call.
     */
    fun takeHeldSweep(): Boolean {
        if (heldSwept) return false
        heldSwept = true
        return true
    }

    /** Whether [id] already had its turn in this call. Checked first, in one place, for every item. */
    fun hasHadTurn(id: String): Boolean = id in spent

    /** Uses up the turn of [id] in this call. */
    fun spendTurn(id: String) {
        spent += id
    }

    /**
     * Records that [id] was skipped as "moved" (the store's point read differed from its list read) and returns
     * whether it had already been skipped once in this call, which means the store keeps disagreeing with itself.
     */
    fun skippedAsMovedBefore(id: String): Boolean = !skippedAsMoved.add(id)
}
