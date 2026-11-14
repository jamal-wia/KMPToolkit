package io.github.jamal_wia.kmptoolkit.video.player.javafx

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.video.player.VideoFrame
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.system.exitProcess

/**
 * Entry point of a child JVM for [PictureRenderD2ScoutProcessRealMediaTest]: plays the checked-in solid red clip on a toolkit
 * started with the JVM flags the parent chose (a render scale or a software pipeline) and prints what the frames looked like as
 * `RESULT key=value` lines. Real media: the parent runs it under the exclusive slot.
 */
@OptIn(ToolkitInternalApi::class)
internal object PictureRenderChildD2ScoutMain {

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val engine = JavaFxVideoEngine(readyTimeoutMs = 20_000L)
            runBlocking { withTimeout(30_000L) { engine.load(VideoSource.Asset(TestClip.ASSET)) } }
            engine.start()
            val frames: MutableList<VideoFrame> = mutableListOf()
            // The frame flow is the producer's own signal; the hang bound is the coroutine timeout, not a clock read.
            runBlocking {
                withTimeoutOrNull(1_500L) {
                    engine.frames.collect { frame: VideoFrame? -> if (frame != null) frames += frame }
                }
            }
            engine.release()
            // The first frames of a stream may be partly decoded; the claim is about the settled picture.
            val settled: List<VideoFrame> = frames.drop(3)
            var badPixels = 0
            for (frame: VideoFrame in settled) {
                for (pixel: Int in frame.pixels) {
                    val a: Int = pixel ushr 24
                    val r: Int = (pixel shr 16) and 0xFF
                    val g: Int = (pixel shr 8) and 0xFF
                    val b: Int = pixel and 0xFF
                    if (a != 0xFF || r < 180 || g > 90 || b > 90) badPixels++
                }
            }
            println("RESULT frames=${frames.size}")
            println("RESULT sizes=${frames.map { it.width to it.height }.distinct()}")
            println("RESULT arraysMatchSize=${frames.all { it.pixels.size == it.width * it.height }}")
            println("RESULT settledFrames=${settled.size}")
            println("RESULT badPixels=$badPixels")
            println("RESULT hidpi=${System.getProperty("prism.allowhidpi")} order=${System.getProperty("prism.order")}")
        } catch (e: Throwable) {
            println("RESULT crashed=${e::class.simpleName}: ${e.message}")
        }
        System.out.flush()
        exitProcess(0)
    }
}
