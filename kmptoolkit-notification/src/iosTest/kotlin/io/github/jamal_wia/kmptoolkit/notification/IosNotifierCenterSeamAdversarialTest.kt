package io.github.jamal_wia.kmptoolkit.notification

import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionController
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSError
import platform.UserNotifications.UNErrorCodeNotificationsNotAllowed
import platform.UserNotifications.UNErrorDomain
import platform.UserNotifications.UNNotificationInterruptionLevel.UNNotificationInterruptionLevelActive
import platform.UserNotifications.UNNotificationInterruptionLevel.UNNotificationInterruptionLevelPassive
import platform.UserNotifications.UNNotificationRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration

/**
 * The iOS notifier past its permission gate, driven through the internal [NotificationCenterPort]
 * seam with a fake center (a test binary cannot reach the real one). Derived from
 * `docs/kmptoolkit-notification/04-api-reference.md` (post, cancel, cancelAll) and the KDoc of
 * [IosNotifier]: a post completes when the center answers, a frame overtaken by a newer one or by a
 * cancel is dropped without reaching the center, cancel removes delivered and pending under exactly
 * that id, cancelAll removes delivered only and both forget the progress baselines.
 */
class IosNotifierCenterSeamAdversarialTest {

    private sealed interface Mode {
        data class Complete(val error: NSError?) : Mode
        data object Hold : Mode
        data object Throw : Mode
    }

    private class FakeCenter : NotificationCenterPort {
        var mode: Mode = Mode.Complete(null)
        val events: MutableList<String> = mutableListOf()
        val requests: MutableList<UNNotificationRequest> = mutableListOf()
        val held: MutableList<(NSError?) -> Unit> = mutableListOf()
        var onAdd: CompletableDeferred<Unit> = CompletableDeferred()

        override fun add(request: UNNotificationRequest, completion: (NSError?) -> Unit) {
            events += "add:${request.identifier}"
            requests += request
            when (val m: Mode = mode) {
                is Mode.Complete -> completion(m.error)
                Mode.Hold -> {
                    held += completion
                    onAdd.complete(Unit)
                }
                Mode.Throw -> throw RuntimeException("center refused")
            }
        }

        override fun removeDelivered(ids: List<String>) {
            events += "removeDelivered:${ids.joinToString("|")}"
        }

        override fun removePending(ids: List<String>) {
            events += "removePending:${ids.joinToString("|")}"
        }

        override fun removeAllDelivered() {
            events += "removeAllDelivered"
        }
    }

    private class AllowAll : PermissionController {
        override suspend fun check(permission: Permission): PermissionStatus = PermissionStatus.Granted
        override suspend fun request(permission: Permission): PermissionStatus = PermissionStatus.Granted
        override suspend fun request(permissions: Collection<Permission>): Map<Permission, PermissionStatus> =
            permissions.associateWith { PermissionStatus.Granted }
        override fun observe(permission: Permission): Flow<PermissionStatus> = flowOf(PermissionStatus.Granted)
        override fun openSettings(permission: Permission): Boolean = false
    }

    /** Runs [hook] once, before the block of the first `withLock`. */
    private class HookedLock(private val inner: NotifierLock, private val hook: () -> Unit) : NotifierLock {
        private var armed: Boolean = true
        override fun <T> withLock(block: () -> T): T = inner.withLock {
            if (armed) {
                armed = false
                hook()
            }
            block()
        }
    }

