package io.github.jamal_wia.kmptoolkit.video.player

import kotlin.math.roundToInt
import platform.AVFoundation.AVPlayerTimeControlStatus
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.AVPlayerWaitingWhileEvaluatingBufferingRateReason

// Pure translations from what AVFoundation reports to what VideoPlaybackEngineListener and the
// polled getters promise. Kept apart from AvPlayerVideoEngine so they are testable without media.

/**
 * The option key that makes `AVURLAsset` send extra HTTP headers with every request it issues,
 * HLS playlist and segment requests included. Apple has never declared it in a public header — it
 * is widely used and has worked since iOS 7, but it is not formally documented, which the platform
 * notes say out loud. Spelled as a string because there is no constant to import.
 */
internal const val HTTP_HEADER_FIELDS_OPTION_KEY: String = "AVURLAssetHTTPHeaderFieldsKey"

/**
 * The `AVURLAsset` creation options for [source]: the HTTP headers of a [VideoSource.Remote] that
 * has any, otherwise `null` (no options at all, so a source without headers is created exactly as
 * AVFoundation's defaults would create it).
 */
internal fun urlAssetOptions(source: VideoSource): Map<Any?, Any?>? {
    val headers: Map<String, String> = (source as? VideoSource.Remote)?.headers ?: return null
    if (headers.isEmpty()) return null
    return mapOf(HTTP_HEADER_FIELDS_OPTION_KEY to headers.toMap())
}

/**
 * `AVPlayerItem.presentationSize` as a [VideoSize], or `null` while there is no picture —
 * AVFoundation reports `CGSizeZero` before the first video track is known and for an audio-only
 * source. The size is already the displayed orientation.
 */
internal fun videoSizeOrNull(width: Double, height: Double): VideoSize? {
    if (!width.isFinite() || !height.isFinite()) return null
    val roundedWidth: Int = width.roundToInt()
    val roundedHeight: Int = height.roundToInt()
    if (roundedWidth <= 0 || roundedHeight <= 0) return null
    return VideoSize(roundedWidth, roundedHeight)
}

/**
 * Whether the player is stalled waiting for data. `WaitingToPlayAtSpecifiedRate` alone is not
 * enough: AVPlayer passes through it with the "evaluating buffering rate" reason on every start,
 * local files included, for a few milliseconds — reporting that would flash a spinner on every
 * `play()`. If the data really is short, the reason moves on to "to minimize stalls" and this
 * reports it.
 */
internal fun isStalled(status: AVPlayerTimeControlStatus, waitingReason: String?): Boolean =
    status == AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate &&
        waitingReason != AVPlayerWaitingWhileEvaluatingBufferingRateReason

/**
 * The buffered-ahead position from `AVPlayerItem.loadedTimeRanges`, given as `(startMs, endMs)`
 * pairs: the end of the range the playhead is in. A range that starts just after the playhead
 * still counts (a seek lands a little before the first loaded sample). When no range covers the
 * playhead nothing is buffered ahead of it, so the answer is the playhead itself.
 */
internal fun bufferedEndMs(rangesMs: List<Pair<Long, Long>>, positionMs: Long): Long {
    val covering: Pair<Long, Long>? = rangesMs.firstOrNull { (startMs: Long, endMs: Long) ->
        startMs <= positionMs + BUFFERED_RANGE_TOLERANCE_MS && endMs >= positionMs
    }
    return maxOf(covering?.second ?: positionMs, positionMs, 0L)
}

/** How far after the playhead a loaded range may start and still count as covering it. */
internal const val BUFFERED_RANGE_TOLERANCE_MS: Long = 250L

/** Seconds as whole milliseconds; `0` for the NaN/infinite/negative values CMTime yields when unknown. */
internal fun secondsToMillis(seconds: Double): Long {
    if (!seconds.isFinite() || seconds <= 0.0) return 0L
    return (seconds * 1000.0).toLong()
}
