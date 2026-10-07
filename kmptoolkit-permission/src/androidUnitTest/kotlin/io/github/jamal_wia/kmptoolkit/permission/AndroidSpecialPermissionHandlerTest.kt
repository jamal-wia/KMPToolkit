package io.github.jamal_wia.kmptoolkit.permission

import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
class AndroidSpecialPermissionHandlerTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val handler: SpecialPermissionHandler = createSpecialPermissionHandler(context)

    @Test
    @Config(sdk = [30])
    fun `EXACT_ALARM is always granted below API 31`() {
        assertTrue(handler.isGranted(SpecialPermission.EXACT_ALARM))
    }

    @Test
    @Config(sdk = [30])
    fun `requesting EXACT_ALARM below API 31 opens nothing — there is no such screen`() {
        assertFalse(handler.requestViaSettings(SpecialPermission.EXACT_ALARM))
    }

    @Test
    @Config(sdk = [29])
    fun `ALL_FILES_ACCESS is always granted below API 30`() {
        assertTrue(handler.isGranted(SpecialPermission.ALL_FILES_ACCESS))
    }

    @Test
    @Config(sdk = [29])
    fun `requesting ALL_FILES_ACCESS below API 30 opens nothing — there is no such screen`() {
        assertFalse(handler.requestViaSettings(SpecialPermission.ALL_FILES_ACCESS))
    }

    @Test
    @Config(sdk = [30])
    fun `MEDIA_MANAGEMENT is always granted below API 31`() {
        assertTrue(handler.isGranted(SpecialPermission.MEDIA_MANAGEMENT))
    }

    @Test
    @Config(sdk = [33])
    fun `FULL_SCREEN_INTENT is always granted below API 34`() {
        assertTrue(handler.isGranted(SpecialPermission.FULL_SCREEN_INTENT))
    }

    @Test
    @Config(sdk = [29])
    fun `KEEP_PERMISSIONS_WHEN_UNUSED is always granted below API 30`() {
        assertTrue(handler.isGranted(SpecialPermission.KEEP_PERMISSIONS_WHEN_UNUSED))
    }

    @Test
    @Config(sdk = [25])
    fun `INSTALL_UNKNOWN_APPS below API 26 follows the device-wide unknown-sources switch`() {
        @Suppress("DEPRECATION")
        val key: String = Settings.Secure.INSTALL_NON_MARKET_APPS
        Settings.Secure.putInt(context.contentResolver, key, 0)
        assertFalse(handler.isGranted(SpecialPermission.INSTALL_UNKNOWN_APPS))

        Settings.Secure.putInt(context.contentResolver, key, 1)
        assertTrue(handler.isGranted(SpecialPermission.INSTALL_UNKNOWN_APPS))
    }

    @Test
    @Config(sdk = [26])
    fun `INSTALL_UNKNOWN_APPS from API 26 is this app's own grant`() {
        shadowOf(context.packageManager).setCanRequestPackageInstalls(false)
        assertFalse(handler.isGranted(SpecialPermission.INSTALL_UNKNOWN_APPS))

        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        assertTrue(handler.isGranted(SpecialPermission.INSTALL_UNKNOWN_APPS))
    }

    @Test
    fun `isGranted never throws for any entry`() {
        // Real device state (canDrawOverlays, usage-stats app-ops, …) is not something Robolectric
        // fakes meaningfully, so this is a smoke test that every branch resolves without crashing —
        // the platform-specific results themselves are exercised on a real device.
        for (permission in SpecialPermission.entries) {
            handler.isGranted(permission)
        }
    }
}
