package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class FrameConversionTest {

    @Test
    fun `RV32 words become opaque ARGB whatever the padding byte holds`() {
        val source: ByteBuffer = ByteBuffer.allocateDirect(3 * 4).order(ByteOrder.nativeOrder())
        source.putInt(0x00FF0000) // red, padding 0
        source.putInt(0x1200FF00) // green, padding garbage
        source.putInt(0x000000FF) // blue
        // Left at the end on purpose: the conversion must not depend on the buffer's position.
        val target = IntArray(3)

        copyRv32ToArgb(source, target, pixelCount = 3)

        assertContentEquals(
            intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt()),
            target,
        )
    }

    @Test
    fun `only the requested pixel count is copied`() {
        val source: ByteBuffer = ByteBuffer.allocateDirect(2 * 4).order(ByteOrder.nativeOrder())
        source.putInt(0x00010203).putInt(0x00040506)
        val target = IntArray(2) { 7 }

        copyRv32ToArgb(source, target, pixelCount = 1)

        assertContentEquals(intArrayOf(0xFF010203.toInt(), 7), target)
    }

    @Test
    fun `full volume is VLC's unamplified 100 percent, never beyond`() {
        assertEquals(0, volumePercent(0f))
        assertEquals(50, volumePercent(0.5f))
        assertEquals(100, volumePercent(1f))
        assertEquals(100, volumePercent(4f))
        assertEquals(0, volumePercent(-1f))
    }
}
