package io.github.jamal_wia.kmptoolkit.scheduler

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.WeakReference
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSError
import platform.UserNotifications.UNNotificationRequest

/**
 * Round 18 (leaks), iOS: a scheduler is collectable once dropped (the process-wide lock does not keep it), and
 * schedules cancelled while they wait behind a stuck center call leave neither the lock held nor their alarms
 * reachable.
 */
@OptIn(ExperimentalNativeApi::class, NativeRuntimeApi::class)
class IosAlarmSchedulerLeaksAdversarialTest {

    private class Center : NotificationCenterPort {
        val requests: LinkedHashMap<String, UNNotificationRequest> = LinkedHashMap()
        var stuck: CompletableDeferred<Unit>? = null
        override suspend fun isAuthorizationDenied(): Boolean = false
        override suspend fun pendingIdentifiers(): List<String> = requests.keys.toList()
        override suspend fun add(request: UNNotificationRequest): NSError? {
            stuck?.await()
            requests[request.identifier] = request
            return null
        }

        override suspend fun remove(ids: List<String>) {
            ids.forEach { requests.remove(it) }
        }
    }

    private fun gone(reference: WeakReference<*>): Boolean {
        repeat(20) {
            GC.collect()
            if (reference.value == null) return true
        }
        return reference.value == null
    }

    private fun alarm(i: Int, id: String = "id${i % 30}"): ScheduledAlarm = ScheduledAlarm(
        id = id,
        type = "T",
        fireAtEpochMillis = 1_900_000_000_000L + i,
        notification = AlarmNotification("t$i", "b$i", "c"),
        payload = mapOf("n" to "$i"),
    )

    @Test
    fun `a scheduler is collectable once dropped after thousands of cycles`() {
        fun use(): WeakReference<AlarmScheduler> {
            val center = Center()
            val scheduler: AlarmScheduler = IosAlarmScheduler(AlarmSoundResolver { null }, AlarmSchedulerConfig(), center)
            runBlocking {
                repeat(2_000) { i: Int ->
                    scheduler.schedule(alarm(i))
                    if (i % 4 == 0) scheduler.cancel("id${i % 30}")
                    if (i % 501 == 0) scheduler.cancelAll(List(30) { "id$it" })
                }
            }
            return WeakReference(scheduler)
        }
        assertTrue(gone(use()), "a dropped scheduler is held by something process wide")
    }

    @Test
    fun `schedules cancelled behind a stuck center call release the lock and their alarms`() = runBlocking {
        withTimeout(60_000) {
            val center = Center()
            val scheduler: AlarmScheduler = IosAlarmScheduler(AlarmSoundResolver { null }, AlarmSchedulerConfig(), center)
            val gate = CompletableDeferred<Unit>()
            center.stuck = gate
            val references: MutableList<WeakReference<AlarmNotification>> = mutableListOf()
            fun queued(i: Int) = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                val alarm: ScheduledAlarm = alarm(i)
                // The probe is the alarm's notification object, which a copy of the alarm shares: it finds a retained copy too.
                references += WeakReference(alarm.notification)
                scheduler.schedule(alarm)
            }
            val jobs = List(600) { i: Int -> queued(i) }
            jobs.forEach { it.cancel() }
            jobs.forEach { it.join() }
            center.stuck = null
            // Nothing is left holding the lock: another schedule and a cancel go straight through.
            assertEquals(AlarmScheduleResult.Exact, scheduler.schedule(alarm(1, "after")))
            scheduler.cancel("after")
            val survivors: Int = run {
                repeat(20) { GC.collect() }
                references.count { it.value != null }
            }
            assertTrue(survivors <= 5, "$survivors of ${references.size} cancelled schedules still have their alarm reachable")
        }
    }
}
