package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.notification.testing.RecordingNotifier
import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionController
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Round 18 (fake fidelity): RecordingNotifier's doc says its recordings are snapshots taken when `post` is called,
 * "as the real notifiers, which read those collections once at the start of a post, would show it". Here the
 * caller edits its button list, extras map and action-icon map while the real post is parked in the permission
 * check (API 33) and the double already holds the same call: the tray and the double must both show the
 * collections as they were at the call.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AndroidNotifierFidelitySnapshotInFlightAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    private class Gated : PermissionController {
        val gate: CompletableDeferred<PermissionStatus> = CompletableDeferred()
        override suspend fun check(permission: Permission): PermissionStatus = gate.await()
        override suspend fun request(permission: Permission): PermissionStatus = gate.await()
        override suspend fun request(permissions: Collection<Permission>): Map<Permission, PermissionStatus> =
            permissions.associateWith { gate.await() }

        override fun observe(permission: Permission): Flow<PermissionStatus> = flowOf(PermissionStatus.Granted)
        override fun openSettings(permission: Permission): Boolean = false
    }

    @Before
    fun registerLauncher() {
        val component = ComponentName(context.packageName, "com.example.TestLauncher")
        shadowOf(context.packageManager).addActivityIfNotPresent(component)
        shadowOf(context.packageManager).addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    @Test
    fun `the tray and the double both show the collections as they were at the call after the caller edited them mid post`() {
        runBlocking {
            withTimeout(60_000) {
                val gate = Gated()
                val real: Notifier = createNotifier(context, gate, NotificationConfig(minProgressInterval = Duration.ZERO))
                val fake = RecordingNotifier()
                val actions: MutableList<NotificationAction> = mutableListOf(NotificationAction("p", "Pause"), NotificationAction("c", "Cancel"))
                val extras: MutableMap<String, String> = mutableMapOf("s" to "one")
                val icons: MutableMap<String, NotificationIcon> = mutableMapOf("p" to NotificationIcon.AndroidDrawable(android.R.drawable.ic_media_pause))
                val notification = LocalNotification(
                    title = "T",
                    body = "B",
                    channel = NotificationChannelSpec("c0", "C0"),
                    actions = actions,
                    contentExtras = extras,
                )
                val options = NotificationOptions(actionIcons = icons)

                val job = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { real.post("a", notification, options) }
                fake.post("a", notification, options)
                // The real post is parked in the permission check; the caller reuses its collections for the next frame.
                actions.clear()
                actions += NotificationAction("x", "Other")
                extras.clear()
                extras["s"] = "two"
                icons.clear()
                gate.gate.complete(PermissionStatus.Granted)
                job.join()

                val shown: Notification = assertNotNull(shadowOf(manager).getNotification("a", 1))
                assertEquals(listOf("Pause", "Cancel"), shown.actions.map { it.title.toString() }, "the tray shows the buttons of the call")
                assertEquals(
                    android.R.drawable.ic_media_pause,
                    shown.actions[0].getIcon()?.resId,
                    "the first button keeps the icon its action id had in the map at the call",
                )
                val tap: Intent = shadowOf(requireNotNull(shown.contentIntent)).savedIntent
                assertEquals("one", tap.getStringExtra("s"), "the tap target carries the extras of the call")
                val recorded: PostedNotificationView = fake.posted.single().let { PostedNotificationView(it.notification.actions.map { a -> a.label }, it.notification.contentExtras, it.options.actionIcons.size) }
                assertEquals(PostedNotificationView(listOf("Pause", "Cancel"), mapOf("s" to "one"), 1), recorded, "the double holds the call as it was")
            }
        }
    }

    private data class PostedNotificationView(val labels: List<String>, val extras: Map<String, String>?, val icons: Int)
}
