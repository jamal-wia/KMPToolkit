package io.github.jamal_wia.kmptoolkit.video.player

import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlin.test.fail
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.AVFoundation.AVAssetWriter
import platform.AVFoundation.AVAssetWriterInput
import platform.AVFoundation.AVAssetWriterInputPixelBufferAdaptor
import platform.AVFoundation.AVAssetWriterStatusCompleted
import platform.AVFoundation.AVFileTypeQuickTimeMovie
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVVideoCodecKey
import platform.AVFoundation.AVVideoCodecTypeH264
import platform.AVFoundation.AVVideoHeightKey
import platform.AVFoundation.AVVideoWidthKey
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.kCMTimeZero
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSRunLoop
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.dateWithTimeIntervalSinceNow
import platform.Foundation.runUntilDate
import platform.Foundation.timeIntervalSinceNow

/**
 * Runs [block] as a coroutine on `Dispatchers.Main` and pumps the main run loop until it finishes.
 *
 * Kotlin/Native tests run on the main thread, and the engine does its AVFoundation work on the main
 * queue — blocking this thread (`runBlocking`, `runTest`) would deadlock it. Pumping the run loop
 * is what lets the main queue, AVFoundation's notifications and `delay` on Main actually run.
 */
internal fun runOnMainLoop(timeoutSeconds: Double = 20.0, block: suspend CoroutineScope.() -> Unit) {
    val done = AtomicInt(0)
    val failure = AtomicReference<Throwable?>(null)
    val job: Job = CoroutineScope(Dispatchers.Main).launch {
        try {
            block()
        } catch (thrown: Throwable) {
            failure.value = thrown
        } finally {
            done.value = 1
        }
    }
    if (!pumpMainLoopUntil(timeoutSeconds) { done.value == 1 }) {
        job.cancel()
        pumpMainLoopUntil(1.0) { done.value == 1 }
        fail("The test body did not finish within $timeoutSeconds s")
    }
    failure.value?.let { thrown: Throwable -> throw thrown }
}

/** Pumps the main run loop until [condition] holds; `false` on timeout. */
internal fun pumpMainLoopUntil(timeoutSeconds: Double, condition: () -> Boolean): Boolean {
    val deadline: NSDate = NSDate.dateWithTimeIntervalSinceNow(timeoutSeconds)
    while (!condition()) {
        if (deadline.timeIntervalSinceNow < 0.0) return false
        NSRunLoop.mainRunLoop.runUntilDate(NSDate.dateWithTimeIntervalSinceNow(0.01))
    }
    return true
}

/** Suspends (inside [runOnMainLoop]) until [condition] holds; `false` on timeout. */
internal suspend fun awaitCondition(timeoutMs: Long, condition: () -> Boolean): Boolean {
    var waitedMs = 0L
    while (!condition()) {
        if (waitedMs >= timeoutMs) return false
        delay(POLL_MS)
        waitedMs += POLL_MS
    }
    return true
}

private const val POLL_MS: Long = 20L

/**
 * Writes a short silent H.264 QuickTime movie of [frameCount] frames (content unspecified) at
 * [fps] and returns its path. Generated at test time because Kotlin/Native test binaries have no convenient way to bundle
 * a media file. Call on the main thread, outside [runOnMainLoop] (it pumps the run loop itself).
 */
@OptIn(ExperimentalForeignApi::class)
internal fun writeTestMovie(
    name: String,
    width: Int = TEST_MOVIE_WIDTH,
    height: Int = TEST_MOVIE_HEIGHT,
    frameCount: Int = TEST_MOVIE_FPS,
    fps: Int = TEST_MOVIE_FPS,
): String {
    val path = "${NSTemporaryDirectory()}$name.mov"
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)

    val writer: AVAssetWriter = AVAssetWriter(
        uRL = NSURL.fileURLWithPath(path),
        fileType = AVFileTypeQuickTimeMovie!!,
        error = null,
    )
    val input = AVAssetWriterInput(
        mediaType = AVMediaTypeVideo!!,
        outputSettings = mapOf<Any?, Any?>(
            AVVideoCodecKey to AVVideoCodecTypeH264,
            AVVideoWidthKey to width,
            AVVideoHeightKey to height,
        ),
    )
    input.expectsMediaDataInRealTime = false
    val adaptor = AVAssetWriterInputPixelBufferAdaptor(
        assetWriterInput = input,
        sourcePixelBufferAttributes = null,
    )
    writer.addInput(input)
    check(writer.startWriting()) { "AVAssetWriter did not start: ${writer.error?.localizedDescription}" }
    writer.startSessionAtSourceTime(kCMTimeZero.readValue())

    repeat(frameCount) { frame: Int ->
        check(pumpMainLoopUntil(5.0) { input.readyForMoreMediaData }) { "writer input never became ready" }
        memScoped {
            val buffer = alloc<CVPixelBufferRefVar>()
            CVPixelBufferCreate(null, width.toULong(), height.toULong(), kCVPixelFormatType_32BGRA, null, buffer.ptr)
            val pixels = checkNotNull(buffer.value) { "CVPixelBufferCreate failed" }
            check(adaptor.appendPixelBuffer(pixels, withPresentationTime = CMTimeMake(frame.toLong(), fps))) {
                "append failed: ${writer.error?.localizedDescription}"
            }
            CVPixelBufferRelease(pixels)
        }
    }
    input.markAsFinished()
    val finished = AtomicInt(0)
    writer.finishWritingWithCompletionHandler { finished.value = 1 }
    check(pumpMainLoopUntil(10.0) { finished.value == 1 }) { "AVAssetWriter never finished" }
    check(writer.status == AVAssetWriterStatusCompleted) {
        "AVAssetWriter failed: ${writer.error?.localizedDescription}"
    }
    return path
}

internal const val TEST_MOVIE_WIDTH: Int = 160
internal const val TEST_MOVIE_HEIGHT: Int = 120
internal const val TEST_MOVIE_FPS: Int = 30
