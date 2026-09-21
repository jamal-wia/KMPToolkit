package io.github.jamal_wia.kmptoolkit.video.player.compose

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import io.github.jamal_wia.kmptoolkit.video.player.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrame
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrameSource
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoSize
import io.github.jamal_wia.kmptoolkit.video.player.frameSourceOrNull
import java.nio.ByteOrder
import java.nio.IntBuffer
import kotlin.math.roundToInt
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Pixmap
import org.jetbrains.skia.impl.BufferUtil

/**
 * Desktop: draws the frames of the memory-rendering engine behind [player]. There is no screen
 * saver to hold off from here, so [keepScreenOn] does nothing.
 */
@OptIn(ToolkitInternalApi::class)
@Composable
internal actual fun PlatformVideoSurface(
    player: VideoPlayer,
    modifier: Modifier,
    scaleMode: VideoScaleMode,
    keepScreenOn: Boolean,
) {
    val frameSource: VideoFrameSource? = remember(player) { player.frameSourceOrNull() }
    val videoSize: State<VideoSize?> = player.videoSizeFlow.collectAsState()
    FrameSourceSurface(frameSource, modifier, scaleMode, pictureSizeKnown = videoSize.value != null)
}

/**
 * Draws the latest frame of [frameSource]; nothing while it has none or is `null`. Frames are copied
 * into one reused native bitmap on the UI thread, then only the draw phase is invalidated — no
 * recomposition per frame.
 *
 * **Placement.** With [pictureSizeKnown], [VideoPlayerSurface] has already sized this surface to the
 * picture as it is meant to be displayed — the engine's reported size, pixel aspect and rotation
 * corrected — so the frame is stretched over the whole surface: its stored pixel grid (1440 × 1080
 * for a 16:9 anamorphic source, say) is not the display ratio, and fitting it again by its own ratio
 * would squeeze the picture. Only while no size is known does the bitmap's own ratio place it, by
 * [scaleMode].
 */
@OptIn(ToolkitInternalApi::class)
@Composable
internal fun FrameSourceSurface(
    frameSource: VideoFrameSource?,
    modifier: Modifier,
    scaleMode: VideoScaleMode,
    pictureSizeKnown: Boolean,
) {
    val renderer: FrameRenderer = remember { FrameRenderer() }
    val frameVersion: MutableIntState = remember { mutableIntStateOf(0) }
    DisposableEffect(renderer) {
        onDispose { renderer.close() }
    }
    LaunchedEffect(frameSource) {
        if (frameSource == null) {
            renderer.clear()
            frameVersion.intValue++
            return@LaunchedEffect
        }
        frameSource.frames.collect { frame ->
            if (frame == null) renderer.clear() else renderer.update(frame)
            frameVersion.intValue++
        }
    }
    Canvas(modifier.clipToBounds()) {
        frameVersion.intValue // Read so each new frame invalidates this draw.
        val image: ImageBitmap = renderer.image ?: return@Canvas
        val fitted: FrameSize = fitVideoFrame(
            contentAspectRatio = image.width.toFloat() / image.height.toFloat(),
            boxWidth = size.width,
            boxHeight = size.height,
            mode = if (pictureSizeKnown) VideoScaleMode.Fill else scaleMode,
        )
        val width: Int = fitted.width.roundToInt()
        val height: Int = fitted.height.roundToInt()
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset(
                ((size.width - width) / 2f).roundToInt(),
                ((size.height - height) / 2f).roundToInt(),
            ),
            dstSize = IntSize(width, height),
            filterQuality = FilterQuality.Low,
        )
    }
}

/**
 * One native Skia bitmap, reallocated only when the frame size changes, that each frame's pixels
 * are copied into — through one reused scratch array into native memory, with
 * no conversion to bytes. A 32-bit ARGB int stored little-endian is exactly Skia's BGRA_8888 byte
 * order. The alpha byte is forced opaque on the way: decoders leave it undefined.
 *
 * Confined to the UI thread, where both [update] and drawing run.
 */
@OptIn(ToolkitInternalApi::class)
internal class FrameRenderer : AutoCloseable {

    private var bitmap: Bitmap? = null
    private var pixmap: Pixmap? = null
    private var pixels: IntBuffer? = null
    private var scratch: IntArray = IntArray(0)

    /** The bitmap as Compose draws it, or `null` before the first frame and after [clear]. */
    var image: ImageBitmap? = null
        private set

    fun update(frame: VideoFrame) {
        val width: Int = frame.width
        val height: Int = frame.height
        if (width <= 0 || height <= 0) return
        val count: Long = width.toLong() * height.toLong()
        if (count > Int.MAX_VALUE / 4 || frame.pixels.size < count) return
        val current: Bitmap? = bitmap
        val target: IntBuffer = if (current == null || current.width != width || current.height != height) {
            allocate(width, height) ?: return
        } else {
            pixels ?: return
        }
        val size: Int = count.toInt()
        // Force the alpha byte opaque: decoders leave it undefined (often 0). A simple loop the JIT
        // vectorises, then one bulk copy into native memory.
        val opaque: IntArray = scratch.takeIf { it.size >= size } ?: IntArray(size).also { scratch = it }
        val source: IntArray = frame.pixels
        for (i in 0 until size) opaque[i] = source[i] or OPAQUE_ALPHA
        target.clear()
        target.put(opaque, 0, size)
        bitmap?.notifyPixelsChanged()
    }

    /** Drops the picture, so nothing is drawn until the next [update]. */
    fun clear() {
        close()
    }

    override fun close() {
        image = null
        scratch = IntArray(0)
        pixels = null
        pixmap?.close()
        pixmap = null
        bitmap?.close()
        bitmap = null
    }

    private fun allocate(width: Int, height: Int): IntBuffer? {
        close()
        val info = ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)
        val newBitmap = Bitmap()
        if (!newBitmap.allocPixels(info, width * 4)) {
            newBitmap.close()
            return null
        }
        val newPixmap: Pixmap = newBitmap.peekPixels() ?: run {
            newBitmap.close()
            return null
        }
        val buffer: IntBuffer = BufferUtil
            .getByteBufferFromPointer(newPixmap.addr, width * height * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
            .asIntBuffer()
        bitmap = newBitmap
        pixmap = newPixmap
        pixels = buffer
        image = newBitmap.asComposeImageBitmap()
        return buffer
    }
}

private const val OPAQUE_ALPHA: Int = -0x1000000 // 0xFF000000

@Composable
internal actual fun platformVideoPlayerFactory(): VideoPlayerFactory? = null
