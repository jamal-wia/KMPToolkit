package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.logging.i
import io.github.jamal_wia.kmptoolkit.logging.w
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.days

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

    /** A usable [response], written at [writtenAtMillis]. */
    class Valid(val response: UploadResponse, val writtenAtMillis: Long) : JournalEntry
}

/** How a replay of one entry ended. */
internal enum class ReplayOutcome {

    /** There was no entry. */
    NoEntry,

    /** There was an entry, but it was expired, of an unknown version or unreadable; it was deleted. */
    Discarded,

    /** The entry reached the engine, which settled (or ignored) it; the entry was deleted. */
    Replayed,

    /** The entry did not reach an engine; it stays for the next attempt. */
    Kept,
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
 * Nothing here throws for a storage problem: a failed write means the journal does not protect that
 * outcome — today's behaviour — and a failed read or delete is logged.
 */
internal class OutcomeJournal(
    private val storage: OutcomeStorage,
    private val clock: UploaderClock = UploaderClock.System,
    private val logger: Logger = NoopLogger,
    private val retentionMillis: Long = RETENTION_MILLIS,
) {

    /**
     * Records [response] for [itemId], atomically. The body and headers are held to the engine's own ceilings.
     *
     * @return whether the entry is now on disk; `false` means the outcome is not protected.
     */
    fun record(itemId: String, response: UploadResponse): Boolean {
        if (!isJournalableId(itemId)) return false
        return try {
            val bytes: ByteArray = OutcomeCodec.encode(response.enforced(MAX_RESPONSE_BODY_BYTES), clock.nowEpochMillis())
            storage.write(itemId, bytes)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            logger.w(failure) { "Could not journal the upload outcome — item=$itemId" }
            false
        }
    }

    /** Reads the entry of [itemId]; an entry that cannot be used is deleted and reported as [JournalEntry.Discarded]. */
    fun inspect(itemId: String): JournalEntry {
        if (!isJournalableId(itemId)) return JournalEntry.Absent
        val bytes: ByteArray = try {
            storage.read(itemId)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            logger.w(failure) { "Could not read the journaled upload outcome — item=$itemId" }
            null
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
        return JournalEntry.Valid(decoded.response, decoded.writtenAtMillis)
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
     * Hands the entry of [itemId], if there is a usable one, to [complete] — the engine's settlement — and
     * deletes it once [complete] returned `true`. An id the engine does not know settles nothing and
     * returns `true`, so such an entry is deleted too; an unreachable engine returns `false` and the entry
     * stays for the next attempt.
     */
    suspend fun replay(itemId: String, complete: suspend (String, UploadResponse) -> Boolean): ReplayOutcome =
        when (val entry: JournalEntry = inspect(itemId)) {
            JournalEntry.Absent -> ReplayOutcome.NoEntry
            JournalEntry.Discarded -> ReplayOutcome.Discarded
            is JournalEntry.Valid -> replayValid(itemId, entry, complete)
        }

    /** [replay] for an entry already inspected. */
    suspend fun replayValid(
        itemId: String,
        entry: JournalEntry.Valid,
        complete: suspend (String, UploadResponse) -> Boolean,
    ): ReplayOutcome {
        logger.i { "Replaying the journaled upload outcome — item=$itemId, status=${entry.response.statusCode}" }
        if (!complete(itemId, entry.response)) return ReplayOutcome.Kept
        delete(itemId)
        return ReplayOutcome.Replayed
    }

    /**
     * Replays or deletes every entry no one claimed — those of items whose transport never ran again — and
     * clears leftovers of cut-short writes. The 7-day expiry applies as in [inspect].
     */
    suspend fun sweep(complete: suspend (String, UploadResponse) -> Boolean) {
        val ids: List<String> = try {
            storage.purgeLeftovers()
            storage.itemIds()
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            logger.w(failure) { "Could not list the journaled upload outcomes." }
            return
        }
        for (itemId in ids) replay(itemId, complete)
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

    class Ok(val response: UploadResponse, val writtenAtMillis: Long) : DecodedOutcome

    /** A format version this code does not know — written by another release. */
    data class UnknownVersion(val version: Int) : DecodedOutcome

    /** Not a complete entry of the known version. */
    data object Corrupt : DecodedOutcome
}

/**
 * The on-disk form of an entry, version 1: `int version, int status, long writtenAt, byte truncated,
 * int headerCount, (int nameLength, name, int valueLength, value) * headerCount, int bodyLength, body`,
 * big-endian, strings in UTF-8. Every length is checked against what is left, so a short or damaged
 * file reads as [DecodedOutcome.Corrupt] and never throws.
 */
internal object OutcomeCodec {

    const val VERSION: Int = 1

    fun encode(response: UploadResponse, writtenAtMillis: Long): ByteArray {
        val out = ByteSink()
        out.int(VERSION)
        out.int(response.statusCode)
        out.long(writtenAtMillis)
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
            if (version != VERSION) return DecodedOutcome.UnknownVersion(version)
            val status: Int = input.int()
            val writtenAt: Long = input.long()
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
            DecodedOutcome.Ok(UploadResponse(status, headers, body, truncated), writtenAt)
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
