package io.github.jamal_wia.kmptoolkit.permission

import android.app.AppOpsManager
import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Two special-access reads no test reached (mutation sweep, round 12): usage-stats access is granted only when the app-ops
 * mode is "allowed" (the default mode is not a grant), and a device with no notification service cannot have been granted
 * full-screen notifications.
 */
@RunWith(AndroidJUnit4::class)
class SpecialAccessReadsRound12Test {

    private val application: Application = ApplicationProvider.getApplicationContext()

    private fun controller(context: Context): AndroidPermissionController =
        testController(context = context, storage = InMemoryKeyValueStorage(), sdkInt = Build.VERSION.SDK_INT)

    @Test
    @Config(sdk = [34])
    fun `usage stats access is not granted while the app-ops mode is not allowed`() = runTest {
        val appOps: AppOpsManager = application.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(appOps).setMode(OP_GET_USAGE_STATS, Process.myUid(), application.packageName, AppOpsManager.MODE_IGNORED)

        assertNotEquals(PermissionStatus.Granted, controller(application).check(AndroidPermission.UsageStatsAccess))
    }

    @Test
    @Config(sdk = [34])
    fun `usage stats access is granted once the app-ops mode is allowed`() = runTest {
        val appOps: AppOpsManager = application.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(appOps).setMode(OP_GET_USAGE_STATS, Process.myUid(), application.packageName, AppOpsManager.MODE_ALLOWED)

        assertEquals(PermissionStatus.Granted, controller(application).check(AndroidPermission.UsageStatsAccess))
    }

    @Test
    @Config(sdk = [34])
    fun `full-screen notifications are not granted on a device with no notification service`() = runTest {
        val noService: Context = object : ContextWrapper(application) {
            override fun getSystemService(name: String): Any? =
                if (name == Context.NOTIFICATION_SERVICE) null else super.getSystemService(name)
        }

        assertNotEquals(PermissionStatus.Granted, controller(noService).check(AndroidPermission.FullScreenNotifications))
    }

    private companion object {
        /** `AppOpsManager.OP_GET_USAGE_STATS`, a hidden constant that has been 43 since API 21. */
        const val OP_GET_USAGE_STATS: Int = 43
    }
}
