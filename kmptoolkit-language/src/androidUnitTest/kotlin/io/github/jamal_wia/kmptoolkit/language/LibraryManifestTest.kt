package io.github.jamal_wia.kmptoolkit.language

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * This module declares **no** Android permission, asserted against a real package manager rather
 * than against a reading of the manifest.
 *
 * Reading and re-pinning the process locale needs none, and there is a permission that looks like it
 * ought to be involved: `CHANGE_CONFIGURATION`. That one is for changing the *device* configuration
 * and is signature-level anyway; everything here stays inside the app's own `Resources` and
 * `LocaleList`. A permission merged from a library manifest appears in every consumer's app
 * silently, and nothing else in the build would notice.
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
            "kmptoolkit-language needs no permission; anything here leaked into every consumer",
        )
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
