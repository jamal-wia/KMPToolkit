package io.github.jamal_wia.kmptoolkit.permission

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
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
    fun `isGranted never throws for any entry`() {
        // Real device state (canDrawOverlays, usage-stats app-ops, …) is not something Robolectric
        // fakes meaningfully, so this is a smoke test that every branch resolves without crashing —
        // the platform-specific results themselves are exercised on a real device.
        for (permission in SpecialPermission.entries) {
            handler.isGranted(permission)
        }
    }
}
