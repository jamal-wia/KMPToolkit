package io.github.jamal_wia.kmptoolkit.logging

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.stringWithContentsOfFile
import platform.darwin.dispatch_apply
import platform.darwin.dispatch_get_global_queue
import platform.posix.O_CREAT
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.STDOUT_FILENO
import platform.posix.close
import platform.posix.dup
import platform.posix.dup2
import platform.posix.open
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The KDoc of the iOS `platformLogSink()` says a throwable's stack trace follows the message line
 * "on its own lines". Standard output is redirected to a file for the duration of 8 concurrent loggers; each
 * event's trace line must directly follow its own header line.
 */
@OptIn(ExperimentalForeignApi::class)
class PlatformLogSinkIosConcurrencyTest {

    private fun capture(workers: ULong, perWorker: Int): List<String> {
        val manager: NSFileManager = NSFileManager.defaultManager
        // timing-hygiene-ignore: TH4 private-dir a UUID directory of this call, removed once the capture is read; nothing else is listed
        val directory: String = NSTemporaryDirectory() + "kmptoolkit-ios-concurrency-" + NSUUID().UUIDString
        manager.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
        val path: String = "$directory/capture.txt"
        val saved: Int = dup(STDOUT_FILENO)
        val fd: Int = open(path, O_WRONLY or O_CREAT or O_TRUNC, 420)
        dup2(fd, STDOUT_FILENO)
        val sink: LogSink = platformLogSink()
        try {
            dispatch_apply(workers, dispatch_get_global_queue(0, 0u)) { worker ->
                repeat(perWorker) { n ->
                    sink.log(LogLevel.ERROR, "t$worker", "event-$worker-$n", IllegalStateException("trace-$worker-$n"))
                }
            }
        } finally {
            dup2(saved, STDOUT_FILENO)
            close(saved)
            close(fd)
        }
        val text: String = NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) ?: ""
        manager.removeItemAtPath(directory, error = null)
        return text.split('\n')
    }

    private fun misplaced(lines: List<String>): Pair<Int, Int> {
        val header: Regex = Regex("^E/t(\\d+): event-(\\d+)-(\\d+)$")
        var misplaced = 0
        var events = 0
        for ((index: Int, line: String) in lines.withIndex()) {
            val match: MatchResult = header.find(line) ?: continue
            events++
            val (w, _, n) = match.destructured
            if (!lines.getOrElse(index + 1) { "" }.contains("trace-$w-$n")) misplaced++
        }
        return events to misplaced
    }

    @Test
    fun `control one logger thread keeps every trace directly under its header`() {
        val (events: Int, misplaced: Int) = misplaced(capture(1uL, 300))
        assertEquals(300, events, "headers")
        assertEquals(0, misplaced, "misplaced traces with a single thread")
    }

    @Test
    fun `under concurrent loggers every trace directly follows its own header`() {
        val lines: List<String> = capture(8uL, 2000)
        val (events: Int, misplaced: Int) = misplaced(lines)
        assertEquals(16000, events, "intact header lines")
        assertEquals(
            0,
            misplaced,
            "of $events intact header lines (16000 were written) misplaced=$misplaced events had another line between the header and its own trace; first lines ${lines.take(4)}",
        )
    }
}
