package io.github.jamal_wia.kmptoolkit.video.player

import platform.AVFoundation.AVPlayerTimeControlStatusPaused
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate
import platform.AVFoundation.AVPlayerWaitingToMinimizeStallsReason
import platform.AVFoundation.AVPlayerWaitingWhileEvaluatingBufferingRateReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure translations from AVFoundation values to the engine contract, no media needed. */
class AvPlayerMappingTest {

    // --- HTTP headers -------------------------------------------------------------------------

    @Test
    fun `remote headers become the AVURLAsset header option`() {
        val headers: Map<String, String> = mapOf("Authorization" to "Bearer t", "X-Trace" to "1")

        val options: Map<Any?, Any?>? = urlAssetOptions(VideoSource.Remote("https://e.x/v.m3u8", headers))

        assertEquals(mapOf<Any?, Any?>("AVURLAssetHTTPHeaderFieldsKey" to headers), options)
    }

    @Test
    fun `a remote source without headers creates the asset with no options at all`() {
        assertNull(urlAssetOptions(VideoSource.Remote("https://e.x/v.mp4")))
        assertNull(urlAssetOptions(VideoSource.Remote("https://e.x/v.mp4", emptyMap())))
    }

    @Test
    fun `local sources never carry header options`() {
        assertNull(urlAssetOptions(VideoSource.File("/tmp/v.mp4")))
        assertNull(urlAssetOptions(VideoSource.Asset("v.mp4")))
    }

    @Test
    fun `the header option is a snapshot not a view of the caller map`() {
        val headers: MutableMap<String, String> = mutableMapOf("Authorization" to "Bearer a")
        val options: Map<Any?, Any?>? = urlAssetOptions(VideoSource.Remote("https://e.x/v.mp4", headers))

        headers["Authorization"] = "Bearer b"

        assertEquals(mapOf("Authorization" to "Bearer a"), options?.get(HTTP_HEADER_FIELDS_OPTION_KEY))
    }

    // --- Picture size -------------------------------------------------------------------------

    @Test
    fun `zero presentation size means no picture`() {
        assertNull(videoSizeOrNull(0.0, 0.0))
        assertNull(videoSizeOrNull(1920.0, 0.0))
        assertNull(videoSizeOrNull(0.0, 1080.0))
    }

    @Test
    fun `non finite or negative presentation size means no picture`() {
        assertNull(videoSizeOrNull(Double.NaN, 1080.0))
        assertNull(videoSizeOrNull(1920.0, Double.POSITIVE_INFINITY))
        assertNull(videoSizeOrNull(-1920.0, 1080.0))
    }

    @Test
    fun `a presentation size maps to whole pixels in the reported orientation`() {
        assertEquals(VideoSize(1920, 1080), videoSizeOrNull(1920.0, 1080.0))
        assertEquals(VideoSize(1080, 1920), videoSizeOrNull(1080.0, 1920.0))
        assertEquals(VideoSize(641, 360), videoSizeOrNull(640.6, 360.2))
    }

    @Test
    fun `a sub pixel size that rounds to zero is no picture`() {
        assertNull(videoSizeOrNull(0.4, 0.4))
    }

    // --- Buffering ----------------------------------------------------------------------------

    @Test
    fun `waiting to minimize stalls is buffering`() {
        assertTrue(
            isStalled(AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate, AVPlayerWaitingToMinimizeStallsReason)
        )
    }

    @Test
    fun `waiting for an unknown or missing reason is buffering`() {
        assertTrue(isStalled(AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate, null))
    }

    @Test
    fun `evaluating the buffering rate at start is not buffering`() {
        assertFalse(
            isStalled(
                AVPlayerTimeControlStatusWaitingToPlayAtSpecifiedRate,
                AVPlayerWaitingWhileEvaluatingBufferingRateReason,
            )
        )
    }

    @Test
    fun `playing or paused is never buffering`() {
        assertFalse(isStalled(AVPlayerTimeControlStatusPlaying, null))
        assertFalse(isStalled(AVPlayerTimeControlStatusPaused, null))
        assertFalse(isStalled(AVPlayerTimeControlStatusPaused, AVPlayerWaitingToMinimizeStallsReason))
    }

    // --- Buffered position --------------------------------------------------------------------

    @Test
    fun `buffered position is the end of the range holding the playhead`() {
        val ranges: List<Pair<Long, Long>> = listOf(0L to 4_000L, 10_000L to 12_000L)

        assertEquals(4_000L, bufferedEndMs(ranges, 1_000L))
        assertEquals(12_000L, bufferedEndMs(ranges, 11_000L))
    }

    @Test
    fun `with nothing buffered ahead the buffered position is the playhead`() {
        assertEquals(7_000L, bufferedEndMs(listOf(0L to 4_000L, 10_000L to 12_000L), 7_000L))
        assertEquals(3_000L, bufferedEndMs(emptyList(), 3_000L))
    }

    @Test
    fun `nothing loaded at the start reports zero`() {
        assertEquals(0L, bufferedEndMs(emptyList(), 0L))
    }

    @Test
    fun `a range starting just after a seek target still counts`() {
        assertEquals(9_000L, bufferedEndMs(listOf(5_100L to 9_000L), 5_000L))
        assertEquals(5_000L, bufferedEndMs(listOf(5_000L + BUFFERED_RANGE_TOLERANCE_MS + 1 to 9_000L), 5_000L))
    }

    @Test
    fun `a whole local file is buffered to its end`() {
        assertEquals(60_000L, bufferedEndMs(listOf(0L to 60_000L), 0L))
    }

    // --- Time ---------------------------------------------------------------------------------

    @Test
    fun `unknown CMTime seconds become zero milliseconds`() {
        assertEquals(0L, secondsToMillis(Double.NaN))
        assertEquals(0L, secondsToMillis(Double.POSITIVE_INFINITY))
        assertEquals(0L, secondsToMillis(-1.0))
        assertEquals(0L, secondsToMillis(0.0))
    }

    @Test
    fun `known seconds become whole milliseconds`() {
        assertEquals(1_500L, secondsToMillis(1.5))
        assertEquals(1L, secondsToMillis(0.0019))
    }

    // --- Errors -------------------------------------------------------------------------------

    @Test
    fun `an item failure without an NSError still explains itself`() {
        assertEquals("AVPlayerItem failed: unknown error", itemFailure(null).message)
    }
}
