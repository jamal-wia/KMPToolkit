package io.github.jamal_wia.kmptoolkit.notification

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Decides whether a progress update is worth handing to the platform.
 *
 * A download that posts its notification on every byte — or every percent — is not just wasteful:
 * both platforms rate-limit an app that posts too fast, and Android's response is to **drop**
 * notifications, so an over-eager progress bar ends up freezing at whatever frame survived. Two
 * independent limits keep that from happening, and a post has to clear both:
 *
 * 1. **Bucket.** The percentage is floored to a multiple of `bucketPercent`; an update in the same
 *    bucket as the last posted one for that id is redundant — the bar would not visibly move.
 * 2. **Rate.** At least `minInterval` must have passed since the last post for that id. Bucketing
 *    alone bounds the number of posts per *run*, not per *second*, and a fast transfer crosses ten
 *    buckets in a blink.
 *
 * Two updates always post, whatever the limits say:
 *
 * - Anything that is not [NotificationProgress.Determinate] — `null` or
 *   [NotificationProgress.Indeterminate]. There is no percentage to compare, and this is what a
 *   terminal frame ("Download complete") looks like: it must never be swallowed.
 * - A determinate **100%**. It is the last frame of a run, and a bar stuck at 90 forever is the
 *   exact failure this class is supposed to prevent, not cause.
 *
 * Either way the id's state is then reset, so the next run starts fresh rather than measuring
 * against a stale bucket.
 *
 * The bucket only judges frames that differ in nothing but the percentage. An update whose content
 * changed — a new title, a body with a new figure in it, different buttons — is not held back by the
 * bucket, because dropping it would leave stale text on screen until the bar next crossed a bucket.
 * The **rate limit still applies** to it: a body that carries the percentage changes on every step,
 * and letting content changes bypass the rate as well would turn coalescing off for exactly the
 * notifications that need it most.
 *
 * **Thread-safe.** Notifications get posted from whatever thread finished a chunk of work, so the
 * state is guarded internally rather than by a note in the documentation.
 *
 * @param bucketPercent see [NotificationConfig.progressBucketPercent].
 * @param minInterval see [NotificationConfig.minProgressInterval].
 * @param timeSource where "now" comes from. Injected so the rate limit can be tested on virtual
 *   time — a test that verifies a 500 ms throttle by sleeping 500 ms is a slow test and a flaky one.
 */
