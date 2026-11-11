package io.github.jamal_wia.kmptoolkit.uploader

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking

/**
 * The durable row of the kill model: what a real engine's store is to the journal, reduced to one atomic file. The
 * state is `INFLIGHT` (the attempt is owed), `PENDING` (a failure was charged, the retry is not in flight) or `DONE`
 * (delivered); [commits] counts commits that were applied, so "committed exactly once" is a number on disk.
 */
internal class KillModelRow(private val file: File) {

    data class State(val state: String, val attempts: Int, val commits: Int)

    fun write(state: State) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText("${state.state} ${state.attempts} ${state.commits}")
        check(tmp.renameTo(file) || run { file.delete(); tmp.renameTo(file) }) { "rename failed" }
    }

    fun read(): State {
        val parts: List<String> = file.readText().trim().split(' ')
        return State(parts[0], parts[1].toInt(), parts[2].toInt())
    }
}

/** Counts the steps of a settlement and halts the process (no `finally`, no shutdown hook) right after step [killAt]. */
internal class KillSteps(private val killAt: Int) {
    private val count = AtomicInteger(0)

    val reached: Int get() = count.get()

    fun reached(@Suppress("UNUSED_PARAMETER") name: String) {
        if (count.incrementAndGet() == killAt) Runtime.getRuntime().halt(KILLED_EXIT_CODE)
    }

    companion object {
        const val KILLED_EXIT_CODE: Int = 137
    }
}

/** A storage that reports every operation to [steps] after performing it. */
internal class KillingStorage(private val inner: OutcomeStorage, private val steps: KillSteps) : OutcomeStorage {
    override fun write(itemId: String, bytes: ByteArray): Boolean = inner.write(itemId, bytes).also { steps.reached("write") }
    override fun read(itemId: String): ByteArray? = inner.read(itemId).also { steps.reached("read") }
    override fun delete(itemId: String) = inner.delete(itemId).also { steps.reached("delete") }
    override fun itemIds(): List<String> = inner.itemIds().also { steps.reached("itemIds") }
    override fun purgeLeftovers() = inner.purgeLeftovers().also { steps.reached("purge") }
}

/**
 * The engine's settlement over [row]: a stamped answer applies only to the row's in-flight attempt, an unstamped one
 * to any in-flight row (it knows no better); an applied commit is atomic, with [steps] reporting just before and just
 * after it.
 */
internal fun killModelSettle(row: KillModelRow, steps: KillSteps): OutcomeSettle = { _, response, stamp, onSettling ->
    val current: KillModelRow.State = row.read()
    if (current.state != "INFLIGHT" || (stamp != null && stamp != current.attempts)) {
        JournalSettleOutcome.STALE
    } else {
        onSettling()
        steps.reached("before-commit")
        row.write(
            if (response.statusCode in 200..299) {
                KillModelRow.State("DONE", current.attempts, current.commits + 1)
            } else {
                KillModelRow.State("PENDING", current.attempts + 1, current.commits + 1)
            },
        )
        steps.reached("after-commit")
        JournalSettleOutcome.LANDED
    }
}

/**
 * A child that uploads a file through the production multipart transfer and halts the process once [fraction] of the
 * file went out. Arguments: `url filePath fraction`.
 */
internal object Round5KillBodyChild {

    @JvmStatic
    fun main(args: Array<String>) {
        val fraction: Float = args[2].toFloat()
        performMultipartUploadCapturing(
            request = UploadRequest(args[0], fields = listOf(UploadField.File("f", "big.bin", "application/octet-stream", args[1]))),
            connectTimeoutMillis = 10_000,
            readTimeoutMillis = 30_000,
            onWholePercent = { done -> if (done >= fraction) Runtime.getRuntime().halt(KillSteps.KILLED_EXIT_CODE) },
        )
    }
}

/**
 * The first process of a kill scenario: records an answer for the item and settles it, and is halted after the step
 * numbered in its arguments. Arguments: `dir itemId stamp|none status killAt`.
 */
internal object Round5KillChild {

    @JvmStatic
    fun main(args: Array<String>) {
        val root = File(args[0])
        val itemId: String = args[1]
        val stamp: Int? = args[2].toIntOrNull()
        val status: Int = args[3].toInt()
        val steps = KillSteps(args[4].toInt())
        if (steps.reached == 0 && args[4].toInt() == 0) Runtime.getRuntime().halt(KillSteps.KILLED_EXIT_CODE)
        val storage = KillingStorage(
            AndroidOutcomeStorage(File(root, "outcomes"), File(root, "outcomes-partial")),
            steps,
        )
        val journal = OutcomeJournal(storage)
        val response = UploadResponse(status, mapOf("content-type" to "application/json"), "{\"ok\":true}".encodeToByteArray(), false)
        journal.record(itemId, response, attemptStamp = stamp)
        val landed: Boolean = runBlocking {
            journal.settleRecorded(itemId, response, attemptStamp = stamp, settle = killModelSettle(KillModelRow(File(root, "row")), steps))
        }
        check(landed) { "the settlement did not land" }
    }
}
