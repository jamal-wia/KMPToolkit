package io.github.jamal_wia.kmptoolkit.scheduler

import io.github.jamal_wia.kmptoolkit.scheduler.testing.RecordingAlarmScheduler
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Round 23 (fakes versus real, a written table): every answer and every resulting armed set is written here, by
 * hand, from the scheduler guide and API reference, never read off an implementation. Each row runs through a real
 * scheduler over a [SchedulerFuzzHost] and through [RecordingAlarmScheduler] (told the answer the table expects). The
 * real scheduler must answer `Exact` as the table says, and after every row the real system, the double's
 * `armed` list (in first-scheduled order, as its docs say) and the row's own expectation must be the same. The same
 * rows run on the Android host (API 24, no exact-alarm permission needed, and API 34 with it granted) and on the
 * iOS simulator over a fake notification center.
 */
internal object SchedulerScenarioTable {

    /** Every id any row uses, so a host can clear them. */
    val IDS: List<String> = listOf("a", "b", "c", "A", "a b/c?d#e", "\uD800", "?", "é💥")

    private const val T: Long = 1_900_000_000_000L

    private class Run(val host: SchedulerFuzzHost) {
        var real: AlarmScheduler = host.newScheduler()
        val fake = RecordingAlarmScheduler()
        val scheduled: MutableMap<String, ScheduledAlarm> = linkedMapOf()

        suspend fun schedule(
            id: String,
            at: Long = T,
            type: String = "T",
            title: String = "t",
            body: String = "b",
            payload: Map<String, String> = emptyMap(),
        ) {
            val alarm = ScheduledAlarm(id, type, at, AlarmNotification(title, body, "c"), payload)
            assertEquals(AlarmScheduleResult.Exact, real.schedule(alarm), "the real scheduler's answer for schedule($id)")
            fake.resultFor = { AlarmScheduleResult.Exact }
            assertEquals(AlarmScheduleResult.Exact, fake.schedule(alarm))
            scheduled[id] = alarm
        }

        suspend fun cancel(id: String) {
            real.cancel(id)
            fake.cancel(id)
            scheduled.remove(id)
        }

        suspend fun cancelAll(ids: List<String>) {
            real.cancelAll(ids)
            fake.cancelAll(ids)
            ids.forEach { scheduled.remove(it) }
        }

        fun restart() {
            real = host.newScheduler()
        }
    }

    private class Row(
        val name: String,
        /** The ids armed after the row, in first-scheduled order, each with the title of its last schedule. */
        val armed: List<Pair<String, String>>,
        val body: suspend Run.() -> Unit,
    )

