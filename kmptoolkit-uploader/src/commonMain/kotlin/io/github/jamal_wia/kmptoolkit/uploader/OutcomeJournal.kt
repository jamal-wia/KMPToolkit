package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.i
import io.github.jamal_wia.kmptoolkit.logging.w
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Where [OutcomeJournal] keeps its entries: one blob per item id. The platform implementation decides the
 * directory, the backup exclusion and the file protection; this is only what the journal needs of it.
 */
internal interface OutcomeStorage {

    /** Stores [bytes] for [itemId] atomically: a reader sees the previous entry or this one, never a part of it. */
    fun write(itemId: String, bytes: ByteArray): Boolean

    /** The bytes stored for [itemId], or `null` when there are none. */
    fun read(itemId: String): ByteArray?

    /** Removes the entry of [itemId]; a no-op when there is none. */
    fun delete(itemId: String)

    /** The item ids that have an entry. */
    fun itemIds(): List<String>

    /** Removes half-written leftovers of a write that was cut short, so they do not pile up. */
    fun purgeLeftovers()
}

/** What the journal found for an item. */
internal sealed interface JournalEntry {

    /** Nothing is stored. */
    data object Absent : JournalEntry

    /** An entry existed but was expired, of an unknown format version or unreadable; it was deleted. */
    data object Discarded : JournalEntry

    /** An entry may exist, but reading it failed; nothing is known about it and nothing was deleted. */
    data object Unreadable : JournalEntry

    /**
     * A usable [response], written at [writtenAtMillis]. [attemptStamp] is the attempt the answer belongs to
     * (`UploaderItem.attempts` when the worker prepared it), or `null` for an unstamped entry (the iOS delegate's).
     */
    class Valid(val response: UploadResponse, val writtenAtMillis: Long, val attemptStamp: Int? = null) : JournalEntry
}

/** How a replay of one entry ended. */
internal enum class ReplayOutcome {

    /** There was no entry. */
    NoEntry,

    /** There was an entry, but it was expired, of an unknown version or unreadable; it was deleted. */
    Discarded,

    /** The entry reached the engine, which settled (or ignored) it; the entry was deleted. */
    Replayed,

    /** The entry answered an attempt the item has moved past; it was deleted and nothing was applied. */
    Stale,

    /** An entry exists but cannot be read; it was kept. */
    Unreadable,

    /** The entry did not reach an engine; it stays for the next attempt. */
    Kept,
}

/** How the engine's settlement of a journaled answer ended. */
internal enum class JournalSettleOutcome {

    /** Committed, or the item is gone. */
    LANDED,

    /**
     * The answer is of an attempt the item has moved past, or its commit found the item already moved on:
     * nothing was applied and no hook ran.
     */
    STALE,

    /** Nothing was committed (no engine, store failure). */
    NOT_LANDED,
}

/**
 * A durable record of an upload's **completed outcome** — the server's status, headers and the capped body —
 * kept from the moment the transport has it until the engine has settled it.
 *
 * The transport's terminal callback fires once, in whichever process the platform delivers it. Without a
 * record, an outcome that dies between that callback and the engine's settlement is lost, and the item is
 * uploaded again when its lease expires. With one, the next process replays the outcome through
 * [UploadGateway.complete] **instead of** uploading again.
 *
 * Only a [UploadResult.Completed] outcome is recorded: a transport failure carries no response, and a
 * re-upload after one is exactly what the engine's retry policy wants.
 *
 * Settlement protocol: an entry written by a worker carries the attempt it answers (an attempt stamp), and is
 * deleted only after the engine's commit returned, so a death or stop before the commit leaves it for the next
 * run, and a leftover after the commit is recognised as stale by its stamp and dropped. An unstamped entry (the
 * iOS delegate writes these in a process with no engine to read a stamp from) is deleted just before the commit
 * and written back when the commit did not land.
 *
 * Nothing here throws for a storage problem: a failed write means the journal does not protect that
 * outcome — today's behaviour — and a failed read or delete is logged.
 */
