package io.github.jamal_wia.kmptoolkit.scheduler

import io.github.jamal_wia.kmptoolkit.scheduler.testing.RecordingAlarmScheduler
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Round 24 (docs-only newcomer): the Kotlin samples of docs/kmptoolkit-scheduler/01 to 04 and 06, typed in as a
 * reader would copy them (the app's own store, strings and lesson ids replaced by plain values) and run against
 * `RecordingAlarmScheduler`, in the order of the getting-started steps. Nothing here was written from source.
 * Runs on the Android host and on iOS.
 */
internal class SchedulerDocsNewcomerSamples {

    private class StudyReminderHandler : AlarmHandler {
        val fired: MutableList<ScheduledAlarm> = mutableListOf()
        override val type: String = "STUDY_REMINDER"
        override suspend fun onFire(alarm: ScheduledAlarm) {
            fired += alarm
        }
    }

    private object Strings {
        val reminderTitle: String = "Time to study"
        fun reminderBody(lessonName: String): String = "Open $lessonName"
    }

    private fun studyAlarm(lessonId: String, fireAtEpochMillis: Long): ScheduledAlarm = ScheduledAlarm(
        id = "study-$lessonId",
        type = "STUDY_REMINDER",
        fireAtEpochMillis = fireAtEpochMillis,
        notification = AlarmNotification(
            title = Strings.reminderTitle,
            body = Strings.reminderBody("lesson $lessonId"),
            channelId = "reminders",
        ),
        payload = mapOf("lessonId" to lessonId),
    )

    suspend fun gettingStartedStepFive() {
        val scheduler: AlarmScheduler = RecordingAlarmScheduler()
        val recorder: RecordingAlarmScheduler = scheduler as RecordingAlarmScheduler
        val marks: MutableList<String> = mutableListOf()

        suspend fun scheduleAndHandle(lessonId: String) {
            val result: AlarmScheduleResult = scheduler.schedule(studyAlarm(lessonId, 1_700_000_000_000L))
            when (result) {
                AlarmScheduleResult.Exact -> Unit
                is AlarmScheduleResult.Inexact -> marks += "markMayBeLate:${result.reason}"
                is AlarmScheduleResult.Failed -> marks += "markNotScheduled:${result.reason}"
            }
        }

        scheduleAndHandle("1")
        assertEquals(listOf("study-1"), recorder.armed.map { it.id })
        assertEquals(mapOf("lessonId" to "1"), recorder.armed.single().payload)
        assertEquals(emptyList(), marks)

        recorder.resultFor = { AlarmScheduleResult.Inexact(InexactReason.EXACT_ALARM_PERMISSION_MISSING) }
        scheduleAndHandle("2")
        recorder.resultFor = { AlarmScheduleResult.Failed(AlarmFailure.NotificationPermissionDenied) }
        scheduleAndHandle("3")
        assertEquals(
            listOf("markMayBeLate:EXACT_ALARM_PERMISSION_MISSING", "markNotScheduled:NotificationPermissionDenied"),
            marks,
        )
        // Inexact is armed, Failed is not (docs 03 table).
        assertEquals(listOf("study-1", "study-2"), recorder.armed.map { it.id })
        assertEquals(listOf("study-1", "study-2", "study-3"), recorder.scheduleCalls.map { it.id })

        scheduler.cancel("study-1")
        scheduler.cancelAll(listOf("study-2", "never-armed"))
        assertTrue(recorder.armed.isEmpty())
        assertEquals(listOf("study-1", "study-2", "never-armed"), recorder.cancelledIds)
    }

    suspend fun guideReconcilePass() {
        val scheduler = RecordingAlarmScheduler()
        val now = 1_700_000_000_000L
        val table: Map<String, Long> = mapOf("reminder-1" to now - 1_000, "reminder-2" to now + 60_000, "reminder-3" to now + 120_000)

        suspend fun reconcile() {
            val (future: Map<String, Long>, stale: Map<String, Long>) = table.entries.partition { it.value > now }
                .let { (a, b) -> a.associate { it.key to it.value } to b.associate { it.key to it.value } }
            scheduler.cancelAll(stale.keys)
            future.forEach { (id: String, at: Long) ->
                scheduler.schedule(
                    ScheduledAlarm(id, "STUDY_REMINDER", at, AlarmNotification("t", "b", "reminders")),
                )
            }
        }

        reconcile()
        val first: List<ScheduledAlarm> = scheduler.armed
        reconcile()
        assertEquals(first, scheduler.armed)
        assertEquals(listOf("reminder-2", "reminder-3"), scheduler.armed.map { it.id })
    }

    suspend fun guideIdsAndTypes() {
        val scheduler = RecordingAlarmScheduler()
        scheduler.schedule(studyAlarm("a", 1_000))
        scheduler.schedule(studyAlarm("b", 2_000))
        scheduler.schedule(studyAlarm("a", 3_000))
        assertEquals(listOf("study-a", "study-b"), scheduler.armed.map { it.id }, "armed is in the order the ids were first scheduled")
        assertEquals(3_000, scheduler.armed.first().fireAtEpochMillis)
        assertEquals(3, scheduler.scheduleCalls.size)

        val study = StudyReminderHandler()
        val handlers: List<AlarmHandler> = listOf(study)
        assertSame(study, handlers.handlerFor("STUDY_REMINDER"))
        assertNull(handlers.handlerFor("study_reminder"))
        assertNull(handlers.handlerFor("SOMETHING_ELSE"))
        assertNull(emptyList<AlarmHandler>().handlerFor("STUDY_REMINDER"))
        val second = object : AlarmHandler {
            override val type: String = "STUDY_REMINDER"
            override suspend fun onFire(alarm: ScheduledAlarm) = Unit
        }
        assertSame(study, listOf(study, second).handlerFor("STUDY_REMINDER"), "the first of two sharing a type wins")
        assertSame(second, listOf(second, study).handlerFor("STUDY_REMINDER"))
    }

    suspend fun testingDocDouble() {
        val scheduler = RecordingAlarmScheduler(
            resultFor = { AlarmScheduleResult.Inexact(InexactReason.EXACT_ALARM_PERMISSION_MISSING) },
        )
        scheduler.schedule(studyAlarm("a", 1_000))
        scheduler.resultFor = { AlarmScheduleResult.Failed(AlarmFailure.PlatformError("no")) }
        val refused: AlarmScheduleResult = scheduler.schedule(studyAlarm("a", 9_000))
        assertEquals(AlarmScheduleResult.Failed(AlarmFailure.PlatformError("no")), refused)
        assertEquals(1_000, scheduler.armed.single().fireAtEpochMillis, "a refused replacement leaves the armed alarm as it was")
        assertEquals(2, scheduler.scheduleCalls.size)
        scheduler.clear()
        assertTrue(scheduler.armed.isEmpty())
        assertTrue(scheduler.scheduleCalls.isEmpty())
        assertTrue(scheduler.cancelledIds.isEmpty())
        assertEquals(AlarmScheduleResult.Failed(AlarmFailure.PlatformError("no")), scheduler.schedule(studyAlarm("z", 1)))

        val handler = StudyReminderHandler()
        val alarm: ScheduledAlarm = studyAlarm("42", 5)
        handler.onFire(alarm)
        assertEquals(listOf(alarm), handler.fired)
    }

    fun apiReferenceValidations() {
        assertFailsWith<IllegalArgumentException> { studyAlarm("1", 0).copy(id = " ") }
        assertFailsWith<IllegalArgumentException> { studyAlarm("1", 0).copy(type = "") }
        // Past values and an empty notification text are accepted.
        studyAlarm("1", -5).copy(notification = AlarmNotification("", "", ""))
        assertFailsWith<IllegalArgumentException> { AlarmSchedulerConfig(alarmIdKey = " ") }
        assertFailsWith<IllegalArgumentException> { AlarmSchedulerConfig(alarmTypeKey = "") }
        assertFailsWith<IllegalArgumentException> { AlarmSchedulerConfig(alarmIdKey = "k", alarmTypeKey = "k") }
        for (reserved: String in listOf(
            "kmptoolkit_alarm_fire_at", "kmptoolkit_alarm_title", "kmptoolkit_alarm_body",
            "kmptoolkit_alarm_channel", "kmptoolkit_alarm_payload",
        )) {
            assertFailsWith<IllegalArgumentException>("id key $reserved") { AlarmSchedulerConfig(alarmIdKey = reserved) }
            assertFailsWith<IllegalArgumentException>("type key $reserved") { AlarmSchedulerConfig(alarmTypeKey = reserved) }
        }
        assertFailsWith<IllegalArgumentException> { AlarmSchedulerConfig(alarmIntentScheme = "1bad") }
        assertFailsWith<IllegalArgumentException> { AlarmSchedulerConfig(alarmIntentScheme = "") }
        AlarmSchedulerConfig(alarmIntentScheme = "com.example.alarm")

        val resolver = AlarmSoundResolver { channelId -> if (channelId == "reminders") "reminder.caf" else null }
        assertEquals("reminder.caf", resolver.soundFor("reminders"))
        assertNull(resolver.soundFor("other"))
        val whenResolver = AlarmSoundResolver { channelId ->
            when (channelId) {
                "reminders" -> "reminder.caf"
                "alerts" -> "alert.caf"
                else -> null // platform default
            }
        }
        assertEquals("alert.caf", whenResolver.soundFor("alerts"))
        assertNull(AlarmSoundResolver { null }.soundFor("anything"))
    }
}
