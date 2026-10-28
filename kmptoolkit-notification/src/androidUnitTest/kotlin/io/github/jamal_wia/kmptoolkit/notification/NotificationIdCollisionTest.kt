package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.RecordingPermissionHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The string id is the identity of a notification — the replace key, the cancel key, and the key of
 * everything tapping it sends — so two different ids must stay two notifications even when their
 * `String.hashCode()`s are equal. `"Aa"` and `"BB"` are the classic pair: both hash to 2112.
 *
 * Run at the suite's default level and again below API 29 (see the subclasses), because
 * `Intent.setIdentifier`, the cleanest way to tell two `PendingIntent`s apart, only exists from 29.
 */
abstract class NotificationIdCollisionTest {

    protected val context: Context = ApplicationProvider.getApplicationContext()

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private val shownByTitle: Map<String, Notification>
        get() = shadowOf(manager).allNotifications.associateBy { posted: Notification ->
            posted.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        }

    private val permissions = RecordingPermissionHandler(defaultStatus = PermissionStatus.Granted)

    private fun notifier(): Notifier =
        createNotifier(context, permissions, NotificationConfig(minProgressInterval = Duration.ZERO))

    /** A notification titled with its own id, so the tray can be read back per id. */
    private fun notificationFor(
        id: String,
        actions: List<NotificationAction> = emptyList(),
        contentExtras: Map<String, String>? = null,
    ): LocalNotification = LocalNotification(
        title = id,
        body = "Body",
        channel = NotificationChannelSpec(id = "downloads", name = "Downloads"),
        actions = actions,
        contentExtras = contentExtras,
    )

    @Test
    fun `the colliding pair really does share a hash code`() {
        assertEquals(FIRST.hashCode(), SECOND.hashCode())
    }

    @Test
    fun `two ids with equal hash codes are two notifications`() = runTest {
        val notifier: Notifier = notifier()

        notifier.post(FIRST, notificationFor(FIRST))
        notifier.post(SECOND, notificationFor(SECOND))

        assertEquals(setOf(FIRST, SECOND), shownByTitle.keys)
    }

    @Test
    fun `cancelling one of two ids with equal hash codes leaves the other showing`() = runTest {
        val notifier: Notifier = notifier()
        notifier.post(FIRST, notificationFor(FIRST))
        notifier.post(SECOND, notificationFor(SECOND))

        notifier.cancel(FIRST)

        assertEquals(setOf(SECOND), shownByTitle.keys)
    }

    @Test
    fun `taps on two ids with equal hash codes carry their own extras`() = runTest {
        registerLauncherActivity()
        val notifier: Notifier = notifier()

        notifier.post(FIRST, notificationFor(FIRST, contentExtras = mapOf("which" to FIRST)))
        notifier.post(SECOND, notificationFor(SECOND, contentExtras = mapOf("which" to SECOND)))

        for (id: String in listOf(FIRST, SECOND)) {
            val tap: PendingIntent = requireNotNull(shownByTitle.getValue(id).contentIntent)
            assertEquals(id, shadowOf(tap).savedIntent.getStringExtra("which"), "tap of $id")
        }
    }

    @Test
    fun `the same button on two ids with equal hash codes reports its own notification`() = runTest {
        registerActionReceiver()
        val notifier: Notifier = notifier()
        val stop: List<NotificationAction> = listOf(NotificationAction("stop", "Stop"))

        notifier.post(FIRST, notificationFor(FIRST, actions = stop))
        notifier.post(SECOND, notificationFor(SECOND, actions = stop))

        for (id: String in listOf(FIRST, SECOND)) {
            val button: PendingIntent = shownByTitle.getValue(id).actions.single().actionIntent
            val intent: Intent = shadowOf(button).savedIntent
            assertEquals(id, NotificationActionIntent.notificationId(intent), "button of $id")
            assertEquals("stop", NotificationActionIntent.actionId(intent))
        }
    }

    @Test
    fun `a button still reaches the app's receiver`() = runTest {
        registerActionReceiver()

        notifier().post(FIRST, notificationFor(FIRST, actions = listOf(NotificationAction("stop", "Stop"))))

        val intent: Intent = shadowOf(shownByTitle.getValue(FIRST).actions.single().actionIntent).savedIntent
        val receivers = context.packageManager.queryBroadcastReceivers(intent, 0)
        assertTrue(
            receivers.any { it.activityInfo.name == RECEIVER_CLASS },
            "the button's intent no longer resolves to the receiver the docs tell an app to declare",
        )
    }

    /** This module has no launcher activity of its own; a real app resolves one. */
    private fun registerLauncherActivity() {
        val component = ComponentName(context.packageName, "com.example.TestLauncher")
        val packageManager = shadowOf(context.packageManager)
        packageManager.addActivityIfNotPresent(component)
        packageManager.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    /** The receiver `05-platform-notes.md` tells an app to declare: the action alone, nothing else. */
    private fun registerActionReceiver() {
        val component = ComponentName(context.packageName, RECEIVER_CLASS)
        val packageManager = shadowOf(context.packageManager)
        packageManager.addReceiverIfNotPresent(component)
        packageManager.addIntentFilterForReceiver(
            component,
            IntentFilter(NotificationActionIntent.defaultAction(context)),
        )
    }

    private companion object {
        const val FIRST: String = "Aa"
        const val SECOND: String = "BB"
        const val RECEIVER_CLASS: String = "com.example.NotificationActionReceiver"
    }
}

/** From API 29, where `Intent.setIdentifier` exists. Uses the suite's default level. */
@RunWith(AndroidJUnit4::class)
class NotificationIdCollisionCurrentApiTest : NotificationIdCollisionTest()

/** Below API 29, where `Intent.setIdentifier` does not exist yet. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class NotificationIdCollisionLegacyApiTest : NotificationIdCollisionTest() {

    @Test
    fun `with no receiver declared a button stays an implicit broadcast with nothing added`() = runTest {
        // Nothing to address it to: the broadcast must still match an action-only filter — a receiver
        // registered at runtime, say — so it carries neither a component nor an extra category.
        createNotifier(context, RecordingPermissionHandler(defaultStatus = PermissionStatus.Granted)).post(
            "Aa",
            LocalNotification(
                title = "Aa",
                body = "Body",
                channel = NotificationChannelSpec(id = "downloads", name = "Downloads"),
                actions = listOf(NotificationAction("stop", "Stop")),
            ),
        )

        val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
        val intent: Intent = shadowOf(shadowOf(manager).allNotifications.single().actions.single().actionIntent).savedIntent
        assertEquals(null, intent.component)
        assertEquals(null, intent.categories)
        assertEquals(NotificationActionIntent.defaultAction(context), intent.action)
    }
}