internal class OutcomeJournal(
    private val storage: OutcomeStorage,
    private val clock: UploaderClock = UploaderClock.System,
    logger: Logger = NoopLogger,
    private val retentionMillis: Long = RETENTION_MILLIS,
) {

    /** A logger that throws is ignored: the journal protects an answer, and a log line must not lose it. */
    private val logger: Logger = logger.nonThrowing()

    /**
     * Records [response] for [itemId], atomically. The body is held to [bodyLimit] — the handler's own
     * `maxResponseBodyBytes` where the transport knows it, never more than the engine's ceiling; `0` keeps no
     * body at all — and the headers to the engine's cap.
     *
     * [attemptStamp] is the attempt the answer belongs to, or `null` for an unstamped entry.
     *
     * @return whether the entry is now on disk; `false` means the outcome is not protected.
     */
    fun record(
        itemId: String,
        response: UploadResponse,
        bodyLimit: Int = MAX_RESPONSE_BODY_BYTES,
        attemptStamp: Int? = null,
    ): Boolean {
        if (!isJournalableId(itemId)) return false
        return try {
            val limit: Int = bodyLimit.coerceIn(0, MAX_RESPONSE_BODY_BYTES)
            val bytes: ByteArray =
                OutcomeCodec.encode(response.enforced(limit), clock.nowEpochMillis(), attemptStamp)
            storage.write(itemId, bytes)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            logger.w(failure) { "Could not journal the upload outcome — item=$itemId" }
            false
        }
    }

    /**
     * Reads the entry of [itemId]; an entry that cannot be used is deleted and reported as [JournalEntry.Discarded],
     * one whose read failed is [JournalEntry.Unreadable] and is left as it is.
     */
    fun inspect(itemId: String): JournalEntry {
        if (!isJournalableId(itemId)) return JournalEntry.Absent
        val bytes: ByteArray = try {
            storage.read(itemId)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            logger.w(failure) { "Could not read the journaled upload outcome — item=$itemId" }
            return JournalEntry.Unreadable
        } ?: return JournalEntry.Absent
        val decoded: DecodedOutcome = OutcomeCodec.decode(bytes)
        if (decoded !is DecodedOutcome.Ok) {
            logger.i { "Discarded an unusable journaled outcome — item=$itemId, why=$decoded" }
            delete(itemId)
            return JournalEntry.Discarded
        }
        if (clock.nowEpochMillis() - decoded.writtenAtMillis > retentionMillis) {
            logger.i { "Discarded an expired journaled outcome — item=$itemId" }
            delete(itemId)
            return JournalEntry.Discarded
        }
        return JournalEntry.Valid(decoded.response, decoded.writtenAtMillis, decoded.attemptStamp)
    }

    /** Removes the entry of [itemId]. */
    fun delete(itemId: String) {
        if (!isJournalableId(itemId)) return
        try {
            storage.delete(itemId)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            logger.w(failure) { "Could not delete the journaled upload outcome — item=$itemId" }
        }
    }

    /**
     * Cuts the body of [itemId]'s entry to [limit] when it holds more; used once the handler's limit is
     * known for an entry written before it was. A missing or unusable entry is left as it is.
     *
     * Under the same per-item exclusion as [replay]: a replayer that handled and deleted the entry in the
     * meantime must not have it written back by a cut that read it earlier, or the response would settle twice.
     */
    suspend fun restrictBody(itemId: String, limit: Int) {
        OutcomeReplayLocks.withItem(itemId) {
            val entry: JournalEntry = inspect(itemId)
            if (entry is JournalEntry.Valid && entry.response.bodySize > limit.coerceAtLeast(0)) {
                record(itemId, entry.response, limit, entry.attemptStamp)
            }
        }
    }

    /**
     * Settles the response a transport just [record]ed for [itemId] (stamped with [attemptStamp]), under the
     * same per-item exclusion as [replay]: if a replayer — the creation sweep — already handled the entry
     * meanwhile, there is nothing left to settle. A stamped entry is deleted once the settlement returned
     * (see [JournalSettleOutcome]) and stays untouched when it did not land or threw; an unstamped one is
     * deleted just before the commit and written back, with its original write time, when it did not land.
     *
     * An entry that cannot be read back, or reads back damaged or expired, never means "handled": the answer
     * is settled from [response], and recorded again (best effort) when that does not land. A stamped answer
     * is settled from memory unless the entry on disk is provably its own, and never deletes another answer's
     * entry; the Android worker runs one job per item at a time (see [exclusively]).
     *
     * @return whether the outcome landed (or was stale), or a replayer had already handled the entry.
     */
    suspend fun settleRecorded(
        itemId: String,
        response: UploadResponse,
        attemptStamp: Int? = null,
        bodyLimit: Int = MAX_RESPONSE_BODY_BYTES,
        settle: OutcomeSettle,
    ): Boolean =
        OutcomeReplayLocks.withItem(itemId) { settleRecordedHeld(itemId, response, attemptStamp, bodyLimit, settle) }

    private suspend fun settleRecordedHeld(
        itemId: String,
        response: UploadResponse,
        attemptStamp: Int?,
        bodyLimit: Int,
        settle: OutcomeSettle,
    ): Boolean {
        val entry: JournalEntry = inspect(itemId)
        if (attemptStamp == null) {
            // Unstamped: the code of the iOS delegate's journal, unchanged.
            return when (entry) {
                is JournalEntry.Valid -> settleKeeping(itemId, entry, response, settle) != JournalSettleOutcome.NOT_LANDED
                // Unstamped only: without a stamp a from-memory settle after a replayer could charge twice.
                JournalEntry.Absent -> true
                JournalEntry.Unreadable, JournalEntry.Discarded -> settleFromMemory(itemId, response, null, bodyLimit, settle)
            }
        }
        val recorded: UploadResponse = response.enforced(bodyLimit.coerceIn(0, MAX_RESPONSE_BODY_BYTES))
        return when {
            entry is JournalEntry.Valid && isOwn(entry, recorded, attemptStamp) ->
                settleKeeping(itemId, entry, response, settle) != JournalSettleOutcome.NOT_LANDED

            // The entry holds another answer (another job's, across processes): settle ours from memory and
            // leave that one to its writer or a replay.
            entry is JournalEntry.Valid -> {
                val outcome: JournalSettleOutcome = settle(itemId, response, attemptStamp) { }
                if (outcome == JournalSettleOutcome.NOT_LANDED) {
                    logger.w {
                        "The answer of attempt $attemptStamp for item=$itemId did not land and another answer holds " +
                            "the entry; the re-run replays that one, this one is given up (at least once)."
                    }
                    false
                } else {
                    true
                }
            }

            // Absent, Unreadable, Discarded: the answer is held in memory, and a stamped answer settled twice
            // is harmless (a stale Failed/Park is a no-op, a delivery finds its row gone), so settle it.
            else -> settleFromMemory(itemId, response, attemptStamp, bodyLimit, settle)
        }
    }

    /** Whether [entry] is the entry this worker's own [recorded] answer of [attemptStamp] wrote. */
    private fun isOwn(entry: JournalEntry.Valid, recorded: UploadResponse, attemptStamp: Int): Boolean =
        entry.attemptStamp == attemptStamp && entry.response == recorded

    /**
     * Settles [response] from memory. Landed or stale: deletes the entry (a no-op when absent) and returns
     * `true`. Not landed: records it again (best effort) so the re-run still finds it, and returns `false`.
     */
    private suspend fun settleFromMemory(
        itemId: String,
        response: UploadResponse,
        attemptStamp: Int?,
        bodyLimit: Int,
        settle: OutcomeSettle,
    ): Boolean {
        val outcome: JournalSettleOutcome = settle(itemId, response, attemptStamp) { }
        return if (outcome != JournalSettleOutcome.NOT_LANDED) {
            delete(itemId)
            true
        } else {
            record(itemId, response, bodyLimit, attemptStamp)
            false
        }
    }

    /** Runs [block] as the only holder of [itemId]'s journal lock in this process; see [HeldItem]. */
    suspend fun <T> exclusively(itemId: String, block: suspend HeldItem.() -> T): T =
        OutcomeReplayLocks.withItem(itemId) { HeldItem(itemId).block() }

    /** The journal's operations on one item while [exclusively] holds its lock; they do not take it again (it is not reentrant). */
    inner class HeldItem internal constructor(val itemId: String) {

        suspend fun replay(settle: OutcomeSettle): ReplayOutcome = replayHeld(itemId, settle)

        fun record(response: UploadResponse, bodyLimit: Int, attemptStamp: Int?): Boolean =
            this@OutcomeJournal.record(itemId, response, bodyLimit, attemptStamp)

        fun delete() = this@OutcomeJournal.delete(itemId)

        suspend fun settleRecorded(
            response: UploadResponse,
            attemptStamp: Int?,
            bodyLimit: Int,
            settle: OutcomeSettle,
        ): Boolean = settleRecordedHeld(itemId, response, attemptStamp, bodyLimit, settle)
    }

    /**
     * Hands the entry of [itemId], if there is a usable one, to [settle] — the engine's settlement — and
     * deletes it once the settlement returned (see [OutcomeSettle]). An id the engine does not know settles
     * nothing and lands, so such an entry is deleted too; an unreachable engine does not land and the entry
     * stays for the next attempt, as does one whose settlement threw. An entry that answers an attempt the
     * item has moved past is [ReplayOutcome.Stale]: deleted, nothing applied. An entry that cannot be read is
     * [ReplayOutcome.Unreadable] and is kept.
     *
     * One replay per item at a time, process-wide: a second one — the creation sweep and a worker, say —
     * waits, then finds the entry gone and does nothing, so the response is delivered once.
     */
    suspend fun replay(itemId: String, settle: OutcomeSettle): ReplayOutcome =
        OutcomeReplayLocks.withItem(itemId) { replayHeld(itemId, settle) }

    private suspend fun replayHeld(itemId: String, settle: OutcomeSettle): ReplayOutcome =
        when (val entry: JournalEntry = inspect(itemId)) {
            JournalEntry.Absent -> ReplayOutcome.NoEntry
            JournalEntry.Unreadable -> ReplayOutcome.Unreadable
            JournalEntry.Discarded -> ReplayOutcome.Discarded
            is JournalEntry.Valid -> {
                logger.i { "Replaying the journaled upload outcome — item=$itemId, status=${entry.response.statusCode}" }
                when (settleKeeping(itemId, entry, entry.response, settle)) {
                    JournalSettleOutcome.LANDED -> ReplayOutcome.Replayed
                    JournalSettleOutcome.STALE -> ReplayOutcome.Stale
                    JournalSettleOutcome.NOT_LANDED -> ReplayOutcome.Kept
                }
            }
        }

    /**
     * Settles [response] for [entry]. Stamped: never deleted before the settlement returns; deleted when it
     * landed or was stale, kept when it did not land or threw. Unstamped: deleted by `onSettling` just before
     * the commit and written back (original write time, so the expiry is not reset) when it did not land,
     * whatever ended the settlement.
     */
    private suspend fun settleKeeping(
        itemId: String,
        entry: JournalEntry.Valid,
        response: UploadResponse,
        settle: OutcomeSettle,
    ): JournalSettleOutcome {
        val stamp: Int? = entry.attemptStamp
        if (stamp != null) {
            val outcome: JournalSettleOutcome = settle(itemId, response, stamp) { }
            if (outcome != JournalSettleOutcome.NOT_LANDED) delete(itemId)
            return outcome
        }
        var deletedForCommit = false
        var outcome: JournalSettleOutcome = JournalSettleOutcome.NOT_LANDED
        try {
            outcome = settle(itemId, response, null) {
                deletedForCommit = true
                delete(itemId)
            }
        } finally {
            // With the engine contract (a throw out of the settle means the commit did not land), a throw
            // after `onSettling` leaves nothing committed, and the answer must survive for the next attempt.
            if (deletedForCommit && outcome == JournalSettleOutcome.NOT_LANDED) {
                withContext(NonCancellable) { restore(itemId, entry) }
            }
        }
        if (outcome != JournalSettleOutcome.NOT_LANDED) delete(itemId)
        return outcome
    }

    /** Writes [entry] back as it was, keeping its [JournalEntry.Valid.writtenAtMillis] and stamp. */
    private fun restore(itemId: String, entry: JournalEntry.Valid) {
        try {
            val limit: Int = entry.response.bodySize.coerceIn(0, MAX_RESPONSE_BODY_BYTES)
            storage.write(
                itemId,
                OutcomeCodec.encode(entry.response.enforced(limit), entry.writtenAtMillis, entry.attemptStamp),
            )
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            logger.w(failure) { "Could not restore the journaled upload outcome — item=$itemId" }
        }
    }

    /**
     * Replays or deletes every entry no one claimed — those of items whose transport never ran again — and
     * clears leftovers of cut-short writes. The 7-day expiry applies as in [inspect].
     */
    suspend fun sweep(settle: OutcomeSettle) {
        val ids: List<String> = try {
            storage.purgeLeftovers()
            storage.itemIds()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            logger.w(failure) { "Could not list the journaled upload outcomes." }
            return
        }
        for (itemId in ids) replay(itemId, settle)
    }

    companion object {

        /** An entry older than this is deleted instead of replayed: the item's own lease logic has long moved on. */
        val RETENTION_MILLIS: Long = 7.days.inWholeMilliseconds
    }
}

