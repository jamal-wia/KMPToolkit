package io.github.jamal_wia.kmptoolkit.notification

import kotlin.time.Duration
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
     * The serial of the newest [PostTicket] issued per id, dropped by [forget] / [clear] and by
     * [complete] for a terminal frame. A ticket whose serial is not the id's current one belongs to
     * a frame that a cancel or a newer post has overtaken, and [isLive] says so.
     */
    private val latest: MutableMap<String, Long> = mutableMapOf()
    private var nextSerial: Long = 0L

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
     */
    fun prepare(
        id: String,
        progress: NotificationProgress?,
        content: Any? = null,
    ): PostTicket? = lock.withLock {
        when (val decision: Decision = decide(id, progress, content)) {
            Decision.Suppress -> null
            Decision.Reset -> {
                posted.remove(id)
                PostTicket(id, previous = null, recorded = null, serial = issue(id))
            }

            is Decision.Record -> {
                val recorded = Post(bucket = decision.bucket, content = content, at = timeSource.markNow())
                val previous: Post? = posted.put(id, recorded)
                PostTicket(id, previous = previous, recorded = recorded, serial = issue(id))
            }
        }
    }

    /** Lock held. Numbers a new ticket for [id] and makes it the id's newest. */
    private fun issue(id: String): Long {
        val serial: Long = ++nextSerial
        latest[id] = serial
        return serial
    }

    /**
     * Whether [ticket]'s frame is still the newest for its id and the id was not cancelled since.
     * The caller asks this right before the platform call, under the lock that [forget]'s caller
     * also holds around its own platform call, so a frame that lost the race to a cancel or to a
     * newer frame (a terminal "Done", say) is dropped instead of reappearing on top of it.
     */
    fun isLive(ticket: PostTicket): Boolean = lock.withLock { latest[ticket.id] == ticket.serial }

    /**
     * Marks [ticket]'s frame as shown. A terminal frame ([prepare] cleared the id) leaves nothing
     * to protect, so the id's newest-serial entry goes too — which makes any older frame still in
     * flight read as overtaken.
     */
    fun complete(ticket: PostTicket): Unit = lock.withLock {
        if (ticket.recorded == null && latest[ticket.id] == ticket.serial) latest.remove(ticket.id)
    }

    /**
     * Undoes [ticket]'s record because its post failed: restores what the id held before, but only
     * if the id still holds exactly what that ticket recorded. A [forget], a [clear], or a newer
     * post for the id in the meantime means the record is no longer this ticket's to undo, and
     * nothing changes. A terminal frame ([prepare] cleared the id) has nothing to restore.
     */
    fun rollback(ticket: PostTicket): Unit = lock.withLock {
        val recorded: Post = ticket.recorded ?: return@withLock
        if (posted[ticket.id] !== recorded) return@withLock
        val previous: Post? = ticket.previous
        if (previous == null) posted.remove(ticket.id) else posted[ticket.id] = previous
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

    /** Forgets [id]'s state, so its next determinate update posts. Called when it is cancelled. */
    fun forget(id: String): Unit = lock.withLock {
        posted.remove(id)
        latest.remove(id)
    }

    /** Forgets every id's state. Called on [Notifier.cancelAll]. */
    fun clear(): Unit = lock.withLock {
        posted.clear()
        latest.clear()
    }

    /**
     * A recorded decision to post. Opaque to callers: pass it to [rollback] if the post failed.
     */
    class PostTicket internal constructor(
        internal val id: String,
        internal val previous: Post?,
        internal val recorded: Post?,
        internal val serial: Long,
    )

    /** One recorded post. A plain class: [rollback] compares records by identity. */
    internal class Post(val bucket: Int, val content: Any?, val at: TimeMark)

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
