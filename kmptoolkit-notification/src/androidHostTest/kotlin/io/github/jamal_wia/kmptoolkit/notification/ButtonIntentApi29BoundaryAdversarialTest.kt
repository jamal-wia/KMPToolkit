package io.github.jamal_wia.kmptoolkit.notification

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Mutation survivor hunt (round 13): the API 29 border of docs 05-platform-notes ("Every PendingIntent carries its
 * own key"): from API 29 the key is `Intent.setIdentifier` and the button broadcast stays implicit even when the app
 * declares exactly one receiver; below 29 the broadcast is addressed to that receiver and the key is a category.
 */
abstract class ButtonIntentApi29BoundaryBase {

    protected val context: Context = ApplicationProvider.getApplicationContext()

    protected fun buttonIntent(): Intent {
        val component = ComponentName(context.packageName, "com.example.NotificationActionReceiver")
        val pm = shadowOf(context.packageManager)
        pm.addReceiverIfNotPresent(component)
        pm.addIntentFilterForReceiver(component, IntentFilter(NotificationActionIntent.defaultAction(context)))
        var intent: Intent? = null
        runTest {
            createNotifier(context, FakePermissionController(defaultStatus = PermissionStatus.Granted)).post(
                "btn",
                LocalNotification(
                    title = "T",
                    body = "B",
                    channel = NotificationChannelSpec(id = "downloads", name = "Downloads"),
                    actions = listOf(NotificationAction("stop", "Stop")),
                ),
            )
            val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
            intent = shadowOf(shadowOf(manager).allNotifications.single().actions.single().actionIntent).savedIntent
        }
        return assertNotNull(intent)
    }
}

/** API 29 is the first level with `setIdentifier`: the key is the identifier, the broadcast stays implicit. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29])
class ButtonIntentApi29IdentifierTest : ButtonIntentApi29BoundaryBase() {

    @Test
    fun `at API 29 the button carries an identifier and stays implicit with one receiver declared`() {
        val intent: Intent = buttonIntent()
        assertTrue(!intent.identifier.isNullOrBlank(), "no identifier key on the button intent")
        assertNull(intent.component, "an API 29 broadcast must stay implicit")
        assertNull(intent.categories, "an API 29 broadcast must not carry the key as a category")
    }
}

/** API 28 has no `setIdentifier`: the broadcast is addressed to the sole receiver and the key is a category. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class ButtonIntentApi28CategoryTest : ButtonIntentApi29BoundaryBase() {

    @Test
    fun `at API 28 the button is addressed to the sole receiver and carries the key as a category`() {
        val intent: Intent = buttonIntent()
        assertEquals("com.example.NotificationActionReceiver", intent.component?.className)
        assertEquals(1, intent.categories?.size, "the key must ride as exactly one category")
    }
}