/** Whether [itemId] can name a journal entry: no path separator, no dot-only name, nothing unprintable. */
internal fun isJournalableId(itemId: String): Boolean =
    itemId.isNotEmpty() && itemId != "." && itemId != ".." &&
        itemId.none { it == '/' || it == '\\' || it == ' ' || it.code < 0x20 || it.code == 0x7f } &&
        itemId.length <= MAX_JOURNAL_ID_LENGTH

private const val MAX_JOURNAL_ID_LENGTH: Int = 200

/** The result of reading the bytes of an entry. */
internal sealed interface DecodedOutcome {

    class Ok(val response: UploadResponse, val writtenAtMillis: Long, val attemptStamp: Int?) : DecodedOutcome

    /** A format version this code does not know — written by another release. */
    data class UnknownVersion(val version: Int) : DecodedOutcome

    /** Not a complete entry of the known version. */
    data object Corrupt : DecodedOutcome
}

/**
 * The on-disk form of an entry. Version 1 (unstamped): `int version, int status, long writtenAt, byte truncated,
 * int headerCount, (int nameLength, name, int valueLength, value) * headerCount, int bodyLength, body`.
 * Version 2 (stamped): `int version, int status, long writtenAt, int attemptStamp, byte truncated`, then the rest
 * as version 1; a negative stamp is [DecodedOutcome.Corrupt]. Big-endian, strings in UTF-8. Every length is
 * checked against what is left, so a short or damaged file reads as [DecodedOutcome.Corrupt] and never throws.
 */
