package io.github.jamal_wia.kmptoolkit.uploader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Items whose handler succeeded (or dropped them) but whose confirming delete failed, by item id, for the whole
 * process: the guarantee that such an effect is not run again by the same process also holds for an engine built to
 * replace a closed one (a logout and a login, a rebuilt scope), which has no memory of its own.
 *
 * An entry carries a fingerprint of the row it was recorded for. A row that carries the same id but another
 * fingerprint (a custom `idGenerator` that reuses an id, over another store) is a different item: it neither matches
 * nor removes the entry, so it runs normally and nothing is deleted on a guess.
 *
 * Not bounded by an active list on purpose. Two live engines over two stores are a documented setup, and an engine
 * that pruned by its own list would erase the other's entries. An entry leaves only when a delete confirms the
 * delivery ([forget]). A withdrawal keeps it: a rolled-back or failed withdrawal leaves the row queued, and its effect
 * must still not run again. A row removed for good, by a withdrawal or from outside every engine, leaves a leaked entry
 * of about a hundred bytes. The time cost of such an entry is bounded separately: the drain's sweep of held rows reads
 * an entry whose row it found absent only once per heartbeat interval ([RowLedger.heldOutOfRotation]), not on every
 * pass.
 *
 * Residual: a row of another store with the same id and fingerprint (a custom `idGenerator` reusing ids with identical
 * content and creation time) is taken for the same item.
 */
internal object DeliveredUnconfirmed {

    private data class Fingerprint(val type: String, val createdAtEpochMillis: Long, val payloadHash: Int)

    private val entries: MutableStateFlow<Map<String, Fingerprint>> = MutableStateFlow(emptyMap())

    private fun UploaderItem.fingerprint(): Fingerprint = Fingerprint(type, createdAtEpochMillis, payload.hashCode())

    /** Remembers that [item]'s effect ran and only its delete is owed. */
    fun remember(item: UploaderItem) {
        entries.update { it + (item.id to item.fingerprint()) }
    }

    /** A snapshot of the ids of every entry, for the drain's sweep of held rows that are out of rotation. */
    fun ids(): Set<String> = entries.value.keys

    /** Whether [item] is the very row an earlier delete failure was recorded for (same id and same fingerprint). */
    fun matches(item: UploaderItem): Boolean = entries.value[item.id] == item.fingerprint()

    /**
     * Forgets the entry of [item] when it was recorded for this very row (a delete confirmed the delivery; a
     * withdrawal never calls this). An entry recorded for another row of the same id stays.
     */
    fun forget(item: UploaderItem) {
        val print: Fingerprint = item.fingerprint()
        if (entries.value[item.id] == print) entries.update { map -> if (map[item.id] == print) map - item.id else map }
    }
}
