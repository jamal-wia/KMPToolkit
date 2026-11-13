package io.github.jamal_wia.kmptoolkit.notification

import io.github.jamal_wia.kmptoolkit.notification.testing.RecordingNotifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runTest

/**
 * Round 24 (docs-only newcomer, Q4): every default the notification docs print or promise, one row each,
 * "documented" typed in from docs/kmptoolkit-notification 01 to 04 and 06 and "actual" read from a value built
 * without arguments. A row that differs names itself. Runs on the Android host and on iOS.
 */
class NotificationDocumentedDefaultsTableAdversarialTest {

    private class Row(val where: String, val documented: Any?, val actual: Any?)

    private val channel = NotificationChannelSpec("id", "name")

    @Test
    fun `documented defaults equal the actual defaults - at least 21 rows`() = runTest {
        val bare = LocalNotification("t", "b", channel)
        val config = NotificationConfig()
        val spec = channel
        val options = NotificationOptions()
        val recorder = RecordingNotifier()
        recorder.post("x", bare)
        val rows: List<Row> = listOf(
            Row("04 NotificationConfig.actionBroadcastAction", null, config.actionBroadcastAction),
            Row("04 NotificationConfig.progressBucketPercent (comment: 10)", 10, config.progressBucketPercent),
            Row("03 progressBucketPercent default 10 = DEFAULT_PROGRESS_BUCKET_PERCENT", 10, NotificationConfig.DEFAULT_PROGRESS_BUCKET_PERCENT),
            Row("04 NotificationConfig.minProgressInterval (comment: 500 ms)", 500.milliseconds, config.minProgressInterval),
            Row("03 minProgressInterval default 500 ms = DEFAULT_MIN_PROGRESS_INTERVAL", 500.milliseconds, NotificationConfig.DEFAULT_MIN_PROGRESS_INTERVAL),
            Row("04 LocalNotification.icon", NotificationIcon.Default, bare.icon),
            Row("04 LocalNotification.progress", null, bare.progress),
            Row("04 LocalNotification.ongoing", false, bare.ongoing),
            Row("04 LocalNotification.autoCancel", true, bare.autoCancel),
            Row("04 LocalNotification.actions", emptyList<NotificationAction>(), bare.actions),
            Row("04 LocalNotification.iosCategoryId", null, bare.iosCategoryId),
            Row("03 contentExtras = null (the default) means nothing happens on tap", null, bare.contentExtras),
            Row("04 NotificationChannelSpec.description", "", spec.description),
            Row("04 NotificationChannelSpec.importance", NotificationImportance.Default, spec.importance),
            Row("04 NotificationChannelSpec.sound", NotificationSound.Default, spec.sound),
            Row("04 NotificationOptions.alertOnce", true, options.alertOnce),
            Row("04 NotificationOptions.dismissAction", null, options.dismissAction),
            Row("04 NotificationOptions.mediaStyle", false, options.mediaStyle),
            Row("04 NotificationOptions.actionIcons", emptyMap<String, NotificationIcon>(), options.actionIcons),
            Row("04 the two-argument form is the same as NotificationOptions.DEFAULT (DEFAULT equals NotificationOptions())", NotificationOptions(), NotificationOptions.DEFAULT),
            Row("06 RecordingNotifier result default Posted", NotificationResult.Posted, RecordingNotifier().result),
            Row("06 two-argument post records NotificationOptions.DEFAULT", NotificationOptions.DEFAULT, recorder.posted.single().options),
            Row("04 NotificationProgress.Determinate keeps the percent as given (clamping is the notifier's, not the type's)", 150, NotificationProgress.Determinate(150).percent),
            Row("04 NotificationResult.Failed.cause may be null", null, NotificationResult.Failed(null).cause),
            Row("04 noOpNotifier is stateless and shared: two calls give the same instance", true, noOpNotifier() === noOpNotifier()),
        )
        assertTrue(rows.size >= 21, "the table has ${rows.size} rows")
        for (row: Row in rows) assertEquals(row.documented, row.actual, "documented default differs from the actual one: ${row.where}")
    }
}