internal object OutcomeCodec {

    const val VERSION_UNSTAMPED: Int = 1
    const val VERSION_STAMPED: Int = 2

    /** Writes version 1 when [attemptStamp] is `null`, version 2 otherwise. */
    fun encode(response: UploadResponse, writtenAtMillis: Long, attemptStamp: Int? = null): ByteArray {
        val out = ByteSink()
        out.int(if (attemptStamp == null) VERSION_UNSTAMPED else VERSION_STAMPED)
        out.int(response.statusCode)
        out.long(writtenAtMillis)
        if (attemptStamp != null) out.int(attemptStamp)
        out.byte(if (response.isBodyTruncated) 1 else 0)
        out.int(response.headers.size)
        for ((name, value) in response.headers) {
            out.bytes(name.encodeToByteArray())
            out.bytes(value.encodeToByteArray())
        }
        out.bytes(response.body())
        return out.toByteArray()
    }

    fun decode(raw: ByteArray): DecodedOutcome {
        val input = ByteSource(raw)
        return try {
            val version: Int = input.int()
            if (version != VERSION_UNSTAMPED && version != VERSION_STAMPED) return DecodedOutcome.UnknownVersion(version)
            val status: Int = input.int()
            val writtenAt: Long = input.long()
            val attemptStamp: Int? = if (version == VERSION_STAMPED) input.int() else null
            if (attemptStamp != null && attemptStamp < 0) return DecodedOutcome.Corrupt
            val truncated: Boolean = when (input.byte().toInt()) {
                0 -> false
                1 -> true
                else -> return DecodedOutcome.Corrupt
            }
            val headerCount: Int = input.int()
            // Each header takes at least eight bytes (two lengths), which bounds a damaged count.
            if (headerCount < 0 || headerCount.toLong() * 8L > input.remaining) return DecodedOutcome.Corrupt
            val headers: MutableMap<String, String> = LinkedHashMap()
            repeat(headerCount) {
                val name: String = input.bytes().decodeToString()
                headers[name] = input.bytes().decodeToString()
            }
            val body: ByteArray = input.bytes()
            if (input.remaining != 0 || body.size > MAX_RESPONSE_BODY_BYTES) return DecodedOutcome.Corrupt
            DecodedOutcome.Ok(UploadResponse(status, headers, body, truncated), writtenAt, attemptStamp)
        } catch (malformed: MalformedEntry) {
            DecodedOutcome.Corrupt
        }
    }

