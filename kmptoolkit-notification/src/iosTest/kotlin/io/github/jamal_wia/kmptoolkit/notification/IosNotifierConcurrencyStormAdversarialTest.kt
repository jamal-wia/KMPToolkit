package io.github.jamal_wia.kmptoolkit.notification

import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionController
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import platform.Foundation.NSError
import platform.Foundation.NSLock
import platform.UserNotifications.UNNotificationRequest

/**
 * Many threads calling every public function of the iOS notifier at once, through the internal
 * [NotificationCenterPort] seam. Derived from docs 04 (post, cancel, cancelAll: a cancel that returns
 * is not followed by a post that was already in flight; answers are values) and the [IosNotifier] KDoc
 * (the center applies requests in call order, issued under one lock). Checked: the center is never
 * entered by two threads at once, every answer is Posted or Coalesced, a cancel issued while a post of
 * the id is in flight leaves the id not showing, and after the storm nothing leaked (no baseline, no
 * claim), so the next frame of every id posts.
 */
class IosNotifierConcurrencyStormAdversarialTest {

    /** A center that only records, and says so when two threads are inside it together. */
    private class Center : NotificationCenterPort {
        private val state = NSLock()
        private val probe = NSLock()
        val delivered: MutableSet<String> = mutableSetOf()
        var overlaps: Int = 0
        var adds: Int = 0

        private inline fun <T> exclusive(block: () -> T): T {
            if (!probe.tryLock()) {
                state.lock(); overlaps++; state.unlock()
                return block()
            }
            try {
                var spin = 0
                while (spin < 200) spin++
                return block()
            } finally {
                probe.unlock()
            }
        }

        override fun add(request: UNNotificationRequest, completion: (NSError?) -> Unit) = exclusive {
            state.lock(); delivered += request.identifier; adds++; state.unlock()
            completion(null)
        }

        override fun removeDelivered(ids: List<String>) = exclusive {
            state.lock(); delivered.removeAll(ids.toSet()); state.unlock()
        }

        override fun removePending(ids: List<String>) = exclusive { Unit }

        override fun removeAllDelivered() = exclusive {
            state.lock(); delivered.clear(); state.unlock()
        }

        fun snapshot(): Set<String> {
            state.lock()
            try {
                return delivered.toSet()
            } finally {
                state.unlock()
            }
        }
    }

    private class YieldingPermissions(val onCheck: suspend () -> Unit = {}) : PermissionController {
        override suspend fun check(permission: Permission): PermissionStatus {
            onCheck()
            yield()
            return PermissionStatus.Granted
        }

        override suspend fun request(permission: Permission): PermissionStatus = PermissionStatus.Granted
        override suspend fun request(permissions: Collection<Permission>): Map<Permission, PermissionStatus> =
            permissions.associateWith { PermissionStatus.Granted }

        override fun observe(permission: Permission): Flow<PermissionStatus> = flowOf(PermissionStatus.Granted)
        override fun openSettings(permission: Permission): Boolean = false
    }

    private fun note(percent: Int?): LocalNotification = LocalNotification(
        title = "T",
        body = "B",
        channel = NotificationChannelSpec("c", "C"),
        progress = percent?.let { NotificationProgress.Determinate(it) },
    )

    @Test
    fun `workers calling post cancel and cancelAll together never enter the center twice and leak nothing`() {
        val center = Center()
        val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO)
        val notifier: Notifier = IosNotifier(YieldingPermissions(), coalescer, center)
        val ids: List<String> = listOf("a", "b", "c")
        val answers: MutableList<NotificationResult> = mutableListOf()
        val answersLock = NSLock()

        runBlocking {
            withTimeout(60_000) {
                withContext(Dispatchers.Default) {
                    List(WORKERS) { worker: Int ->
                        async {
                            val random = Random(worker)
                            repeat(OPS) {
                                val id: String = ids.random(random)
                                when (random.nextInt(10)) {
                                    0 -> notifier.cancel(id)
                                    1 -> if (random.nextInt(4) == 0) notifier.cancelAll()
                                    else -> {
                                        val result: NotificationResult =
                                            notifier.post(id, note(listOf(0, 10, 50, 99, 100, null).random(random)))
                                        answersLock.lock(); answers += result; answersLock.unlock()
                                    }
                                }
                            }
                        }
                    }.awaitAll()
                }
            }
        }

        assertEquals(0, center.overlaps, "two threads were inside the notification center at once")
        assertTrue(answers.isNotEmpty())
        answers.forEach { assertTrue(it == NotificationResult.Posted || it == NotificationResult.Coalesced, "$it") }
        assertEquals(0, coalescer.claimCountForTest(), "a claim outlived its post")
        notifier.cancelAll()
        ids.forEach { notifier.cancel(it) }
        assertEquals(emptySet(), center.snapshot())
        runBlocking {
            ids.forEach { id: String ->
                assertEquals(NotificationResult.Posted, notifier.post(id, note(10)), "'$id' kept a ghost baseline after the storm")
                assertEquals(NotificationResult.Coalesced, notifier.post(id, note(10)), "'$id' lost its baseline")
            }
        }
    }

    @Test
    fun `a cancel issued while a post of the id is in flight leaves the id not showing`() {
        repeat(ROUNDS) { round: Int ->
            val center = Center()
            val started = CompletableDeferred<Unit>()
            val coalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO)
            val notifier: Notifier = IosNotifier(YieldingPermissions { started.complete(Unit) }, coalescer, center)

            runBlocking {
                withTimeout(30_000) {
                    withContext(Dispatchers.Default) {
                        val post = launch { notifier.post("x", note(50)) }
                        started.await()
                        notifier.cancel("x")
                        post.join()
                    }
                }
            }

            assertEquals(emptySet(), center.snapshot(), "round $round: the post landed after the cancel returned")
            assertEquals(0, coalescer.claimCountForTest(), "round $round: claim leaked")
        }
    }

    private companion object {
        const val WORKERS: Int = 8
        const val OPS: Int = 400
        const val ROUNDS: Int = 300
    }
}
