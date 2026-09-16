package io.github.jamal_wia.kmptoolkit.uploader

/**
 * Quantizes a running byte count against a total into whole percents, handing each percent out once,
 * so a transport reports at most 101 progress signals for an upload however it is chunked.
 *
 * Shared by the platform transports so the only arithmetic in the progress chain is tested once.
 */
internal class WholePercentProgress(
    private val totalBytes: Long,
    private val onWholePercent: (fraction: Float) -> Unit,
) {
    private var countedBytes: Long = 0L
    private var lastReportedPercent: Int = PERCENT_UNREPORTED

    /** Adds [bytes] to the running count. */
    fun add(bytes: Long) {
        countedBytes += bytes
        report(countedBytes)
    }

    /** Sets the running count to [bytes], for a platform that reports a cumulative total. */
    fun set(bytes: Long) {
        countedBytes = bytes
        report(countedBytes)
    }

    private fun report(bytes: Long) {
        if (totalBytes <= 0L) return
        val percent: Int = ((bytes * PERCENT_SCALE) / totalBytes).toInt().coerceIn(0, PERCENT_SCALE)
        if (percent == lastReportedPercent) return
        lastReportedPercent = percent
        onWholePercent(percent / PERCENT_SCALE.toFloat())
    }

    private companion object {
        const val PERCENT_SCALE: Int = 100
        const val PERCENT_UNREPORTED: Int = -1
    }
}
