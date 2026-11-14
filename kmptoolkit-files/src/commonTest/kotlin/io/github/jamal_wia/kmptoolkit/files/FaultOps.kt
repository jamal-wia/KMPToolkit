@file:OptIn(ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.spi.FileOpException
import io.github.jamal_wia.kmptoolkit.files.spi.FileSystemOps
import io.github.jamal_wia.kmptoolkit.files.spi.ReadableFile
import io.github.jamal_wia.kmptoolkit.files.spi.WritableFile

internal enum class Op { Metadata, List, CreateDirectory, CreateNew, OpenForRead, Write, Sync, Close, Rename, Link, Remove, SyncDirectory, UsableSpace }

/** Process death: an Error, so the engine's exception handling cannot swallow it. */
internal class SimulatedKill : Error("simulated process death")

internal class LoggedOp(val op: Op, val path: String)

/**
 * Wraps the platform's real ops with fault injection and process-death injection, so the engine's crash and failure
 * behaviour is observed on real files. After a crash every later call throws [SimulatedKill] without touching disk,
 * which is what a dead process does: the engine's cleanup never runs.
 */
internal class FaultOps(private val real: FileSystemOps) : FileSystemOps {
    private class Fault(val op: Op, val error: FileError, var remaining: Int, val match: (String) -> Boolean)

    val log: MutableList<LoggedOp> = mutableListOf()

    /** The `full` argument of every directory sync that reached the real ops, in order. */
    val syncFlags: MutableList<Boolean> = mutableListOf()
    private val faults: MutableList<Fault> = mutableListOf()
    private var crashOp: Op? = null
    private var crashOccurrence: Int = 0
    private var seen: Int = 0
    private var crashed: Boolean = false

    fun failNext(op: Op, error: FileError, count: Int = 1, match: (String) -> Boolean = { true }) {
        faults += Fault(op, error, count, match)
    }

    fun crashAfter(op: Op, occurrence: Int = 1) {
        crashOp = op
        crashOccurrence = occurrence
        seen = 0
    }

    val hasCrashed: Boolean get() = crashed

    /** Runs before every operation, on the caller's thread, before any fault applies (to model a concurrent actor). */
    var onBefore: ((Op, String) -> Unit)? = null

    private fun before(op: Op, path: String) {
        if (crashed) throw SimulatedKill()
        log += LoggedOp(op, path)
        onBefore?.invoke(op, path)
        val fault: Fault? = faults.firstOrNull { it.op == op && it.remaining > 0 && it.match(path) }
        if (fault != null) {
            fault.remaining--
            throw FileOpException(fault.error)
        }
    }

    private fun after(op: Op) {
        if (op == crashOp && ++seen == crashOccurrence) {
            crashed = true
            throw SimulatedKill()
        }
    }

    private inline fun <T> intercept(op: Op, path: String, block: () -> T): T {
        before(op, path)
        val result: T = block()
        after(op)
        return result
    }

    override val windowsPaths: Boolean get() = real.windowsPaths

    override fun nowMillis(): Long = real.nowMillis()

    override fun metadata(path: String, followLinks: Boolean): FileMetadata? =
        intercept(Op.Metadata, path) { real.metadata(path, followLinks) }

    override fun list(directory: String): List<String>? = intercept(Op.List, directory) { real.list(directory) }

    override fun createDirectory(path: String) = intercept(Op.CreateDirectory, path) { real.createDirectory(path) }

    override fun createNew(path: String, protection: FileProtection): WritableFile {
        before(Op.CreateNew, path)
        val inner: WritableFile = real.createNew(path, protection)
        try {
            after(Op.CreateNew)
        } catch (e: SimulatedKill) {
            // The harness releases the handle of the dead process, so no descriptor outlives the test.
            try {
                inner.close()
            } catch (_: Exception) {
            }
            throw e
        }
        return object : WritableFile {
            override fun write(bytes: ByteArray, offset: Int, length: Int) =
                intercept(Op.Write, path) { inner.write(bytes, offset, length) }

            override fun sync(full: Boolean) = intercept(Op.Sync, path) { inner.sync(full) }

            override fun close() {
                if (crashed) {
                    try {
                        inner.close()
                    } catch (_: Exception) {
                        // The test harness releases the handle of the dead process.
                    }
                    throw SimulatedKill()
                }
                intercept(Op.Close, path) { inner.close() }
            }
        }
    }

    override fun openForRead(path: String): ReadableFile {
        val inner: ReadableFile = intercept(Op.OpenForRead, path) { real.openForRead(path) }
        return object : ReadableFile {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = inner.read(buffer, offset, length)

            override fun close() = inner.close()
        }
    }

    override fun rename(from: String, to: String) = intercept(Op.Rename, from) { real.rename(from, to) }

    override fun link(existing: String, newPath: String) = intercept(Op.Link, newPath) { real.link(existing, newPath) }

    override fun remove(path: String) = intercept(Op.Remove, path) { real.remove(path) }

    override fun syncDirectory(directory: String, full: Boolean) =
        intercept(Op.SyncDirectory, directory) {
            syncFlags += full
            real.syncDirectory(directory, full)
        }

    override fun usableSpace(path: String): Long? = intercept(Op.UsableSpace, path) { real.usableSpace(path) }
}
