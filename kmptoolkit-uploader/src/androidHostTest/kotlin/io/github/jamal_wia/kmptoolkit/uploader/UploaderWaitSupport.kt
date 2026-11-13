// timing-hygiene-file: poll-helper the one bounded polling loop of the WorkManager and real-executor tests
package io.github.jamal_wia.kmptoolkit.uploader

import androidx.work.WorkManager

/** Polling interval of [awaitCondition]; a poll, never a wait for an absence. */
private const val POLL_MILLIS: Long = 10L

/**
 * Polls [condition] every few milliseconds until it holds (true) or [timeoutMillis] pass (false). The caller keeps
 * its own assertion and message for the false case. A hang bound, not a measurement.
 */
internal fun pollUntil(timeoutMillis: Long, condition: () -> Boolean): Boolean {
    // timing-hygiene-ignore: TH2 bound the deadline of a bounded poll, never a measurement
    val deadline: Long = System.nanoTime() + timeoutMillis * 1_000_000L
    while (!condition()) {
        // timing-hygiene-ignore: TH2 bound the deadline of a bounded poll, never a measurement
        if (System.nanoTime() >= deadline) return false
        // timing-hygiene-ignore: TH1 poll the interval of the bounded poll above
        Thread.sleep(POLL_MILLIS)
    }
    return true
}

/**
 * Waits until [condition] holds and fails with [what] after [timeoutMillis] (a hang bound, not a measurement). For a
 * condition that names an EVENT of the code under test (a row gone, a delivery counted, a work spec finished).
 * Never a stand-in for "nothing else happens": use [awaitWorkQuiescent] for that.
 */
internal fun awaitCondition(timeoutMillis: Long = 15_000L, what: String = "condition", condition: () -> Boolean) {
    check(pollUntil(timeoutMillis, condition)) { "timed out after $timeoutMillis ms waiting for $what" }
}

/** True when no work spec carrying [tag] is running, enqueued or blocked: every job of the tag has finished. */
internal fun isWorkQuiescent(workManager: WorkManager, tag: String): Boolean =
    workManager.getWorkInfosByTag(tag).get().all { it.state.isFinished }

/**
 * The quiescence barrier that replaces a sleep before an absence assertion ("a stray extra upload would land
 * here"): waits until every job of [tag] has finished AND [extra] holds (a ledger drained, a row gone). A job
 * that finished has already read its answer, so a request it made is already recorded; a job still alive keeps
 * the barrier closed. The remaining blind spot, work enqueued after the barrier opened, is a hand-off the engine
 * would have to make for a row it no longer owns.
 */
internal fun awaitWorkQuiescent(
    workManager: WorkManager,
    tag: String,
    timeoutMillis: Long = 15_000L,
    what: String = "work of tag $tag to finish",
    extra: () -> Boolean = { true },
) {
    awaitCondition(timeoutMillis, what) { isWorkQuiescent(workManager, tag) && extra() }
}
