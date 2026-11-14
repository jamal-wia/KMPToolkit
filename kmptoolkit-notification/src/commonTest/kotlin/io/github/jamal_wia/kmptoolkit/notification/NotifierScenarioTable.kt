package io.github.jamal_wia.kmptoolkit.notification

import io.github.jamal_wia.kmptoolkit.notification.testing.RecordingNotifier
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TestTimeSource

/**
 * Round 23 (fakes versus real, a written table): the typed answer of every step is written here, by hand, from
 * the notification guide and API reference, never read off an implementation. Each row runs through a real
 * notifier over a [NotifierFuzzHost] and through [RecordingNotifier] (told the answer the table expects). The real
 * notifier must answer exactly what the table says, and after every row the real tray, the double's `showing` and
 * the row's own expected tray must be the same. The same rows run on the Android host and on the iOS simulator.
 * Rows that need a permission gate or an app-level switch are skipped where the platform has none, as the
 * documented ordering says ("permission, app-level toggle, channel, icon, coalescing").
 */
internal object NotifierScenarioTable {

    private val CHANNEL = NotificationChannelSpec("table", "Table")
    private val ALL_IDS: List<String> = listOf("a", "b", "z", "\uD800", "?", "é💥")

    private class Run(val host: NotifierFuzzHost) {
        val clock = TestTimeSource()
        var real: Notifier = host.newNotifier(clock)
        val fake = RecordingNotifier()
        val touched: MutableSet<String> = linkedSetOf()

        fun frame(title: String, progress: NotificationProgress?): LocalNotification =
            LocalNotification(title = title, body = "body $title", channel = CHANNEL, progress = progress)

        suspend fun post(
            id: String,
            title: String,
            progress: NotificationProgress? = null,
            expect: NotificationResult = NotificationResult.Posted,
        ) {
            touched += id
            val frame: LocalNotification = frame(title, progress)
            assertEquals(expect, real.post(id, frame), "the real notifier's answer for post($id, $title, $progress)")
            fake.result = expect
            assertEquals(expect, fake.post(id, frame), "the double answers the scripted result")
        }

        fun cancel(id: String) {
            real.cancel(id)
            fake.cancel(id)
        }

        fun cancelAll() {
            real.cancelAll()
            fake.cancelAll()
        }

        fun tick(ms: Long) {
            clock += ms.milliseconds
        }

        fun restart() {
            real = host.newNotifier(clock)
        }
    }

    private class Row(
        val name: String,
        val needsGate: Boolean = false,
        val needsSwitch: Boolean = false,
        /** The tray after the row: id to title. */
        val showing: Map<String, String>,
        val body: suspend Run.() -> Unit,
    )

    private fun d(percent: Int): NotificationProgress = NotificationProgress.Determinate(percent)

