package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import kotlin.test.Test
import kotlin.time.TestTimeSource
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The Android half of the cross-platform differential: the same seeded operation sequences, with the
 * same reference model of the posting contract ([NotifierProtocolFuzz]), that the iOS notifier is run
 * through in `IosNotifierProtocolFuzzDifferentialAdversarialTest`. Run over a lean API matrix (24 has
 * no channels, 29 and 32 no runtime permission before 33, 33 the first with it, 35 the newest the build
 * can run) because earlier rounds found crashes that sdk=35-only tests hid.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 29, 33, 35])
class AndroidNotifierProtocolFuzzDifferentialAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = MutablePermissions()

    @Before
    fun registerLauncher() {
        val component = ComponentName(context.packageName, "com.example.TestLauncher")
        shadowOf(context.packageManager).addActivityIfNotPresent(component)
        shadowOf(context.packageManager).addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    private val host = object : NotifierFuzzHost {
        override val permissionGate: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        override val hasAppSwitch: Boolean = true

        override fun reset() {
            manager.cancelAll()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.notificationChannels.toList().forEach { manager.deleteNotificationChannel(it.id) }
            }
            shadowOf(manager).setNotificationsEnabled(true)
            permissions.status = PermissionStatus.Granted
        }

        override fun newNotifier(clock: TestTimeSource): Notifier = AndroidNotifier(
            context = context,
            permissions = permissions,
            renderer = NotificationRenderer(context, "test.differential.ACTION"),
            coalescer = NotifierProtocolFuzz.newCoalescer(clock),
        )

        override fun setPermission(status: PermissionStatus) {
            permissions.status = status
        }

        override fun setAppSwitch(enabled: Boolean) {
            shadowOf(manager).setNotificationsEnabled(enabled)
        }

        override fun shown(ids: List<String>): Map<String, Pair<String, String>> =
            ids.mapNotNull { id: String ->
                shadowOf(manager).getNotification(id, 1)?.let { n: Notification ->
                    id to (
                        n.extras.getString(Notification.EXTRA_TITLE).orEmpty() to
                            n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                        )
                }
            }.toMap()
    }

    @Test
    fun `seeded sequences give the answers and the tray of the shared model on every api level`() {
        runBlocking { withTimeout(120_000) { NotifierProtocolFuzz.run(host, seeds = 40, steps = 50) } }
    }
}
