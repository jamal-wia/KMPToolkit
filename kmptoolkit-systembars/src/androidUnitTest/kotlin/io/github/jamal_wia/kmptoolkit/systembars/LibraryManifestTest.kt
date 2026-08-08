package io.github.jamal_wia.kmptoolkit.systembars

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * This module declares **no** Android permission, asserted against a real package manager rather
 * than against a reading of the manifest.
 *
 * Styling the system bars needs none, and it would be easy to reach for one by accident:
 * `SYSTEM_ALERT_WINDOW` looks adjacent to "draw over the system UI", and fullscreen work has
 * historically dragged `WAKE_LOCK` along with it. Everything here goes through the window's own
 * insets controller, which needs nothing declared. A permission merged from a library manifest
 * appears in every consumer's app silently, and nothing else in the build would notice.
 */
@RunWith(AndroidJUnit4::class)
class LibraryManifestTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val declared: List<String>
        get() = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toList()
            ?: emptyList()

    @Test
    fun `the library manifest contributes no permission of its own`() {
        // The merged manifest under test is the *test* application's, and the test harness adds a
        // couple of its own — none of which reach a consumer, because the debug variant is never
        // published. Anything outside that set could only have come from this library.
        assertEquals(
            emptyList(),
            declared.filterNot(::isTestHarnessPermission),
            "kmptoolkit-systembars needs no permission; anything here leaked into every consumer",
        )
    }

    @Test
    fun `the library manifest does not contribute the overlay permission`() {
        assertFalse(
            android.Manifest.permission.SYSTEM_ALERT_WINDOW in declared,
            "styling the bars goes through the window's own insets controller, not an overlay",
        )
    }

    @Test
    fun `the library manifest does not contribute the wake lock permission`() {
        assertFalse(
            android.Manifest.permission.WAKE_LOCK in declared,
            "hiding the bars for a fullscreen surface says nothing about keeping the screen on",
        )
    }

    @Test
    fun `the library manifest does not contribute the status bar permissions`() {
        assertFalse(android.Manifest.permission.EXPAND_STATUS_BAR in declared)
        assertFalse("android.permission.STATUS_BAR" in declared)
    }

    private companion object {

        /**
         * Added by the instrumentation harness, not by any library: `REORDER_TASKS` comes with the
         * AndroidX test runner, and AGP synthesises a per-package receiver permission for every
         * test application.
         */
        fun isTestHarnessPermission(permission: String): Boolean =
            permission == android.Manifest.permission.REORDER_TASKS ||
                permission.endsWith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }
}
