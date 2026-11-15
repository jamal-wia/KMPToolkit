package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking

/**
 * Helpers for the tests that put a named pipe where a store opens a file. Opening a pipe for reading or writing blocks in
 * `open(2)` until a peer appears, which no coroutine timeout or cancellation can end, so a call that meets one is run on
 * its own thread with a hang bound and freed by opening the pipe itself when the bound passes.
 */
internal object NamedPipes {

    /** The hang bound: a hang detector, never a timing assertion (the stores answer a pipe in milliseconds). */
    const val HANG_BOUND_MS: Long = 30_000L

    /** Creates a named pipe at [file] with `mkfifo`, or skips the test where that is not possible. */
    fun createOrSkip(file: File) {
        TestHost.assumePosixFileSemantics("a named pipe needs mkfifo")
        val process: Process = ProcessBuilder("mkfifo", file.path).redirectErrorStream(true).start()
        val finished: Boolean = process.waitFor(HANG_BOUND_MS, TimeUnit.MILLISECONDS)
        if (!finished) process.destroyForcibly()
        org.junit.Assume.assumeTrue("mkfifo is not available here", finished && process.exitValue() == 0 && file.exists())
    }

    /**
     * Runs [call] on its own thread and returns its answer; fails the test when it does not answer within [HANG_BOUND_MS]
     * (naming the frame it is blocked in), after freeing it by opening every pipe of [pipes] read-write.
     */
    fun <T> answersWithinBound(label: String, pipes: List<File>, call: suspend () -> T): T {
        val answer = AtomicReference<Result<T>>()
        val worker = Thread({ answer.set(runCatching { runBlocking { call() } }) }, "named-pipe-call-$label").apply { isDaemon = true }
        worker.start()
        worker.join(HANG_BOUND_MS)
        val hung: Boolean = worker.isAlive
        val stack: String = if (!hung) "" else worker.stackTrace.take(STACK_FRAMES)
            .joinToString(" <- ") { frame: StackTraceElement -> "${frame.className.substringAfterLast('.')}.${frame.methodName}" }
        if (hung) {
            for (pipe: File in pipes) runCatching { RandomAccessFile(pipe, "rw").use { worker.join(HANG_BOUND_MS) } }
        }
        assertFalse(hung, "$label: the call did not answer within ${HANG_BOUND_MS / 1000} s; blocked at: $stack")
        return answer.get().getOrThrow()
    }

    private const val STACK_FRAMES: Int = 8
}
