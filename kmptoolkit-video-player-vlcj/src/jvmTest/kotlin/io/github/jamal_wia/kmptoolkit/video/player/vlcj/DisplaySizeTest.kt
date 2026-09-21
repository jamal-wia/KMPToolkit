package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DisplaySizeTest {

    @Test
    fun `square pixels are shown at the stored size`() {
        assertEquals(VideoSize(1920, 1080), displaySize(1920, 1080, sar = 1, sarBase = 1, orientation = "TOP_LEFT"))
        assertEquals(VideoSize(640, 360), displaySize(640, 360, sar = 0, sarBase = 0, orientation = null))
    }

    @Test
    fun `an anamorphic source is widened by its sample aspect ratio`() {
        // DVD NTSC 16:9: 720x480 stored, pixels 32:27 wide.
        assertEquals(VideoSize(853, 480), displaySize(720, 480, sar = 32, sarBase = 27, orientation = null))
        // HDV: 1440x1080 stored, pixels 4:3 wide, shown at 1920x1080.
        assertEquals(VideoSize(1920, 1080), displaySize(1440, 1080, sar = 4, sarBase = 3, orientation = "TOP_LEFT"))
    }

    @Test
    fun `a quarter-turn orientation swaps the sides, after the aspect correction`() {
        assertEquals(VideoSize(1080, 1920), displaySize(1920, 1080, sar = 1, sarBase = 1, orientation = "RIGHT_TOP"))
        assertEquals(VideoSize(480, 853), displaySize(720, 480, sar = 32, sarBase = 27, orientation = "LEFT_BOTTOM"))
        assertEquals(VideoSize(1920, 1080), displaySize(1920, 1080, sar = 1, sarBase = 1, orientation = "BOTTOM_RIGHT"))
    }

    @Test
    fun `a track without a size has no display size`() {
        assertNull(displaySize(0, 480, sar = 1, sarBase = 1, orientation = null))
        assertNull(displaySize(720, 0, sar = 1, sarBase = 1, orientation = null))
        assertNull(displaySize(-1, -1, sar = 1, sarBase = 1, orientation = null))
    }

    @Test
    fun `an extreme sample aspect ratio never collapses the width to zero`() {
        assertEquals(VideoSize(1, 100), displaySize(2, 100, sar = 1, sarBase = 1_000, orientation = null))
    }
}