internal class ProgressCoalescer(
    private val bucketPercent: Int = NotificationConfig.DEFAULT_PROGRESS_BUCKET_PERCENT,
    private val minInterval: Duration = NotificationConfig.DEFAULT_MIN_PROGRESS_INTERVAL,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {

    private val lock: NotifierLock = notifierLock()
    private val posted: MutableMap<String, Post> = mutableMapOf()

    /**
     * The newest [PostTicket] issued per id, dropped by [forget] / [clear], by [complete] for a
     * terminal frame and by [pruneStale]. A ticket that is not the id's current one belongs to a
     * frame that a cancel or a newer post has overtaken, and [isLive] says so.
     */
    private val latest: MutableMap<String, PostTicket> = mutableMapOf()

    /**
     * Per id, the record of the frame that most recently reached the platform ([complete]d and not
     * refused or abandoned since): the one baseline a [rollback] may restore. It is set when a frame
     * completes, never when one is prepared, because frames are prepared outside the post lock and a
     * newer frame can be prepared between an older frame's liveness check and its completion: a
     * snapshot taken at prepare time would then name a frame the tray no longer shows. Dropped by
     * [forget] / [clear], by [complete] for a terminal frame and by [pruneStale].
     */
    private val lastShown: MutableMap<String, Post> = mutableMapOf()

    /**
     * Per id, the tickets issued and not yet completed or rolled back, oldest first. When the newest
     * one fails, the newest of these takes over as the id's live frame (see [rollback]): a frame that
     * never reached the platform has overtaken nothing.
     */
    private val inFlight: MutableMap<String, MutableList<PostTicket>> = mutableMapOf()
    private var nextSerial: Long = 0L

    /**
     * Posts that have started and not yet ended, per id: see [claim]. Bounded by the number of posts
     * running at once, because every claim is released in a `finally`.
     */
    private val claims: MutableMap<String, MutableList<PostClaim>> = mutableMapOf()

    /** How long an id must have been quiet (see [Post.lastSeen]) before [pruneStale] drops it; far above any sensible rate limit. */
    private val staleAfter: Duration = maxOf(minInterval * STALE_INTERVAL_FACTOR, MIN_STALE_AFTER)
    private var lastPrune: TimeMark = timeSource.markNow()

    /**
     * Decides whether the notification for [id] carrying [progress] should reach the platform, and
     * **records the decision atomically** as the baseline for the next call.
     *
     * Returns `null` when the frame is redundant. Otherwise it returns a [PostTicket]: post, and if
     * the post **fails** (an exception, or the platform rejecting it) hand the ticket to
     * [rollback], so a retry of the very same progress is posted rather than judged redundant
     * against a frame that was never shown. Call it exactly once per attempted post.
     *
     * Recording at decision time keeps the rate limit and the cancel semantics atomic: two
     * concurrent posts cannot both pass, and [forget] / [clear] erase the record whenever they run.
     *
     * @param content everything about the post other than [progress], compared by equality. A change
     *   in it bypasses the bucket but not the rate limit. `null` compares equal to `null`.
     * @param claim the post's [claim], if it took one: when [forget] / [clear] ran since, the post
     *   was cancelled before it got here and the answer is `null` (the frame is dropped).
     */
    fun prepare(
        id: String,
        progress: NotificationProgress?,
        content: Any? = null,
        claim: PostClaim? = null,
    ): PostTicket? = lock.withLock {
        // A cancel that ran after the post started (see [claim]) has already said no to it.
        if (claim?.cancelled == true) return@withLock null
        pruneStale()
        when (val decision: Decision = decide(id, progress, content)) {
            Decision.Suppress -> {
                // A suppressed frame is a sign of life too: the run is live, only not worth a post.
                posted[id]?.lastSeen = timeSource.markNow()
                null
            }
            Decision.Reset -> {
                posted.remove(id)
                issue(id, recorded = null)
            }

            is Decision.Record -> {
                // What a rollback restores is decided when it runs (see [lastShown]), not here: the
                // frame that is on the tray by then may not be the one that is on it now.
                val recorded = Post(bucket = decision.bucket, content = content, at = timeSource.markNow())
                posted[id] = recorded
                issue(id, recorded = recorded)
            }
        }
    }

    /**
     * Lock held. Drops the baselines of ids that went quiet: no frame for the id, posted or suppressed,
     * reached [prepare] for longer than [staleAfter], without a terminal frame or a cancel (a
     * failed download, a notification the user swiped away), so they do not accumulate for the
     * notifier's lifetime. Dropping a baseline is always safe: the id's next frame simply posts, as
     * the first one for an id does. Scans at most once per tenth of [staleAfter], whatever the
     * number of tracked ids: a single id whose frame was never settled must age out as well.
     */
    private fun pruneStale() {
        if (lastPrune.elapsedNow() < staleAfter / PRUNE_SPACING) return
        lastPrune = timeSource.markNow()
        val iterator: MutableIterator<MutableMap.MutableEntry<String, Post>> = posted.entries.iterator()
        while (iterator.hasNext()) {
            val entry: MutableMap.MutableEntry<String, Post> = iterator.next()
            if (entry.value.lastSeen.elapsedNow() > staleAfter) {
                latest.remove(entry.key)
                inFlight.remove(entry.key)
                lastShown.remove(entry.key)
                iterator.remove()
            }
        }
        lastShown.values.removeAll { record: Post -> record.at.elapsedNow() > staleAfter }
        // Tickets with no baseline (terminal frames) and tickets whose caller never completed or
        // rolled them back have no `posted` entry to age out with, so they age out on their own.
        latest.values.removeAll { ticket: PostTicket -> ticket.at.elapsedNow() > staleAfter }
        val flights: MutableIterator<MutableMap.MutableEntry<String, MutableList<PostTicket>>> =
            inFlight.entries.iterator()
        while (flights.hasNext()) {
            val flight: MutableList<PostTicket> = flights.next().value
            flight.removeAll { ticket: PostTicket -> ticket.at.elapsedNow() > staleAfter }
            if (flight.isEmpty()) flights.remove()
        }
    }

    /** Test seam: how many ids the baseline map holds. */
    internal fun postedCountForTest(): Int = lock.withLock { posted.size }

    /** Test seam: how many ids the newest-serial map holds. */
    internal fun latestCountForTest(): Int = lock.withLock { latest.size }

    /** Lock held. Numbers a new ticket for [id] and makes it the id's newest. */
    private fun issue(id: String, recorded: Post?): PostTicket {
        val ticket = PostTicket(id, recorded, ++nextSerial, timeSource.markNow())
        latest[id] = ticket
        inFlight.getOrPut(id) { mutableListOf() }.add(ticket)
        return ticket
    }

    /**
     * Lock held. Takes [ticket] out of its id's in-flight list. A completed frame is on the tray, so
     * every older frame still in flight is out of date and leaves with it; a failed one takes only
     * itself.
     */
    private fun settle(ticket: PostTicket, completed: Boolean) {
        val flight: MutableList<PostTicket> = inFlight[ticket.id] ?: return
        if (completed) flight.removeAll { it.serial <= ticket.serial } else flight.remove(ticket)
        if (flight.isEmpty()) inFlight.remove(ticket.id)
    }

    /**
     * Whether [ticket]'s frame is still the newest for its id and the id was not cancelled since.
     * The caller asks this right before the platform call, under the lock that [forget]'s caller
     * also holds around its own platform call, so a frame that lost the race to a cancel or to a
     * newer frame (a terminal "Done", say) is dropped instead of reappearing on top of it.
     *
     * **A `false` answer is final.** The caller drops the frame and never settles the ticket, so the
     * answer also takes the ticket out of its id's in-flight list: an overtaken frame that gave up
     * must not be promoted back to live, nor become a baseline, when the newer frame later fails.
     * Ask it once per ticket, right before the platform call.
     */
    fun isLive(ticket: PostTicket): Boolean = lock.withLock {
        val live: Boolean = latest[ticket.id]?.serial == ticket.serial
        if (!live) settle(ticket, completed = false)
        live
    }

    /**
     * Marks [ticket]'s frame as shown. A terminal frame ([prepare] cleared the id) leaves nothing
     * to protect, so the id's newest-serial entry goes too — which makes any older frame still in
     * flight read as overtaken.
     *
     * @param acknowledged `true` when the platform has already accepted the frame (Android's `notify`
     *   returns only once it has), so nothing can undo it and the record sheds the baseline it kept
     *   for a rollback. `false` when the frame was only handed over and the outcome arrives later (the
     *   iOS notification center reports an error through a completion handler): the record then
     *   counts as shown but keeps that baseline until [acknowledge] or [rollback] settles it, so two
     *   refused frames whose refusals arrive in either order leave no baseline behind.
     */
    fun complete(ticket: PostTicket, acknowledged: Boolean = false): Unit = lock.withLock {
        settle(ticket, completed = true)
        val recorded: Post? = ticket.recorded
        if (recorded == null) {
            // A terminal frame is what the tray shows now; nothing before it is a baseline any more.
            lastShown.remove(ticket.id)
            if (latest[ticket.id]?.serial == ticket.serial) latest.remove(ticket.id)
            return@withLock
        }
        recorded.shown = true
        if (recorded.refused) return@withLock
        // Frames complete in tray order (the notifier completes under its post lock), so the frame
        // that was last shown when this one completes is the one a refusal of this one falls back to.
        recorded.fallback = if (acknowledged) null else lastShown[ticket.id]
        lastShown[ticket.id] = recorded
    }

    /** The platform accepted a frame that was [complete]d before its outcome was known. */
    fun acknowledge(ticket: PostTicket): Unit = lock.withLock {
        ticket.recorded?.fallback = null
    }

    /**
     * Undoes [ticket]'s record because its post failed.
     *
     * If the ticket was the id's newest and an older frame is still in flight, that frame takes over
     * as the live one and as the baseline: the failed frame never reached the platform, so it
     * overtook nothing. Otherwise the id's newest-serial entry goes (a frame that never reached the
     * platform will never be [complete]d, so nothing else would clear it) and, if the id still holds
     * exactly what the ticket recorded, the last baseline that was actually shown is restored. A
     * [forget], a [clear], or a newer post for the id in the meantime means the record is no longer
     * this ticket's to undo, and nothing changes. A terminal frame ([prepare] cleared the id) has
     * nothing to restore.
     *
     * A ticket that was already [complete]d without being acknowledged (the iOS order: handed to the
     * notification center first, refused later) may be rolled back too, in any order relative to the
     * other refusals of its id: a refused frame is skipped when a baseline is restored.
     */
    fun rollback(ticket: PostTicket): Unit = lock.withLock {
        // Whatever else is true, this frame is not on the tray: a later rollback must not restore it.
        ticket.recorded?.refused = true
        settleLastShown(ticket.id)
        settle(ticket, completed = false)
        if (latest[ticket.id]?.serial == ticket.serial) {
            val promoted: PostTicket? = inFlight[ticket.id]?.lastOrNull()
            if (promoted != null) {
                latest[ticket.id] = promoted
                if (ticket.recorded == null || posted[ticket.id] === ticket.recorded) {
                    val baseline: Post? = promoted.recorded
                    if (baseline == null) posted.remove(ticket.id) else posted[ticket.id] = baseline
                }
                return@withLock
            }
            latest.remove(ticket.id)
        }
        val recorded: Post = ticket.recorded ?: return@withLock
        if (posted[ticket.id] !== recorded) return@withLock
        val previous: Post? = lastShown[ticket.id]
        if (previous == null) posted.remove(ticket.id) else posted[ticket.id] = previous
    }

    /**
     * [ticket]'s frame was handed to the platform ([complete]d without acknowledgement) and its
     * outcome will never be known (the caller was cancelled while the platform was answering). The
     * tray may or may not show it, so neither it nor any baseline behind it may judge a later frame
     * redundant: the id is left with no baseline and its next frame posts. A no-op on what
     * [forget] / [clear] or a newer post already replaced.
     */
    fun abandon(ticket: PostTicket): Unit = lock.withLock {
        val recorded: Post = ticket.recorded ?: return@withLock
        // Unknown is "not a baseline" in both directions: refused makes `unrefused` skip this record,
        // and the cut fallback makes it stop there instead of falling through to an older baseline
        // that the tray may no longer show.
        recorded.refused = true
        recorded.fallback = null
        settleLastShown(ticket.id)
        if (posted[ticket.id] === recorded) posted.remove(ticket.id)
    }

    /** Lock held. Moves [lastShown] past refused or abandoned records, so it only ever names a record not known to be off the tray. */
    private fun settleLastShown(id: String) {
        val current: Post = lastShown[id] ?: return
        val settled: Post? = unrefused(current)
        if (settled == null) lastShown.remove(id) else lastShown[id] = settled
    }

    /**
     * Lock held. [from], or the first of the baselines behind it that was not refused: a frame that
     * was handed over optimistically and refused later is not what the tray shows.
     */
    private fun unrefused(from: Post?): Post? {
        var node: Post? = from
        while (node != null && node.refused) node = node.fallback
        return node
    }

    /**
     * The same question as [prepare], asked without recording anything.
     *
     * It exists so a caller can find out that a frame is redundant *before* paying for work that
     * only matters to a frame that will actually be shown — while still running every gate whose
     * answer could outrank "redundant". Nothing here is a commitment: [prepare] is what decides,
     * and it may disagree if time has passed in between.
     */
    fun wouldSuppress(id: String, progress: NotificationProgress?, content: Any? = null): Boolean =
        lock.withLock { decide(id, progress, content) is Decision.Suppress }

    private fun decide(id: String, progress: NotificationProgress?, content: Any?): Decision {
        if (progress !is NotificationProgress.Determinate) return Decision.Reset
        val percent: Int = progress.percent.coerceIn(0, NotificationConfig.MAX_PERCENT)
        if (percent == NotificationConfig.MAX_PERCENT) return Decision.Reset
        val bucket: Int = (percent / bucketPercent) * bucketPercent
        val previous: Post = posted[id] ?: return Decision.Record(bucket)
        if (previous.at.elapsedNow() < minInterval) return Decision.Suppress
        val redundant: Boolean = previous.bucket == bucket && previous.content == content
        return if (redundant) Decision.Suppress else Decision.Record(bucket)
    }

    /**
     * Registers a post of [id] that has just started, before its first suspension, so a [forget] or
     * [clear] that runs while the post is still on its way to [prepare] (suspended in a permission
     * check, say) is ordered against it: the post then learns, in [prepare], that it was cancelled.
     * Pass it to [prepare] and always [release] it in a `finally`.
     */
    fun claim(id: String): PostClaim = lock.withLock {
        val claim = PostClaim()
        claims.getOrPut(id) { mutableListOf() }.add(claim)
        claim
    }

    /** Ends [claim]; see [claim]. */
    fun release(claim: PostClaim): Unit = lock.withLock {
        val iterator: MutableIterator<MutableMap.MutableEntry<String, MutableList<PostClaim>>> =
            claims.entries.iterator()
        while (iterator.hasNext()) {
            val entry: MutableMap.MutableEntry<String, MutableList<PostClaim>> = iterator.next()
            if (entry.value.remove(claim)) {
                if (entry.value.isEmpty()) iterator.remove()
                return@withLock
            }
        }
    }

    /** Test seam: how many claims are registered over all ids. */
    internal fun claimCountForTest(): Int = lock.withLock { claims.values.sumOf { it.size } }

    /** Forgets [id]'s state, so its next determinate update posts. Called when it is cancelled. */
    fun forget(id: String): Unit = lock.withLock {
        claims.remove(id)?.forEach { it.cancelled = true }
        posted.remove(id)
        latest.remove(id)
        inFlight.remove(id)
        lastShown.remove(id)
    }

    /** Forgets every id's state. Called on [Notifier.cancelAll]. */
    fun clear(): Unit = lock.withLock {
        claims.values.forEach { list: MutableList<PostClaim> -> list.forEach { it.cancelled = true } }
        claims.clear()
        posted.clear()
        latest.clear()
        inFlight.clear()
        lastShown.clear()
    }

    /**
     * A recorded decision to post. Opaque to callers: pass it to [rollback] if the post failed.
     */
    class PostTicket internal constructor(
        internal val id: String,
        internal val recorded: Post?,
        internal val serial: Long,
        internal val at: TimeMark,
    )

    /** A post of one id that has started and not yet ended; see [claim]. */
    class PostClaim internal constructor() {

        /** Set by [forget] / [clear]; guarded by the coalescer's lock. */
        internal var cancelled: Boolean = false
    }

    private companion object {
        /** [staleAfter] is this many `minInterval`s, but never less than [MIN_STALE_AFTER]. */
        const val STALE_INTERVAL_FACTOR: Int = 120
        val MIN_STALE_AFTER: Duration = 10.minutes

        /** A scan runs at most once per `staleAfter / PRUNE_SPACING`. */
        const val PRUNE_SPACING: Int = 10
    }

    /** One recorded post. A plain class: [rollback] compares records by identity. */
    internal class Post(val bucket: Int, val content: Any?, val at: TimeMark) {

        /**
         * When a frame for this id last reached [prepare] while this record was the baseline, posted
         * or suppressed; what [pruneStale] ages. Not the rate-limit clock, which stays [at]. Guarded
         * by the coalescer's lock.
         */
        var lastSeen: TimeMark = at

        /**
         * The record that was on the platform when this one was handed over and its outcome was still
         * unknown (the iOS order), set by [complete]; `null` once the platform accepted this frame.
         */
        var fallback: Post? = null


        /** Set once the frame reached the platform; guarded by the coalescer's lock. */
        var shown: Boolean = false

        /** Set when the platform refused the frame; guarded by the coalescer's lock. */
        var refused: Boolean = false
    }

    /** What [decide] concluded, kept separate from acting on it so it can also be asked about. */
    private sealed interface Decision {

        /** Too soon after the last post, or the same content with the bar in the same bucket. */
        data object Suppress : Decision

        /** Always posts and clears the id's state — a terminal or non-determinate frame. */
        data object Reset : Decision

        /** Posts and becomes the new baseline. */
        data class Record(val bucket: Int) : Decision
    }
}
