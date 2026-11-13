// timing-hygiene-file: poll-helper the one bounded polling loop of the WorkManager and real-executor tests
package io.github.jamal_wia.kmptoolkit.uploader

import androidx.work.WorkManager
import kotlinx.coroutines.flow.MutableStateFlow

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

/**
 * True when at least one work spec carries [tag] and none of them is running, enqueued or blocked. An empty tag is
 * NOT quiescent: a renamed tag must keep the barrier closed, never open it vacuously.
 */
internal fun isWorkQuiescent(workManager: WorkManager, tag: String): Boolean {
    val infos: List<androidx.work.WorkInfo> = workManager.getWorkInfosByTag(tag).get()
    return infos.isNotEmpty() && infos.all { it.state.isFinished }
}

/** The state of every work spec of [tag], for the message of a barrier that timed out. */
internal fun describeWork(workManager: WorkManager, tag: String): String =
    workManager.getWorkInfosByTag(tag).get().joinToString(prefix = "[", postfix = "]") { "${it.id.toString().take(8)}=${it.state}" }

/**
 * The quiescence barrier that replaces a sleep before an absence assertion ("a stray extra upload would land
 * here"). It opens when, checked in this order: [first] holds (the engine runs no attempt of the items: a cancelled
 * attempt may still be about to launch, and its launch enqueues synchronously before the attempt ends), then every
 * job of [tag] has finished (and at least one existed), then [extra] holds (a ledger drained, a row gone). A job
 * that finished has already read its answer, so a request it made is already recorded; a job still alive keeps the
 * barrier closed. [describe] adds the state of the engine and the ledger to the message of a timeout.
 *
 * Remaining blind spots: work enqueued after the barrier opened, which needs a hand-off for a row the engine no
 * longer owns; and a JDK-internal reconnect left in a server's accept backlog, which only a server that counts at
 * `accept` (not at the end of the request) can miss, and which needs a reset during the request head that these
 * peers never send.
 */
internal fun awaitWorkQuiescent(
    workManager: WorkManager,
    tag: String,
    timeoutMillis: Long = 15_000L,
    what: String = "work of tag $tag to finish",
    first: () -> Boolean = { true },
    describe: () -> String = { "" },
    extra: () -> Boolean = { true },
) {
    val opened: Boolean = pollUntil(timeoutMillis) { first() && isWorkQuiescent(workManager, tag) && extra() }
    check(opened) {
        "timed out after $timeoutMillis ms waiting for $what; work of tag $tag: ${describeWork(workManager, tag)}; ${describe()}"
    }
}

/**
 * Coroutines waiting in `UploaderEngineRegistry.await` for an engine: the collectors of its private slot. Read by
 * reflection so no production seam is needed; a renamed field fails loudly with `NoSuchFieldException` and never
 * passes. Compare with a value read before the waiter is started (other tests' waiters end on their own).
 */
internal fun engineWaiters(): Int {
    val field: java.lang.reflect.Field = UploaderEngineRegistry::class.java.getDeclaredField("slot").apply { isAccessible = true }
    return (field.get(null) as MutableStateFlow<*>).subscriptionCount.value
}
