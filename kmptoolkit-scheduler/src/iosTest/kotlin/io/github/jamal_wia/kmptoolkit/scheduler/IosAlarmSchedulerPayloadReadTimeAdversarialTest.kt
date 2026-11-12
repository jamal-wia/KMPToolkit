package io.github.jamal_wia.kmptoolkit.scheduler

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSError
import platform.UserNotifications.UNNotificationRequest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The iOS twin of the Android payload read-time test: `docs/kmptoolkit-scheduler/06-testing.md` says
 * the real schedulers write the payload into the platform request at the call. The iOS scheduler
 * builds its `userInfo` only after it has won the process-wide lock, so a payload map the caller edits
 * while its `schedule` waits behind a slow one is armed with the later contents.
 */
class IosAlarmSchedulerPayloadReadTimeAdversarialTest {

    private class GatedCenter(private val authGate: CompletableDeferred<Unit>) : NotificationCenterPort {
        val userInfoById: MutableMap<String, Map<Any?, *>> = mutableMapOf()
        private var first: Boolean = true

        override suspend fun isAuthorizationDenied(): Boolean {
            if (first) {
                first = false
                authGate.await()
            }
            return false
        }

        override suspend fun pendingIdentifiers(): List<String> = userInfoById.keys.toList()

        override suspend fun add(request: UNNotificationRequest): NSError? {
            userInfoById[request.identifier] = request.content.userInfo
            return null
        }

        override suspend fun remove(ids: List<String>) {
            ids.forEach { userInfoById.remove(it) }
        }
    }

    private fun alarm(id: String, payload: Map<String, String> = emptyMap()): ScheduledAlarm = ScheduledAlarm(
        id = id,
        type = "TEST",
        fireAtEpochMillis = 10_000_000_000_000L,
        notification = AlarmNotification(title = "t", body = "b", channelId = "c"),
        payload = payload,
    )

    private fun queuedBehindSlowSchedule(mutate: (MutableMap<String, String>) -> Unit): GatedCenter {
        val gate: CompletableDeferred<Unit> = CompletableDeferred()
        val center = GatedCenter(gate)
        runBlocking {
            try {
                withTimeout(TIMEOUT_MILLIS) {
                    val subject = IosAlarmScheduler(AlarmSoundResolver { null }, AlarmSchedulerConfig(), center)
                    val holder = launch(start = CoroutineStart.UNDISPATCHED) { subject.schedule(alarm("holder")) }
                    val payload: MutableMap<String, String> = mutableMapOf("k" to "v1")
                    val queued = launch(start = CoroutineStart.UNDISPATCHED) { subject.schedule(alarm("queued", payload)) }
                    mutate(payload)
                    gate.complete(Unit)
                    holder.join()
                    queued.join()
                }
            } finally {
                gate.complete(Unit)
            }
        }
        return center
    }

    private fun payloadOf(center: GatedCenter): Map<String, Any?> =
        center.userInfoById.getValue("queued").entries
            .associate { it.key.toString() to it.value }
            .filterKeys { it == "k" || it == "extra" }

    @Test
    fun `a payload map edited while schedule waits for the lock is armed as it was when schedule was called`() {
        val center: GatedCenter = queuedBehindSlowSchedule { it["k"] = "v2" }

        assertEquals(mapOf<String, Any?>("k" to "v1"), payloadOf(center))
    }

    @Test
    fun `a payload map that gains a key while schedule waits for the lock does not arm the new key`() {
        val center: GatedCenter = queuedBehindSlowSchedule { it["extra"] = "x" }

        assertEquals(mapOf<String, Any?>("k" to "v1"), payloadOf(center))
    }

    @Test
    fun `control - an untouched payload queued behind a slow schedule is armed whole`() {
        val center: GatedCenter = queuedBehindSlowSchedule { }

        assertEquals(mapOf<String, Any?>("k" to "v1"), payloadOf(center))
    }

    private companion object {
        const val TIMEOUT_MILLIS: Long = 10_000L
    }
}
