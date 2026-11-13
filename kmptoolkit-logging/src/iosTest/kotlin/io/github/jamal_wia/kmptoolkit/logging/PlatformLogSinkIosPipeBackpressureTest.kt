package io.github.jamal_wia.kmptoolkit.logging

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_apply
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_queue_t
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_t
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time
import platform.posix.STDOUT_FILENO
import platform.posix.close
import platform.posix.dup
import platform.posix.dup2
import platform.posix.pipe
import platform.posix.read
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The iOS `platformLogSink()` promises that each event, its line and its stack trace, reaches standard output
 * in one step, so concurrent loggers never glue lines together. `println` on Kotlin/Native is two unbuffered
 * `write(2)` calls (the text, then the line break), and a text larger than `PIPE_BUF` written to a full pipe
 * is split by the kernel, so the only thing that keeps two events apart is the sink's lock.
 *
 * This is the case the file-based [PlatformLogSinkIosConcurrencyTest] cannot reach: standard output is a
 * pipe whose reader is slow (64-byte reads on its own dispatch queue), 8 workers write about 4 KB per event,
 * so the pipe is full for most of the run and every writer blocks inside `write(2)` while the others queue
 * behind it. Without the lock the blocked writes interleave and headers are cut or traces misplaced.
 *
 * Nothing waits on a clock: the reader signals a semaphore on end of file, the wait only bounds a hang and
 * fails with its own message.
 */
@OptIn(ExperimentalForeignApi::class)
class PlatformLogSinkIosPipeBackpressureTest {

    private class Capture {
        var bytes: ByteArray = ByteArray(1 shl 20)
        var size: Int = 0
        var readFailed: Boolean = false

        fun ensureRoom(room: Int) {
            if (size + room > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + room))
        }
    }

    private fun capturePipe(workers: ULong, perWorker: Int): List<String> {
        val capture: Capture = Capture()
        val readerDone: dispatch_semaphore_t = dispatch_semaphore_create(0)
        val readerQueue: dispatch_queue_t = dispatch_queue_create("kmptoolkit-log-pipe-reader", null)
        val saved: Int = dup(STDOUT_FILENO)
        var readFd = -1
        var started = false
        memScoped {
            val fds: CPointer<IntVar> = allocArray<IntVar>(2)
            check(pipe(fds) == 0) { "pipe(2) failed" }
            readFd = fds[0]
            dup2(fds[1], STDOUT_FILENO)
            // Standard output is now the only holder of the write end, so restoring it below gives the reader its end of file.
            close(fds[1])
        }
        val descriptor: Int = readFd
        try {
            dispatch_async(readerQueue) {
                while (true) {
                    capture.ensureRoom(READ_CHUNK)
                    val count: Long = capture.bytes.usePinned { pinned ->
                        read(descriptor, pinned.addressOf(capture.size), READ_CHUNK.convert())
                    }
                    if (count < 0) {
                        capture.readFailed = true
                        break
                    }
                    if (count == 0L) break
                    capture.size += count.toInt()
                }
                dispatch_semaphore_signal(readerDone)
            }
            started = true
            val sink: LogSink = platformLogSink()
            val padding: String = "x".repeat(TRACE_PADDING)
            dispatch_apply(workers, dispatch_get_global_queue(0, 0u)) { worker ->
                repeat(perWorker) { n ->
                    sink.log(LogLevel.ERROR, "t$worker", "event-$worker-$n", IllegalStateException("trace-$worker-$n $padding"))
                }
            }
        } finally {
            dup2(saved, STDOUT_FILENO)
            close(saved)
        }
        val finished: Boolean = started && dispatch_semaphore_wait(readerDone, dispatch_time(DISPATCH_TIME_NOW, HANG_BOUND_NANOS)) == 0L
        close(descriptor)
        assertTrue(finished, "the reader did not reach end of file within the hang bound")
        assertTrue(!capture.readFailed, "read(2) on the pipe failed")
        return capture.bytes.decodeToString(0, capture.size).split('\n')
    }

    @Test
    fun `under a slow pipe reader and concurrent loggers no event is cut or interleaved`() {
        val workers = 8uL
        val perWorker = 500
        val lines: List<String> = capturePipe(workers, perWorker)
        val header: Regex = Regex("^E/t(\\d+): event-(\\d+)-(\\d+)$")
        var events = 0
        var misplaced = 0
        for ((index: Int, line: String) in lines.withIndex()) {
            val match: MatchResult = header.find(line) ?: continue
            events++
            val (w, _, n) = match.destructured
            if (!lines.getOrElse(index + 1) { "" }.contains("trace-$w-$n ")) misplaced++
        }
        val expected: Int = workers.toInt() * perWorker
        assertEquals(expected, events, "intact header lines of $expected written events")
        assertEquals(
            0,
            misplaced,
            "of $events intact header lines misplaced=$misplaced events had another line between the header and its own trace",
        )
    }

    private companion object {
        const val READ_CHUNK: Int = 64
        const val TRACE_PADDING: Int = 4000
        const val HANG_BOUND_NANOS: Long = 120_000_000_000L
    }
}
