package io.github.jamal_wia.kmptoolkit.notification

import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import kotlin.concurrent.AtomicInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TestTimeSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import platform.Foundation.NSError
import platform.Foundation.NSLocalizedDescriptionKey
import platform.UserNotifications.UNNotificationRequest

/**
 * Round 18 (iOS versus Android): the real `UNUserNotificationCenter` answers on another thread, and sometimes
 * with an error, after the notifier has already moved on; every fake center of earlier rounds answers inside
 * `add`. Here the center delivers a request at once or rejects it, and tells the notifier from another thread
 * after a few yields. Four threads post, cancel and clear for a while; once everything has answered, the
 * property that the progress baseline is never a ghost must hold for every id: with the clock frozen (so the
 * rate limit never lapses) a determinate frame is answered Coalesced only if the tray shows a frame of that id.
 * Claims must all be released and no map may name an id twice over.
 */
class IosNotifierAsyncCenterGhostBaselineAdversarialTest {

    private class AsyncCenter(private val scope: CoroutineScope, private val random: Random, private val rejectOutOf: Int) : NotificationCenterPort {
        private val lock: NotifierLock = notifierLock()
        private val tray: MutableMap<String, UNNotificationRequest> = mutableMapOf()
        val pending: AtomicInt = AtomicInt(0)
        val answered: AtomicInt = AtomicInt(0)

        fun showing(id: String): Boolean = lock.withLock { tray.containsKey(id) }

        override fun add(request: UNNotificationRequest, completion: (NSError?) -> Unit) {
            val reject: Boolean = lock.withLock {
                val rejects: Boolean = random.nextInt(rejectOutOf) == 0
                if (!rejects) tray[request.identifier] = request
                rejects
            }
            pending.incrementAndGet()
            val yields: Int = lock.withLock { random.nextInt(0, 4) }
            scope.launch {
                repeat(yields) { yield() }
                completion(
                    if (reject) NSError.errorWithDomain("test.center", 7, mapOf<Any?, Any?>(NSLocalizedDescriptionKey to "rejected")) else null,
                )
                answered.incrementAndGet()
                pending.decrementAndGet()
            }
        }

        override fun removeDelivered(ids: List<String>) {
            lock.withLock { ids.forEach { tray.remove(it) } }
        }

        override fun removePending(ids: List<String>) = Unit

        override fun removeAllDelivered() {
            lock.withLock { tray.clear() }
        }
    }

    private val ids: List<String> = listOf("a", "b", "c")

    @Test
    fun `after threads and late errors settle no id keeps a baseline for a frame the tray does not show`() {
        repeat(40) { round: Int ->
            // Odd rounds are the targeted ones: only cancels and determinate frames on two ids, many refusals, so that the last
            // thing to happen to an id is often a refused first frame after a cancel.
            val targeted: Boolean = round % 2 == 1
            runBlocking {
                withTimeout(120_000) {
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                    val center = AsyncCenter(scope, Random(round * 17 + 3), rejectOutOf = if (targeted) 2 else 5)
                    val coalescer: ProgressCoalescer = NotifierProtocolFuzz.newCoalescer(TestTimeSource())
                    val notifier: Notifier = IosNotifier(MutablePermissions(PermissionStatus.Granted), coalescer, center)
                    val phase = StringBuilder("workers")
                    val results: List<NotificationResult> = try { withTimeout(40_000) { List(4) { worker: Int ->
                        async(Dispatchers.Default) {
                            val random = Random(round * 1000 + worker)
                            val seen: MutableList<NotificationResult> = mutableListOf()
                            repeat(if (targeted) 60 else 300) { step: Int ->
                                val id: String = (if (targeted) ids.take(2) else ids).random(random)
                                if (targeted) {
                                    if (random.nextInt(4) == 0) notifier.cancel(id) else seen += notifier.post(id, frame(step, NotificationProgress.Determinate(random.nextInt(0, 100))))
                                    if (step % 3 == 0) yield()
                                    return@repeat
                                }
                                when (random.nextInt(20)) {
                                    in 0..2 -> notifier.cancel(id)
                                    3 -> notifier.cancelAll()
                                    in 4..10 -> seen += notifier.post(id, frame(step, null))
                                    else -> seen += notifier.post(id, frame(step, NotificationProgress.Determinate(random.nextInt(0, 100))))
                                }
                                if (step % 7 == 0) yield()
                            }
                            seen
                        }
                    }.awaitAll().flatten() } } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        throw AssertionError("round $round: the four workers did not finish in 40 s (center answered ${center.answered.value}, still pending ${center.pending.value}): a post, cancel or cancelAll is stuck")
                    }
                    // Everything the center was handed has been answered.
                    try {
                        withTimeout(20_000) { while (center.pending.value > 0) yield() }
                    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                        throw AssertionError("round $round: ${center.pending.value} answers of the center never arrived")
                    }

                    assertTrue(
                        results.all { it is NotificationResult.Posted || it is NotificationResult.Coalesced || it is NotificationResult.Failed },
                        "round $round: an answer outside Posted, Coalesced and Failed: ${results.map { it::class.simpleName }.toSet()}",
                    )
                    assertEquals(0, coalescer.claimCountForTest(), "round $round: a claim was left registered")
                    assertTrue(coalescer.postedCountForTest() <= ids.size && coalescer.latestCountForTest() <= ids.size, "round $round: a map outgrew the ids")
                    for (id: String in ids) {
                        val tray: Boolean = center.showing(id)
                        val answer: NotificationResult = notifier.post(id, frame(9_999, NotificationProgress.Determinate(50)))
                        if (answer == NotificationResult.Coalesced) {
                            assertTrue(tray, "round $round id $id: a frame was held back as redundant although the tray shows nothing for it (ghost baseline)")
                        }
                    }
                    // Only now: the center answers through this scope, so the probe posts above needed it alive.
                    scope.coroutineContext[Job]!!.cancel()
                }
            }
        }
    }

    private fun frame(step: Int, progress: NotificationProgress?): LocalNotification = LocalNotification(
        title = "t${step % 3}",
        body = "b",
        channel = NotificationChannelSpec("c", "C"),
        progress = progress,
    )
}
