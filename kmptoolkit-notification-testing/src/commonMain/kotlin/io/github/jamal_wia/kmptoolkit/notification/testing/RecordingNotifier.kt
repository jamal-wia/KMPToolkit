package io.github.jamal_wia.kmptoolkit.notification.testing

import io.github.jamal_wia.kmptoolkit.notification.LocalNotification
import io.github.jamal_wia.kmptoolkit.notification.NotificationResult
import io.github.jamal_wia.kmptoolkit.notification.Notifier

/**
 * A [Notifier] double that records what your code would have shown the user, and answers with
 * whatever [NotificationResult] the test wants.
 *
 * Three things it lets you assert, none of which a real notifier will:
 * - **What you posted, and in what order** — `assertEquals("Download complete",
 *   notifier.posted.last().notification.title)`.
 * - **What is still on screen** — [showing] applies posts, [cancel] and [cancelAll] the way a
 *   platform would, so "the progress notification is gone once the download finishes" is one
 *   assertion rather than a diff of the whole log.
 * - **How your code copes when nothing gets posted** — set [result] to
 *   [NotificationResult.PermissionDenied] or a [NotificationResult.ChannelBlocked] and check that
 *   the work itself still completes.
 *
 * **Recording is independent of [result].** A post is recorded even when the configured result says
 * the platform refused it, because the question the log answers is "did my code ask for this?" —
 * but [showing] is not updated for a refused post, because that question is "would the user see
 * it?", and the answer there is no. [NotificationResult.Coalesced] is the one exception worth
 * knowing: it is recorded and does update [showing], since a real coalesced post means the previous
 * frame is still up.
 *
 * **Nothing is coalesced here.** The double does not replicate the production coalescing rule, on
 * purpose: a test that has to reason about a 500 ms throttle to know what its subject posted is
 * testing this fixture instead of its subject. Set [result] to [NotificationResult.Coalesced] when
 * you want your code to face that branch.
 *
 * **Not thread-safe**, deliberately: the backing collections are plain ones. Drive it from a single
 * test coroutine and assert once the work under test has finished; making it concurrent would mean
 * an atomics dependency in an artifact whose whole value is being trivial.
 *
 * @param result what [post] reports back; mutable so one instance can switch mid-test.
 */
public class RecordingNotifier(
    public var result: NotificationResult = NotificationResult.Posted,
) : Notifier {

    private val recorded: MutableList<PostedNotification> = mutableListOf()
    private val current: MutableMap<String, LocalNotification> = mutableMapOf()
    private val cancelledIds: MutableList<String> = mutableListOf()
    private var cancelAllCalls: Int = 0

    /**
     * Every post attempt so far, oldest first, whatever [result] said about it.
     *
     * A snapshot: the returned list does not grow when more posts arrive.
     */
    public val posted: List<PostedNotification> get() = recorded.toList()

    /**
     * What would currently be on screen, keyed by the id it was posted under.
     *
     * Follows the same rules a platform does: a re-post under an existing id replaces it, [cancel]
     * removes one, [cancelAll] removes all, and a post whose [result] was a failure never gets here.
     */
    public val showing: Map<String, LocalNotification> get() = current.toMap()

    /**
     * Every id passed to [cancel], oldest first — including ids that were never showing, because
     * cancelling something already gone is a real call your code made.
     */
    public val cancelled: List<String> get() = cancelledIds.toList()

    /** How many times [cancelAll] was called. */
    public val cancelAllCount: Int get() = cancelAllCalls

    override suspend fun post(id: String, notification: LocalNotification): NotificationResult {
        recorded += PostedNotification(id = id, notification = notification)
        val outcome: NotificationResult = result
        // Only a real post changes what is on screen. Coalesced leaves whatever was already up
        // exactly where it was, and every failure leaves the screen untouched too.
        if (outcome == NotificationResult.Posted) current[id] = notification
        return outcome
    }

    override fun cancel(id: String) {
        cancelledIds += id
        current.remove(id)
    }

    override fun cancelAll() {
        cancelAllCalls++
        current.clear()
    }

    /**
     * Drops the recorded posts, the cancellations and everything [showing], leaving [result]
     * untouched.
     *
     * Useful to separate the arrange phase from the act phase when setup posts notifications of its
     * own.
     */
    public fun clear() {
        recorded.clear()
        current.clear()
        cancelledIds.clear()
        cancelAllCalls = 0
    }
}

/**
 * One [Notifier.post] call, as [RecordingNotifier] saw it.
 *
 * @property id the id it was posted under — the replace/cancel key.
 * @property notification exactly what the caller passed, unmodified.
 */
public data class PostedNotification(
    public val id: String,
    public val notification: LocalNotification,
)
