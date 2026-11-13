package io.github.jamal_wia.kmptoolkit.notification

import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionController
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSError
import platform.UserNotifications.UNNotificationRequest

/**
 * High contention on one iOS notifier from coroutines on a multi-threaded dispatcher, through the internal center
 * seam, with a center that answers from another thread after a random delay: many coroutines post frames of a handful
 * of ids while others cancel the posting jobs at random moments (a hard cancel, a short timeout) or call cancel and
 * cancelAll. Invariants once the storm has ended: nothing is stuck (a hard time limit), only cancellation escapes, the
 * coalescer holds no claim, a final cancelAll forgets every baseline, and every id accepts a fresh frame again.
 */
class IosNotifierCoroutineContentionAdversarialTest {

    private class AsyncCenter(private val scope: CoroutineScope) : NotificationCenterPort {
        private val lock = platform.Foundation.NSLock()
        private var adds: Int = 0

        override fun add(request: UNNotificationRequest, completion: (NSError?) -> Unit) {
            lock.lock(); val n: Int = ++adds; lock.unlock()
            scope.launch {
                if (n % 3 != 0) delay((n % 4).toLong())
                completion(null)
            }
        }

        override fun removeDelivered(ids: List<String>) = Unit
        override fun removePending(ids: List<String>) = Unit
        override fun removeAllDelivered() = Unit
    }

    private class AllowAll : PermissionController {
        override suspend fun check(permission: Permission): PermissionStatus = PermissionStatus.Granted
        override suspend fun request(permission: Permission): PermissionStatus = PermissionStatus.Granted
        override suspend fun request(permissions: Collection<Permission>): Map<Permission, PermissionStatus> =
            permissions.associateWith { PermissionStatus.Granted }
        override fun observe(permission: Permission): Flow<PermissionStatus> = flowOf(PermissionStatus.Granted)
        override fun openSettings(permission: Permission): Boolean = false
    }

    private fun frame(id: String, percent: Int): LocalNotification = LocalNotification(
        title = "t-$id", body = "b", channel = NotificationChannelSpec(id = "chan", name = "Chan"),
        progress = NotificationProgress.Determinate(percent),
    )

    @Test
    fun `cancelling posting coroutines at random moments leaves no claim and every id postable`() {
        val ids: List<String> = listOf("a", "b", "c")
        repeat(ROUNDS) { round: Int ->
            val centerScope = CoroutineScope(Dispatchers.Default)
            val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO)
            val notifier = IosNotifier(AllowAll(), coalescer, AsyncCenter(centerScope), notifierLock())
            val escaped: MutableList<String> = mutableListOf()
            val escapedLock = platform.Foundation.NSLock()
            fun record(e: Throwable) {
                escapedLock.lock(); escaped += "${e::class.simpleName}: ${e.message}"; escapedLock.unlock()
            }
            runBlocking {
                withTimeout(JOIN_MILLIS) {
                    val scope = CoroutineScope(Dispatchers.Default)
                    val jobs: List<Job> = List(COROUTINES) { index: Int ->
                        scope.launch {
                            val random = Random(round * 1000 + index)
                            val posts: MutableList<Job> = mutableListOf()
                            repeat(OPS) {
                                val id: String = ids[random.nextInt(ids.size)]
                                when (val dice: Int = random.nextInt(100)) {
                                    in 0..4 -> notifier.cancel(id)
                                    in 5..6 -> notifier.cancelAll()
                                    in 7..30 -> {
                                        val job: Job = launch {
                                            try {
                                                notifier.post(id, frame(id, random.nextInt(0, 100)))
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (e: Throwable) {
                                                record(e)
                                            }
                                        }
                                        posts += job
                                        if (random.nextBoolean()) job.cancel()
                                    }
                                    in 31..40 -> try {
                                        withTimeoutOrNull(random.nextLong(1, 3)) { notifier.post(id, frame(id, dice)) }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Throwable) {
                                        record(e)
                                    }
                                    else -> if (posts.isNotEmpty()) posts[random.nextInt(posts.size)].cancel()
                                }
                                if (random.nextInt(8) == 0) delay(1)
                            }
                            posts.joinAll()
                        }
                    }
                    jobs.joinAll()
                }
            }
            assertEquals(emptyList(), escaped.toList(), "round $round: an exception other than cancellation escaped a post")
            assertEquals(0, coalescer.claimCountForTest(), "round $round: a claim is left with no caller")
            notifier.cancelAll()
            assertEquals(0, coalescer.postedCountForTest(), "round $round: cancelAll left a baseline")
            ids.forEach { id: String ->
                val result: NotificationResult = runBlocking { withTimeout(JOIN_MILLIS) { notifier.post(id, frame(id, 40)) } }
                assertTrue(
                    result == NotificationResult.Posted || result is NotificationResult.Failed,
                    "round $round: fresh frame of '$id' was $result",
                )
            }
            centerScope.coroutineContext[Job]?.cancel()
        }
    }

    private companion object {
        const val ROUNDS: Int = 4
        const val COROUTINES: Int = 24
        const val OPS: Int = 100
        const val JOIN_MILLIS: Long = 60_000L
    }
}