    private class MalformedEntry : RuntimeException()

    private class ByteSink {
        private var buffer: ByteArray = ByteArray(256)
        private var size: Int = 0

        private fun ensure(extra: Int) {
            if (size + extra > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, size + extra))
        }

        fun byte(value: Int) {
            ensure(1)
            buffer[size++] = value.toByte()
        }

        fun int(value: Int) {
            for (shift in 24 downTo 0 step 8) byte(value ushr shift)
        }

        fun long(value: Long) {
            for (shift in 56 downTo 0 step 8) byte((value ushr shift).toInt())
        }

        fun bytes(value: ByteArray) {
            int(value.size)
            ensure(value.size)
            value.copyInto(buffer, size)
            size += value.size
        }

        fun toByteArray(): ByteArray = buffer.copyOf(size)
    }

    private class ByteSource(private val data: ByteArray) {
        private var position: Int = 0
        val remaining: Int get() = data.size - position

        private fun need(count: Int) {
            if (count < 0 || count > remaining) throw MalformedEntry()
        }

        fun byte(): Byte {
            need(1)
            return data[position++]
        }

        fun int(): Int {
            need(4)
            var value = 0
            repeat(4) { value = (value shl 8) or (data[position++].toInt() and 0xff) }
            return value
        }

        fun long(): Long {
            need(8)
            var value = 0L
            repeat(8) { value = (value shl 8) or (data[position++].toLong() and 0xff) }
            return value
        }

        fun bytes(): ByteArray {
            val length: Int = int()
            need(length)
            val out: ByteArray = data.copyOfRange(position, position + length)
            position += length
            return out
        }
    }
}

