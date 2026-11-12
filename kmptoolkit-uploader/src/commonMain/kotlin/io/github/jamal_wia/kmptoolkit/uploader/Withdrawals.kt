package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.e
import io.github.jamal_wia.kmptoolkit.logging.i

/**
 * Rule P8, the side effects of a removed row, and the only code that cancels an upload (`UploadTransport.cancel`): the
 * attempt the engine runs for the row is stopped and its upload is cancelled, **whatever the row's state, parked
 * included** (a parked row can still have an earlier claim's upload running: a re-hand that parks does not cancel that
 * upload, and an iOS background transfer outlives the process). Cancelling an id the transport does not run is a no-op.
 *
 * The three entry points are the three situations the protocol allows: a withdrawal ([onRemoved]), the end of a
 * re-hand that dropped the item ([onRehandDrop]) and a hand-off that finds its row gone after the launch
 * ([onRemovedDuringHandOff]). None of them suspends or throws (a transport that throws is logged), and none of them
 * touches the delivered-but-unconfirmed memory (P1): only a confirming delete of the [RowLedger] does.
 */
internal class Withdrawals(
    private val handlersByType: Map<String, UploaderHandler<*>>,
    private val attempts: AttemptRegistry,
    private val logger: Logger,
) {

    /**
     * A withdrawal removed [rows] (the store call that removed them has returned): stops each attempt first, then
     * cancels each upload. P1 order.
     */
    fun onRemoved(rows: List<UploaderItem>, reason: String) {
        rows.forEach { row -> attempts.stop(row.id) }
        rows.forEach { row -> cancelUpload(row, reason) }
    }

    /**
     * A re-hand of an expired lease dropped [row] (applied): the earlier claim's transfer, which a re-hand that never
     * launched did not replace, is cancelled.
     */
    fun onRehandDrop(row: UploaderItem) {
        cancelUpload(row, reason = "dropped at its re-hand")
    }

    /** A hand-off found [row] removed after its launch, so the upload it just launched is cancelled. */
    fun onRemovedDuringHandOff(row: UploaderItem) {
        cancelUpload(row, reason = "removed during its hand-off")
    }

    /** Cancels [record]'s upload when its handler is an [UploadHandler], whose transport the engine knows. */
    private fun cancelUpload(record: UploaderItem, reason: String) {
        val handler: UploadHandler<*> = handlersByType[record.type] as? UploadHandler<*> ?: run {
            logger.i { "Withdrew ${record.logName} — $reason." }
            return
        }
        try {
            handler.transport.cancel(record.id)
            logger.i { "Withdrew ${record.logName} and cancelled its upload — $reason." }
        } catch (e: Throwable) {
            // The row is already gone; a transport that throws must not fail the caller's withdrawal.
            logger.e(e) { "Withdrew ${record.logName}, but cancelling its upload failed — $reason." }
        }
    }
}
