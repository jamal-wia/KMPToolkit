package io.github.jamal_wia.kmptoolkit.uploader.spi

import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem

/**
 * What [UploaderStore.insertKeep] did.
 *
 * It names the row a KEEP removed for the same reason [UploaderStore.insertReplace] returns its superseded row: the
 * engine cancels the upload of every row a conflict policy removes, and a read made beforehand would not do (an item
 * enqueued between that read and the insert would lose its row without its upload ever being cancelled).
 *
 * @since 5.0.0
 */
public sealed interface KeepResult {

    /** An existing PENDING or IN_FLIGHT item holds the identity and won: nothing was inserted, nothing was removed. */
    public data object Kept : KeepResult

    /**
     * The record was inserted.
     *
     * @property superseded the PARKED item the insert replaced, as it was just before its removal, or `null` when no
     *   item held the identity.
     */
    public data class Inserted(val superseded: UploaderItem?) : KeepResult
}