    private val ROWS: List<Row> = listOf(
        Row("01 one alarm is armed", listOf("a" to "t")) { schedule("a") },
        Row("02 the same id replaces, it does not add", listOf("a" to "second")) { schedule("a", title = "first"); schedule("a", title = "second") },
        Row("03 a replacement may change the type and the time", listOf("a" to "t")) {
            schedule("a", type = "X", at = T); schedule("a", type = "Y", at = T + 60_000)
        },
        Row("04 two ids are two alarms in first-scheduled order", listOf("a" to "t", "b" to "t")) { schedule("a"); schedule("b") },
        Row("05 a replacement keeps the first-scheduled order", listOf("a" to "again", "b" to "t")) {
            schedule("a"); schedule("b"); schedule("a", title = "again")
        },
        Row("06 cancel removes one id and leaves the others", listOf("b" to "t")) { schedule("a"); schedule("b"); cancel("a") },
        Row("07 cancel of an id that is not armed is a no-op", listOf("a" to "t")) { schedule("a"); cancel("c") },
        Row("08 cancel twice is a no-op the second time", emptyList()) { schedule("a"); cancel("a"); cancel("a") },
        Row("09 cancelAll cancels exactly the ids passed", listOf("b" to "t")) { schedule("a"); schedule("b"); schedule("c"); cancelAll(listOf("a", "c")) },
        Row("10 cancelAll of an empty collection is a no-op", listOf("a" to "t")) { schedule("a"); cancelAll(emptyList()) },
        Row("11 cancelAll tolerates unknown and repeated ids", listOf("b" to "t")) {
            schedule("a"); schedule("b"); cancelAll(listOf("a", "c", "a"))
        },
        Row("12 cancelAll is not cancel everything", listOf("a" to "t", "b" to "t")) { schedule("a"); schedule("b"); cancelAll(emptyList()) },
        Row("13 an id can be scheduled again after a cancel", listOf("a" to "back")) { schedule("a"); cancel("a"); schedule("a", title = "back") },
        Row("14 a past time is armed, not refused", listOf("a" to "t")) { schedule("a", at = -1L) },
        Row("15 the far past is armed", listOf("a" to "t")) { schedule("a", at = Long.MIN_VALUE) },
        Row("16 the far future is armed", listOf("a" to "t")) { schedule("a", at = Long.MAX_VALUE) },
        Row("17 the epoch is armed", listOf("a" to "t")) { schedule("a", at = 0L) },
        Row("18 a replacement swaps a far past for a far future", listOf("a" to "t")) {
            schedule("a", at = Long.MIN_VALUE); schedule("a", at = Long.MAX_VALUE)
        },
        Row("19 the payload is carried", listOf("a" to "t")) { schedule("a", payload = mapOf("k" to "v", "é" to "💥", "" to "empty key")) },
        Row("20 a replacement drops the old payload", listOf("a" to "t")) {
            schedule("a", payload = mapOf("k" to "v")); schedule("a", payload = emptyMap())
        },
        Row("21 a restarted scheduler sees what an earlier one armed", listOf("b" to "t")) {
            schedule("a"); restart(); schedule("b"); restart(); cancel("a")
        },
        Row("22 a restarted scheduler replaces an alarm of the earlier one", listOf("a" to "later")) {
            schedule("a"); restart(); schedule("a", title = "later")
        },
        Row("23 ten replacements and one cancel leave nothing and wedge nothing", emptyList()) {
            repeat(10) { n: Int -> schedule("a", at = T + n) }; cancel("a")
        },
        Row("24 ten replacements then a schedule of another id", listOf("a" to "last", "b" to "t")) {
            repeat(9) { n: Int -> schedule("a", at = T + n) }; schedule("a", title = "last"); schedule("b")
        },
        Row("25 an unpaired surrogate id and a question mark id are two alarms", listOf("\uD800" to "lone")) {
            schedule("\uD800", title = "lone"); schedule("?", title = "mark"); cancel("?")
        },
        Row("26 ids differing only in case are two alarms", listOf("a" to "lower", "A" to "upper")) {
            schedule("a", title = "lower"); schedule("A", title = "upper")
        },
        Row("27 an id with spaces and URI characters round trips and cancels", listOf("b" to "t")) {
            schedule("a b/c?d#e"); schedule("b"); cancel("a b/c?d#e")
        },
        Row("28 empty title and body are accepted", listOf("a" to "")) { schedule("a", title = "", body = "") },
        Row("29 cancelAll in reverse order of scheduling empties the set", emptyList()) {
            schedule("a"); schedule("b"); schedule("c"); cancelAll(listOf("c", "b", "a"))
        },
        Row("30 a non-ASCII id round trips", listOf("é💥" to "t")) { schedule("é💥") },
        Row("31 a blank id never reaches a scheduler: construction rejects it", emptyList()) {
            for (blank: String in listOf("", " ", "\t", "\n ")) {
                assertFailsWith<IllegalArgumentException> { ScheduledAlarm(blank, "T", T, AlarmNotification("t", "b", "c")) }
            }
        },
    )

    suspend fun run(host: SchedulerFuzzHost) {
        assertTrue(ROWS.size >= 25, "the table must hold at least 25 rows")
        for (row: Row in ROWS) {
            host.reset()
            val run = Run(host)
            row.body(run)
            val system: Map<String, ArmedView> = host.armed(IDS)
            assertEquals(row.armed.map { it.first }.toSet(), system.keys, "row '${row.name}': the real system's armed ids")
            assertEquals(row.armed.map { it.first }, run.fake.armed.map { it.id }, "row '${row.name}': the double's armed ids, in first-scheduled order")
            for ((id: String, title: String) in row.armed) {
                val alarm: ScheduledAlarm = run.scheduled.getValue(id)
                val expected = ArmedView(alarm.type, title, alarm.notification.body, alarm.payload)
                assertEquals(expected, system[id], "row '${row.name}': the real system's content of '$id'")
                assertEquals(alarm, run.fake.armed.single { it.id == id }, "row '${row.name}': the double's content of '$id'")
            }
            run.cancelAll(IDS)
            assertEquals(emptyMap(), host.armed(IDS), "row '${row.name}': cancelAll of every id leaves nothing armed")
        }
    }
}
