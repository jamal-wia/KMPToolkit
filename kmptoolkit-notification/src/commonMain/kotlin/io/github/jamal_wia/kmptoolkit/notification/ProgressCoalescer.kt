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
     * Whether the notification for [id] carrying [progress] should reach the platform.
     *
     * Calling this **records** the decision: a `true` becomes the baseline the next call is
     * measured against, so call it exactly once per attempted post and honour the answer.
     */
    fun shouldPost(id: String, progress: NotificationProgress?): Boolean = lock.withLock {
        when (val decision: Decision = decide(id, progress)) {
            Decision.Suppress -> false
            Decision.Reset -> {
                posted.remove(id)
                true
            }

            is Decision.Record -> {
                posted[id] = Post(bucket = decision.bucket, at = timeSource.markNow())
                true
            }
        }
    }

    /**
     * The same question as [shouldPost], asked **without** recording anything.
     *
     * It exists so a caller can find out that a frame is redundant *before* paying for work that
     * only matters to a frame that will actually be shown — while still running every gate whose
     * answer could outrank "redundant". Nothing here is a commitment: [shouldPost] is what decides,
     * and it may disagree if time has passed in between.
     */
    fun wouldSuppress(id: String, progress: NotificationProgress?): Boolean =
        lock.withLock { decide(id, progress) is Decision.Suppress }

    private fun decide(id: String, progress: NotificationProgress?): Decision {
        if (progress !is NotificationProgress.Determinate) return Decision.Reset
        val percent: Int = progress.percent.coerceIn(0, NotificationConfig.MAX_PERCENT)
        if (percent == NotificationConfig.MAX_PERCENT) return Decision.Reset
        val bucket: Int = (percent / bucketPercent) * bucketPercent
        val previous: Post = posted[id] ?: return Decision.Record(bucket)
        val tooSoon: Boolean = previous.at.elapsedNow() < minInterval
        return if (previous.bucket == bucket || tooSoon) Decision.Suppress else Decision.Record(bucket)
    }

    /** Forgets [id]'s state, so its next determinate update posts. Called when it is cancelled. */
    fun forget(id: String): Unit = lock.withLock { posted.remove(id) }

    /** Forgets every id's state. Called on [Notifier.cancelAll]. */
    fun clear(): Unit = lock.withLock { posted.clear() }

    private data class Post(val bucket: Int, val at: TimeMark)

    /** What [decide] concluded, kept separate from acting on it so it can also be asked about. */
    private sealed interface Decision {

        /** Redundant: the bar would not move, or the rate limit has not elapsed. */
        data object Suppress : Decision

        /** Always posts and clears the id's state — a terminal or non-determinate frame. */
        data object Reset : Decision

        /** Posts and becomes the new baseline. */
        data class Record(val bucket: Int) : Decision
    }
}
