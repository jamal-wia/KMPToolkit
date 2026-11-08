package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrame
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The desktop frame renderer against hostile frames and long churn: a frame is ignored when it cannot
 * be a picture, a size change reallocates, close and clear are idempotent and drop the picture, and
 * the alpha byte is forced opaque.
 */
@OptIn(ToolkitInternalApi::class)
class FrameRendererAdversarialTest {

    private fun frame(width: Int, height: Int, argb: Int = 0x00112233): VideoFrame =
        VideoFrame(width, height, IntArray(width * height) { argb })

    @Test
    fun `frames that cannot be a picture are ignored and leave the previous picture alone`() {
        val renderer = FrameRenderer()
        renderer.update(frame(4, 2))
        val shown: ImageBitmap = assertNotNull(renderer.image)

        renderer.update(VideoFrame(0, 5, IntArray(0)))
        renderer.update(VideoFrame(5, 0, IntArray(0)))
        renderer.update(VideoFrame(-3, 4, IntArray(12)))
        renderer.update(VideoFrame(4, -3, IntArray(12)))
        renderer.update(VideoFrame(10, 10, IntArray(99)))
        renderer.update(VideoFrame(Int.MAX_VALUE, Int.MAX_VALUE, IntArray(4)))
        renderer.update(VideoFrame(46_341, 46_341, IntArray(4)))
        renderer.update(VideoFrame(Int.MAX_VALUE, 1, IntArray(4)))

        assertSame(shown, renderer.image, "a bad frame replaced or dropped the picture")
        renderer.close()
    }

    @Test
    fun `the alpha byte is forced opaque and the pixels arrive in order`() {
        val renderer = FrameRenderer()
        renderer.update(VideoFrame(2, 1, intArrayOf(0x00FF0000, 0x0000FF00)))
        val map = assertNotNull(renderer.image).toPixelMap()
        assertEquals(1f, map[0, 0].alpha)
        assertEquals(1f, map[0, 0].red)
        assertEquals(1f, map[1, 0].green)
        renderer.close()
    }

    @Test
    fun `alternating frame sizes for a long run reallocate cleanly and end freed`() {
        val renderer = FrameRenderer()
        repeat(400) { i ->
            val w: Int = 2 + (i % 7)
            val h: Int = 3 + (i % 5)
            renderer.update(frame(w, h, argb = i))
            val image: ImageBitmap = assertNotNull(renderer.image, "iteration $i")
            assertEquals(w, image.width)
            assertEquals(h, image.height)
        }
        renderer.close()
        assertNull(renderer.image)
        renderer.close()
        renderer.clear()
        assertNull(renderer.image)
    }

    @Test
    fun `a renderer shows a picture again after it was cleared or closed`() {
        val renderer = FrameRenderer()
        renderer.update(frame(3, 3))
        renderer.clear()
        assertNull(renderer.image)
        renderer.update(frame(3, 3))
        assertNotNull(renderer.image)
        renderer.close()
        renderer.update(frame(5, 2))
        assertEquals(5, assertNotNull(renderer.image).width)
        renderer.close()
    }

    @Test
    fun `a frame larger than the pixel array is ignored even after a good one of the same size`() {
        val renderer = FrameRenderer()
        renderer.update(frame(6, 6))
        val shown = assertNotNull(renderer.image)
        renderer.update(VideoFrame(6, 6, IntArray(35)))
        assertSame(shown, renderer.image)
        renderer.close()
    }
}