    private val coalescer: ProgressCoalescer = ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO)
    private val center: FakeCenter = FakeCenter()

    private fun notifier(postLock: NotifierLock = notifierLock(), c: ProgressCoalescer = coalescer): IosNotifier =
        IosNotifier(AllowAll(), c, center, postLock)

    private fun note(
        percent: Int? = null,
        importance: NotificationImportance = NotificationImportance.Default,
        sound: NotificationSound = NotificationSound.Default,
        category: String? = null,
    ): LocalNotification = LocalNotification(
        title = "Title",
        body = "Body",
        channel = NotificationChannelSpec(id = "chan", name = "Chan", importance = importance, sound = sound),
        progress = percent?.let { NotificationProgress.Determinate(it) },
        iosCategoryId = category,
    )

    private fun <T> bounded(block: suspend () -> T): T = runBlocking { withTimeout(TIMEOUT_MILLIS) { block() } }

    // T1: M287 M291 M294
    @Test
    fun `a post completes with Posted when the center answers without an error`() {
        val result: NotificationResult = bounded { notifier().post("a", note(percent = 10)) }

        assertEquals(NotificationResult.Posted, result)
        assertEquals(listOf("add:a"), center.events)
    }

    // T2: M292 W333 M293
    @Test
    fun `a frame overtaken before the hand-over is Coalesced and never reaches the center`() {
        val id = "a"
        val notifier: IosNotifier = notifier(HookedLock(notifierLock()) {
            coalescer.prepare(id, NotificationProgress.Determinate(60), null, null)
        })

        val result: NotificationResult = bounded { notifier.post(id, note(percent = 10)) }

        assertEquals(NotificationResult.Coalesced, result)
        assertEquals(emptyList(), center.events)
    }

    // T3: W328
    @Test
    fun `a post cancelled after the hand-over leaves no baseline so the same frame posts again`() {
        val notifier: IosNotifier = notifier()
        assertEquals(NotificationResult.Posted, bounded { notifier.post("a", note(percent = 10)) })
        center.mode = Mode.Hold
        runBlocking {
            withTimeout(TIMEOUT_MILLIS) {
                val job = launch(start = CoroutineStart.UNDISPATCHED) { notifier.post("a", note(percent = 50)) }
                center.onAdd.await()
                job.cancel()
                job.join()
            }
        }
        center.mode = Mode.Complete(null)

        val again: NotificationResult = bounded { notifier.post("a", note(percent = 10)) }

        assertEquals(NotificationResult.Posted, again)
    }

    // T4: W323
    @Test
    fun `a center that throws before the hand-over is Failed and the frame is rolled back`() {
        val notifier: IosNotifier = notifier()
        assertEquals(NotificationResult.Posted, bounded { notifier.post("a", note(percent = 10)) })
        center.mode = Mode.Throw

        val failed: NotificationResult = bounded { notifier.post("a", note(percent = 50)) }

        assertIs<NotificationResult.Failed>(failed)
        center.mode = Mode.Complete(null)
        // Rolled back to the confirmed 10% frame: the same 10% is redundant again.
        assertEquals(NotificationResult.Coalesced, bounded { notifier.post("a", note(percent = 10)) })
    }

    // T5: M300
    @Test
    fun `only the not allowed code of the notification error domain is PermissionDenied`() {
        val notifier: IosNotifier = notifier()

        center.mode = Mode.Complete(NSError.errorWithDomain(UNErrorDomain, UNErrorCodeNotificationsNotAllowed, null))
        assertEquals(NotificationResult.PermissionDenied, bounded { notifier.post("a", note()) })

        center.mode = Mode.Complete(NSError.errorWithDomain("other.domain", UNErrorCodeNotificationsNotAllowed, null))
        assertIs<NotificationResult.Failed>(bounded { notifier.post("b", note()) })

        center.mode = Mode.Complete(NSError.errorWithDomain(UNErrorDomain, UNErrorCodeNotificationsNotAllowed + 1, null))
        assertIs<NotificationResult.Failed>(bounded { notifier.post("c", note()) })
    }

    // T6: M303 W346
    @Test
    fun `cancel removes delivered then pending of exactly that id and forgets its baseline`() {
        val notifier: IosNotifier = notifier()
        assertEquals(NotificationResult.Posted, bounded { notifier.post("a", note(percent = 50)) })
        center.events.clear()

        notifier.cancel("a")

        assertEquals(listOf("removeDelivered:a", "removePending:a"), center.events)
        assertEquals(NotificationResult.Posted, bounded { notifier.post("a", note(percent = 50)) })
    }

    // T7: M306
    @Test
    fun `cancelAll removes delivered only and forgets every baseline`() {
        val notifier: IosNotifier = notifier()
        assertEquals(NotificationResult.Posted, bounded { notifier.post("a", note(percent = 50)) })
        center.events.clear()

        notifier.cancelAll()

        assertEquals(listOf("removeAllDelivered"), center.events)
        assertEquals(NotificationResult.Posted, bounded { notifier.post("a", note(percent = 50)) })
    }

    // T8: M308 M309 M312
    @Test
    fun `the request carries the content of the notification`() {
        val notifier: IosNotifier = notifier()
        bounded { notifier.post("id-1", note(category = "cat", sound = NotificationSound.Silent)) }
        bounded {
            notifier.post(
                "id-2",
                note(importance = NotificationImportance.Low),
            )
        }
        bounded { notifier.post("id-3", note(importance = NotificationImportance.High)) }

        val silent: UNNotificationRequest = center.requests[0]
        assertEquals("id-1", silent.identifier)
        assertNull(silent.trigger)
        assertEquals("Title", silent.content.title)
        assertEquals("Body", silent.content.body)
        assertEquals("chan", silent.content.threadIdentifier)
        assertEquals("cat", silent.content.categoryIdentifier)
        assertNull(silent.content.sound)
        assertEquals(UNNotificationInterruptionLevelActive, silent.content.interruptionLevel)

        val low: UNNotificationRequest = center.requests[1]
        assertNotNull(low.content.sound)
        assertEquals(UNNotificationInterruptionLevelPassive, low.content.interruptionLevel)
        assertEquals(UNNotificationInterruptionLevelActive, center.requests[2].content.interruptionLevel)
    }

    private companion object {
        const val TIMEOUT_MILLIS: Long = 10_000L
    }
}
