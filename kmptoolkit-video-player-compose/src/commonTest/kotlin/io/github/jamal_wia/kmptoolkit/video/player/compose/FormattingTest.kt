package io.github.jamal_wia.kmptoolkit.video.player.compose

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The pure functions behind the time label, the speed button and the seek bar. */
class FormattingTest {

    @Test
    fun `a time under an hour is m colon ss`() {
        assertEquals("0:00", formatVideoTime(0L))
        assertEquals("0:05", formatVideoTime(5_000L))
        assertEquals("1:05", formatVideoTime(65_000L))
        assertEquals("59:59", formatVideoTime(3_599_999L))
    }

    @Test
    fun `a time from an hour is h colon mm colon ss`() {
        assertEquals("1:00:00", formatVideoTime(3_600_000L))
        assertEquals("1:02:03", formatVideoTime(3_723_000L))
        assertEquals("12:00:09", formatVideoTime(43_209_000L))
    }

    @Test
    fun `a reference duration of an hour or more puts a short time into the hour form`() {
        assertEquals("0:01:05", formatVideoTime(65_000L, referenceDurationMs = 3_600_000L))
        assertEquals("1:05", formatVideoTime(65_000L, referenceDurationMs = 3_599_999L))
    }

    @Test
    fun `a time rounds down to the second already reached`() {
        assertEquals("0:00", formatVideoTime(999L))
        assertEquals("0:59", formatVideoTime(59_999L))
    }

    @Test
    fun `a negative time is shown as zero`() {
        assertEquals("0:00", formatVideoTime(-5_000L))
        assertEquals("0:00", formatVideoTime(Long.MIN_VALUE))
    }

    @Test
    fun `the default speed format drops trailing zeros`() {
        assertEquals("1×", VideoControlsDefaults.formatSpeed(1f))
        assertEquals("1.5×", VideoControlsDefaults.formatSpeed(1.5f))
        assertEquals("0.75×", VideoControlsDefaults.formatSpeed(0.75f))
        assertEquals("2×", VideoControlsDefaults.formatSpeed(2f))
        assertEquals("0.25×", VideoControlsDefaults.formatSpeed(0.25f))
    }

    @Test
    fun `the next speed wraps after the last entry`() {
        val speeds: List<Float> = listOf(0.5f, 1f, 2f)
        assertEquals(2f, nextSpeed(1f, speeds))
        assertEquals(0.5f, nextSpeed(2f, speeds))
    }

    @Test
    fun `a speed not in the list moves to the first entry above it`() {
        val speeds: List<Float> = listOf(0.5f, 1f, 2f)
        assertEquals(2f, nextSpeed(1.3f, speeds))
        assertEquals(0.5f, nextSpeed(3f, speeds))
    }

    @Test
    fun `fit letterboxes a wide picture in a square`() {
        val frame: FrameSize = fitVideoFrame(2f, 100f, 100f, VideoScaleMode.Fit)
        assertEquals(100f, frame.width)
        assertEquals(50f, frame.height)
    }

    @Test
    fun `crop covers a square with a wide picture`() {
        val frame: FrameSize = fitVideoFrame(2f, 100f, 100f, VideoScaleMode.Crop)
        assertEquals(200f, frame.width)
        assertEquals(100f, frame.height)
    }

    @Test
    fun `fill stretches to the box`() {
        val frame: FrameSize = fitVideoFrame(2f, 100f, 100f, VideoScaleMode.Fill)
        assertEquals(100f, frame.width)
        assertEquals(100f, frame.height)
    }

    @Test
    fun `fit and crop pillarbox a tall picture`() {
        val fit: FrameSize = fitVideoFrame(0.5f, 100f, 100f, VideoScaleMode.Fit)
        assertEquals(50f, fit.width)
        assertEquals(100f, fit.height)
        val crop: FrameSize = fitVideoFrame(0.5f, 100f, 100f, VideoScaleMode.Crop)
        assertEquals(100f, crop.width)
        assertEquals(200f, crop.height)
    }

    @Test
    fun `an unknown or degenerate ratio fills the box`() {
        for (ratio in listOf(null, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val frame: FrameSize = fitVideoFrame(ratio, 120f, 80f, VideoScaleMode.Fit)
            assertEquals(120f, frame.width, "ratio $ratio")
            assertEquals(80f, frame.height, "ratio $ratio")
        }
    }

    @Test
    fun `an empty box stays empty`() {
        val frame: FrameSize = fitVideoFrame(2f, 0f, 100f, VideoScaleMode.Crop)
        assertEquals(0f, frame.width)
        assertEquals(100f, frame.height)
    }

    @Test
    fun `a seek bar position maps through the inset track and mirrors in rtl`() {
        assertEquals(0f, fractionAt(0f, 110f, 5f, rtl = false))
        assertEquals(0.5f, fractionAt(55f, 110f, 5f, rtl = false))
        assertEquals(1f, fractionAt(200f, 110f, 5f, rtl = false))
        assertEquals(0.25f, fractionAt(80f, 110f, 5f, rtl = true))
        assertEquals(0f, fractionAt(50f, 8f, 5f, rtl = false))
    }

    @Test
    fun `a fraction of an unknown duration is zero and never exceeds one`() {
        assertEquals(0f, fractionOf(5_000L, 0L))
        assertEquals(0f, fractionOf(5_000L, -1L))
        assertEquals(1f, fractionOf(150L, 100L))
        assertEquals(0.25f, fractionOf(25L, 100L))
    }

    @Test
    fun `an auto hide delay that is not positive is rejected`() {
        assertFailsWith<IllegalArgumentException> { requireAutoHideDelay(0L) }
        assertFailsWith<IllegalArgumentException> { requireAutoHideDelay(-1L) }
        requireAutoHideDelay(1L)
    }
}
