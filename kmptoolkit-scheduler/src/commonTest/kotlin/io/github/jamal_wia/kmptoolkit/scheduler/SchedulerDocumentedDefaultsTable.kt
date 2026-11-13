package io.github.jamal_wia.kmptoolkit.scheduler

import io.github.jamal_wia.kmptoolkit.scheduler.testing.RecordingAlarmScheduler
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 24 (docs-only newcomer, Q4): every default the scheduler docs print or promise, one row each,
 * "documented" typed in from docs/kmptoolkit-scheduler 01 to 04 and 06 and "actual" read from a value built
 * without arguments. A row that differs names itself. Runs on the Android host and on iOS.
 */
internal class SchedulerDocumentedDefaultsTable {

    private class Row(val where: String, val documented: Any?, val actual: Any?)

    suspend fun documentedDefaultsTable() {
        val config = AlarmSchedulerConfig()
        val alarm = ScheduledAlarm("id", "T", 0L, AlarmNotification("t", "b", "c"))
        val recorder = RecordingAlarmScheduler()
        val answer: AlarmScheduleResult = recorder.schedule(alarm)
        val rows: List<Row> = listOf(
            Row("04 ScheduledAlarm.payload", emptyMap<String, String>(), alarm.payload),
            Row("04 AlarmSchedulerConfig.alarmIntentScheme (null derives <applicationId>.alarm)", null, config.alarmIntentScheme),
            Row("04 AlarmSchedulerConfig.alarmIdKey", "alarm_id", config.alarmIdKey),
            Row("04 AlarmSchedulerConfig.alarmTypeKey", "alarm_type", config.alarmTypeKey),
            Row("04 DEFAULT_ALARM_ID_KEY", "alarm_id", AlarmSchedulerConfig.DEFAULT_ALARM_ID_KEY),
            Row("04 DEFAULT_ALARM_TYPE_KEY", "alarm_type", AlarmSchedulerConfig.DEFAULT_ALARM_TYPE_KEY),
            Row("06 RecordingAlarmScheduler.resultFor defaults to Exact", AlarmScheduleResult.Exact, answer),
            Row("06 armed holds the scheduled alarm", listOf(alarm), recorder.armed),
            Row("06 scheduleCalls holds every call", listOf(alarm), recorder.scheduleCalls),
            Row("06 cancelledIds starts empty", emptyList<String>(), recorder.cancelledIds),
            Row("04 InexactReason has exactly the two documented values", 2, InexactReason.entries.size),
            Row("04 an alarm with a blank channel id is still legal (module neither creates nor validates the channel)", "", ScheduledAlarm("i", "T", 0L, AlarmNotification("t", "b", "")).notification.channelId),
        )
        assertTrue(rows.size >= 10, "the table has ${rows.size} rows")
        for (row: Row in rows) assertEquals(row.documented, row.actual, "documented default differs from the actual one: ${row.where}")
    }
}
