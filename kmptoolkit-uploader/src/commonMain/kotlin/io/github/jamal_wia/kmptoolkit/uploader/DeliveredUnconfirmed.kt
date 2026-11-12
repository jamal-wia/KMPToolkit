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
 * that pruned by its own list would erase the other's entries. An entry leaves when a delete confirms the delivery or
 * an engine withdraws the item ([forget]); only a row removed from outside every engine of the process leaves a leaked
 * entry of about a hundred bytes.
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

    /** Whether [item] is the very row an earlier delete failure was recorded for (same id and same fingerprint). */
    fun matches(item: UploaderItem): Boolean = entries.value[item.id] == item.fingerprint()

    /**
     * Forgets the entry of [item] when it was recorded for this very row (a delete confirmed, or an engine withdrew
     * the item). An entry recorded for another row of the same id stays.
     */
    fun forget(item: UploaderItem) {
        val print: Fingerprint = item.fingerprint()
        if (entries.value[item.id] == print) entries.update { map -> if (map[item.id] == print) map - item.id else map }
    }
}
