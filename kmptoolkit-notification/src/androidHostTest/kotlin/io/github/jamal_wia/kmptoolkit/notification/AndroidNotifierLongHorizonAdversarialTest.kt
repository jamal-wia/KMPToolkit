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
 * Round 24: the 30-day virtual-time model of [NotifierLongHorizon] through the real Android notifier at three
 * API levels (24 without channels, 33 the first with the runtime permission, 35 the newest the build can run).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 33, 35])
class AndroidNotifierLongHorizonAdversarialTest {

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
    fun `seeded 30 day sequences give the answers and the tray of the documented model`() {
        runBlocking {
            withTimeout(120_000) {
                val tally: NotifierLongHorizon.Tally = NotifierLongHorizon.run(host, seeds = 6, steps = 400)
                NotifierLongHorizon.assertExercised(tally, host)
            }
        }
    }
}