    private val ROWS: List<Row> = listOf(
        Row("01 a first post is Posted and shown", showing = mapOf("a" to "t1")) { post("a", "t1") },
        Row("02 a repost under the same id replaces it", showing = mapOf("a" to "t2")) { post("a", "t1"); post("a", "t2") },
        Row("03 two ids are two notifications", showing = mapOf("a" to "t1", "b" to "t2")) { post("a", "t1"); post("b", "t2") },
        Row("04 cancel removes a shown notification", showing = emptyMap()) { post("a", "t1"); cancel("a") },
        Row("05 cancel of an id that is not showing is a no-op", showing = mapOf("a" to "t1")) { post("a", "t1"); cancel("z") },
        Row("06 cancelAll removes everything", showing = emptyMap()) { post("a", "t1"); post("b", "t2"); cancelAll() },
        Row("07 the first frame after a cancelAll posts", showing = mapOf("a" to "run")) {
            post("a", "run", d(20)); cancelAll(); post("a", "run", d(21))
        },
        Row("08 a same bucket same content frame is Coalesced and the tray is unchanged", showing = mapOf("a" to "run")) {
            post("a", "run", d(20)); tick(600); post("a", "run", d(21), NotificationResult.Coalesced)
        },
        Row("09 crossing a bucket after the interval posts", showing = mapOf("a" to "run")) {
            post("a", "run", d(20)); tick(600); post("a", "run", d(30))
        },
        Row("10 a changed title inside the bucket posts", showing = mapOf("a" to "page 2")) {
            post("a", "page 1", d(20)); tick(600); post("a", "page 2", d(21))
        },
        Row("11 a completed 100 percent frame is never suppressed, twice in a row", showing = mapOf("a" to "run")) {
            post("a", "run", d(95)); tick(600); post("a", "run", d(100)); post("a", "run", d(100))
        },
        Row("12 a terminal frame without progress resets the run", showing = mapOf("a" to "run")) {
            post("a", "run", d(20)); post("a", "run", null); post("a", "run", d(21))
        },
        Row("13 indeterminate frames are never suppressed", showing = mapOf("a" to "run")) {
            repeat(3) { post("a", "run", NotificationProgress.Indeterminate) }
        },
        Row("14 a frame inside the rate interval is Coalesced even across a bucket, and at exactly the interval it posts", showing = mapOf("a" to "run")) {
            post("a", "run", d(20)); tick(100); post("a", "run", d(50), NotificationResult.Coalesced)
            tick(400); post("a", "run", d(50))
        },
        Row("15 one millisecond short of the interval is still Coalesced", showing = mapOf("a" to "run")) {
            post("a", "run", d(20)); tick(499); post("a", "run", d(50), NotificationResult.Coalesced)
        },
        Row("16 a restarted notifier has no baseline, so the same frame posts", showing = mapOf("a" to "run")) {
            post("a", "run", d(20)); restart(); post("a", "run", d(21))
        },
        Row("17 a cancel forgets the baseline of that id only", showing = mapOf("a" to "run", "b" to "run")) {
            post("a", "run", d(20)); post("b", "run", d(20)); cancel("a"); tick(600)
            post("a", "run", d(21)); post("b", "run", d(21), NotificationResult.Coalesced)
        },
        Row("18 Denied is PermissionDenied and shows nothing", needsGate = true, showing = emptyMap()) {
            host.setPermission(PermissionStatus.Denied()); post("a", "t1", expect = NotificationResult.PermissionDenied)
        },
        Row("19 PermanentlyDenied is PermissionDenied", needsGate = true, showing = emptyMap()) {
            host.setPermission(PermissionStatus.PermanentlyDenied); post("a", "t1", expect = NotificationResult.PermissionDenied)
        },
        Row("20 Restricted is PermissionDenied", needsGate = true, showing = emptyMap()) {
            host.setPermission(PermissionStatus.Restricted); post("a", "t1", expect = NotificationResult.PermissionDenied)
        },
        Row("21 Limited still posts", needsGate = true, showing = mapOf("a" to "t1")) {
            host.setPermission(PermissionStatus.Limited); post("a", "t1")
        },
        Row("22 a refused frame leaves the frame that was showing", needsGate = true, showing = mapOf("a" to "t1")) {
            post("a", "t1"); host.setPermission(PermissionStatus.Denied()); post("a", "t2", expect = NotificationResult.PermissionDenied)
        },
        Row("23 a would-be Coalesced frame still reports the denied permission", needsGate = true, showing = mapOf("a" to "run")) {
            post("a", "run", d(20)); host.setPermission(PermissionStatus.Denied()); tick(600)
            post("a", "run", d(21), NotificationResult.PermissionDenied)
        },
        Row("24 the same frame posts once the permission is granted again", needsGate = true, showing = mapOf("a" to "run")) {
            host.setPermission(PermissionStatus.Denied()); post("a", "run", d(20), NotificationResult.PermissionDenied)
            host.setPermission(PermissionStatus.Granted); post("a", "run", d(20))
        },
        Row("25 the app switch off is NotificationsDisabled", needsSwitch = true, showing = emptyMap()) {
            host.setAppSwitch(false); post("a", "t1", expect = NotificationResult.NotificationsDisabled)
        },
        Row("26 the permission is reported before the app switch", needsGate = true, needsSwitch = true, showing = emptyMap()) {
            host.setAppSwitch(false); host.setPermission(PermissionStatus.Denied())
            post("a", "t1", expect = NotificationResult.PermissionDenied)
        },
        Row("27 switching the app switch back on posts", needsSwitch = true, showing = mapOf("a" to "t1")) {
            host.setAppSwitch(false); post("a", "t1", expect = NotificationResult.NotificationsDisabled)
            host.setAppSwitch(true); post("a", "t1")
        },
        Row("28 progress state is per id", showing = mapOf("a" to "run", "b" to "run")) {
            post("a", "run", d(20)); post("b", "run", d(20)); tick(600)
            post("a", "run", d(21), NotificationResult.Coalesced); post("b", "run", d(30))
        },
        Row("29 an unpaired surrogate id and a question mark id are two notifications", showing = mapOf("\uD800" to "lone", "?" to "mark")) {
            post("\uD800", "lone"); post("?", "mark")
        },
        Row("30 a non-ASCII id round trips and cancels", showing = mapOf("a" to "keep")) {
            post("é💥", "gone"); post("a", "keep"); cancel("é💥")
        },
        Row("31 a blank id is rejected alike by the real notifier and the double", showing = emptyMap()) {
            NotifierFidelityDriver.blankIdRejectedAlike(real)
        },
    )

    suspend fun run(host: NotifierFuzzHost) {
        assertTrue(ROWS.size >= 25, "the table must hold at least 25 rows")
        var executed = 0
        for (row: Row in ROWS) {
            if (row.needsGate && !host.permissionGate) continue
            if (row.needsSwitch && !host.hasAppSwitch) continue
            host.reset()
            val run = Run(host)
            row.body(run)
            val system: Map<String, String> = host.shown(ALL_IDS).mapValues { (_, text: Pair<String, String>) -> text.first }
            val double: Map<String, String> = run.fake.showing.mapValues { (_, n: LocalNotification) -> n.title }
            assertEquals(row.showing, system, "row '${row.name}': the real tray")
            assertEquals(row.showing, double, "row '${row.name}': the double's showing")
            run.cancelAll()
            assertEquals(emptyMap(), host.shown(ALL_IDS), "row '${row.name}': cancelAll leaves the real tray empty")
            executed++
        }
        assertTrue(executed >= 25, "at least 25 rows ran on this platform, ran $executed")
    }
}
