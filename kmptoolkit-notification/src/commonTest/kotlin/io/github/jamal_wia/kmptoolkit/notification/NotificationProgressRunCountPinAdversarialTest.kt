package io.github.jamal_wia.kmptoolkit.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.TestTimeSource

/**
 * KDoc of `NotificationConfig.progressBucketPercent`: "a per-byte loop turns into at most
 * `100 / progressBucketPercent + 1` posts — as long as only the percentage changes."
 *
 * Pins what a 0..100 run with the rate limit off really posts, for every legal bucket width: the
 * buckets of 0..99 plus the never-suppressed terminal frame. The documented bound (`100 / width + 1`)
 * holds only for widths that divide 100; the suspect that the KDoc understates the count by one for
 * the others is reported separately (suspects/r11-W3.kt).
 */
class NotificationProgressRunCountPinAdversarialTest {

    private fun postsOfAFullRun(bucketPercent: Int): Int {
        val coalescer = ProgressCoalescer(bucketPercent, Duration.ZERO, TestTimeSource())
        var posts = 0
        for (percent: Int in 0..100) {
            if (coalescer.prepare("a", NotificationProgress.Determinate(percent), null) != null) posts++
        }
        return posts
    }

    @Test
    fun `pin - a full run posts the buckets of 0 to 99 plus the terminal frame`() {
        for (width: Int in 1..100) {
            val expected: Int = (100 + width - 1) / width + 1
            assertEquals(expected, postsOfAFullRun(width), "width $width")
        }
    }

    @Test
    fun `pin - a width that divides 100 meets the documented bound exactly`() {
        for (width: Int in listOf(1, 2, 4, 5, 10, 20, 25, 50, 100)) {
            assertEquals(100 / width + 1, postsOfAFullRun(width), "width $width")
        }
    }
}
