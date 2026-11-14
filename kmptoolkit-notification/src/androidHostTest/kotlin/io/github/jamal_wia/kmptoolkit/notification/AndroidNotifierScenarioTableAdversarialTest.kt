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
 * Round 23: the hand-written scenario table ([NotifierScenarioTable]) through the real Android notifier and
 * `RecordingNotifier`, at the two levels that have a permission gate (33 and 35), so all 31 rows run on both.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33, 35])
class AndroidNotifierScenarioTableAdversarialTest {

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
            shadowOf(manager).setNotificationsEnabled(true)
            permissions.status = PermissionStatus.Granted
        }

        override fun newNotifier(clock: TestTimeSource): Notifier = AndroidNotifier(
            context = context,
            permissions = permissions,
            renderer = NotificationRenderer(context, "test.table.ACTION"),
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
    fun `every row of the scenario table gives the documented typed answer and the same tray in the real notifier and in the double`() {
        runBlocking { withTimeout(120_000) { NotifierScenarioTable.run(host) } }
    }
}
