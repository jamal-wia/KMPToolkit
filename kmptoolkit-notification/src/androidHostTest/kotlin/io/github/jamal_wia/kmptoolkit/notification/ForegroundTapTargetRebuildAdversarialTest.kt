package io.github.jamal_wia.kmptoolkit.notification

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Mutation survivor hunt (round 13). Contract of `buildForegroundNotification` (docs 02 and the KDoc): nothing
 * posts the notification through a notifier, so there is no accept step that writes the tap target afterwards;
 * the built notification must open the app with ITS OWN `contentExtras` the moment it is posted. A second build
 * for the same foreground id with other extras (the service refreshes its notification) must therefore carry the
 * second extras, not the ones of the first build.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 34])
class ForegroundTapTargetRebuildAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val foreground = ForegroundNotificationId("svc", 1001)

    private fun registerLauncher() {
        val component = ComponentName(context.packageName, "com.example.TestLauncher")
        val pm = shadowOf(context.packageManager)
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    private fun extrasOf(pending: PendingIntent?): Map<String, String> {
        assertNotNull(pending, "the built notification has no tap target")
        val intent: Intent = shadowOf(pending).savedIntent
        return intent.extras?.keySet().orEmpty().associateWith { key: String -> intent.extras?.getString(key).orEmpty() }
    }

    private fun notification(extras: Map<String, String>?): LocalNotification = LocalNotification(
        title = "Playing",
        body = "Track",
        channel = NotificationChannelSpec(id = "media", name = "Media"),
        contentExtras = extras,
    )

    @Test
    fun `a rebuilt foreground notification opens the app with its own extras`() {
        registerLauncher()
        val first: Map<String, String> = mapOf("screen" to "player", "track" to "1")
        val second: Map<String, String> = mapOf("screen" to "player", "track" to "2")
        val built1 = buildForegroundNotification(context, foreground, notification(first))
        assertEquals(first, extrasOf(built1.contentIntent))
        val built2 = buildForegroundNotification(context, foreground, notification(second))
        assertEquals(second, extrasOf(built2.contentIntent), "the second build kept the extras of the first")
        val third: Map<String, String> = mapOf("screen" to "queue", "track" to "3")
        val built3 = buildForegroundNotification(context, foreground, notification(third))
        assertEquals(third, extrasOf(built3.contentIntent))
    }
}