/**
 * The engine's settlement of a journaled response: `(itemId, response, attemptStamp, onSettling)`.
 * [onSettling] is invoked only for unstamped answers (`attemptStamp == null`), right before the engine commits,
 * so the entry is gone before the item can be re-handed; a stamped answer is never deleted before the engine
 * returns. A throw out of the settlement means the commit did not land. See [JournalSettleOutcome].
 */
internal typealias OutcomeSettle = suspend (String, UploadResponse, Int?, () -> Unit) -> JournalSettleOutcome

/** [OutcomeSettle] through [UploadGateway], waiting up to [engineWait] for an engine. */
internal fun gatewaySettle(engineWait: Duration): OutcomeSettle = { itemId, response, attemptStamp, onSettling ->
    UploadGateway.completeJournaled(itemId, response, attemptStamp, engineWait, onSettling)
}

/** One lock per item id, created on demand and dropped once nobody holds or waits for it. */
internal class ItemLocks {

    private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)

    private val guard: Mutex = Mutex()
    private val entries: MutableMap<String, Entry> = HashMap()

    /** The number of ids that have a lock right now; for tests, read when nothing holds or waits. */
    internal val activeKeys: Int get() = entries.size

    suspend fun <T> withItem(itemId: String, block: suspend () -> T): T {
        val entry: Entry = guard.withLock { entries.getOrPut(itemId) { Entry() }.also { it.users++ } }
        try {
            return entry.mutex.withLock { block() }
        } finally {
            withContext(NonCancellable) {
                guard.withLock {
                    if (--entry.users == 0) entries.remove(itemId)
                }
            }
        }
    }
}

/** One replay per item id at a time, shared by every [OutcomeJournal] in the process. */
internal object OutcomeReplayLocks {

    private val locks: ItemLocks = ItemLocks()

    /** See [ItemLocks.activeKeys]. */
    internal val activeKeys: Int get() = locks.activeKeys

    suspend fun <T> withItem(itemId: String, block: suspend () -> T): T = locks.withItem(itemId, block)
}
