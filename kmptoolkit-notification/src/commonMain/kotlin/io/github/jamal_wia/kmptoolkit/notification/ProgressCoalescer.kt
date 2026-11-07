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
     * Decides whether the notification for [id] carrying [progress] should reach the platform,
     * **without recording anything**.
     *
     * Returns `null` when the frame is redundant. Otherwise it returns a [PostTicket]; the caller
     * posts and, **only once the platform accepted the post**, hands the ticket to [commit]. A
     * frame that was never shown must not become the baseline: if it did, a retry of the very same
     * progress after a failed post would be judged redundant and swallowed, though nothing on
     * screen ever showed it.
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
            Decision.Reset -> PostTicket(id, bucket = null, content = content)
            is Decision.Record -> PostTicket(id, bucket = decision.bucket, content = content)
        }
    }

    /**
     * Records [ticket]'s frame as the new baseline for its id. Call it after the platform accepted
     * the post, and never for a post that failed. A terminal frame ([prepare] answered it with no
     * bucket) clears the id's state instead, so the next run starts fresh.
     */
    fun commit(ticket: PostTicket): Unit = lock.withLock {
        val bucket: Int? = ticket.bucket
        if (bucket == null) {
            posted.remove(ticket.id)
        } else {
            posted[ticket.id] = Post(bucket = bucket, content = ticket.content, at = timeSource.markNow())
        }
    }

    /**
     * [prepare] and [commit] in one step, for a caller with nothing that can fail between deciding
     * and posting. A caller that posts to a platform must use the two calls and commit only on
     * success.
     */
    fun shouldPost(
        id: String,
        progress: NotificationProgress?,
        content: Any? = null,
    ): Boolean {
        val ticket: PostTicket = prepare(id, progress, content) ?: return false
        commit(ticket)
        return true
    }

    /**
     * The same question as [prepare], asked without producing a ticket.
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
    fun forget(id: String): Unit = lock.withLock { posted.remove(id) }

    /** Forgets every id's state. Called on [Notifier.cancelAll]. */
    fun clear(): Unit = lock.withLock { posted.clear() }

    /**
     * A decision to post, waiting for the platform's answer. Opaque to callers: pass it back to
     * [commit] once the post succeeded.
     */
    class PostTicket internal constructor(
        internal val id: String,
        internal val bucket: Int?,
        internal val content: Any?,
    )

    private data class Post(val bucket: Int, val content: Any?, val at: TimeMark)

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
