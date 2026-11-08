package io.github.jamal_wia.kmptoolkit.video.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Extreme values AVFoundation can hand to the pure mapping: huge, tiny, and the edges of Long and Int. */
class AvPlayerMappingRound5AdversarialTest {

    @Test
    fun `a playhead at the top of the Long range is not undone by the tolerance addition`() {
        val position: Long = Long.MAX_VALUE - 10L
        val buffered: Long = bufferedEndMs(listOf(0L to Long.MAX_VALUE), position)
        assertTrue(buffered >= position, "buffered $buffered behind the playhead $position")
    }

    @Test
    fun `a huge seconds value saturates instead of wrapping`() {
        assertEquals(Long.MAX_VALUE, secondsToMillis(1e300))
        assertEquals(0L, secondsToMillis(Double.NEGATIVE_INFINITY))
        assertEquals(0L, secondsToMillis(Double.NaN))
        assertEquals(1L, secondsToMillis(0.001))
    }

    @Test
    fun `an enormous presentation size saturates to a valid size and never throws`() {
        val size: VideoSize? = videoSizeOrNull(1e12, 1e12)
        assertTrue(size != null && size.width > 0 && size.height > 0)
        assertNull(videoSizeOrNull(Double.NaN, 1.0))
        assertNull(videoSizeOrNull(1.0, Double.POSITIVE_INFINITY))
        assertNull(videoSizeOrNull(-1.0, 1.0))
    }

    @Test
    fun `buffered ranges that are inverted or negative never report behind the playhead`() {
        val position: Long = 5_000L
        val buffered: Long = bufferedEndMs(listOf(9_000L to 1_000L, -5L to -1L), position)
        assertTrue(buffered >= position, "buffered $buffered behind the playhead $position")
    }
}
