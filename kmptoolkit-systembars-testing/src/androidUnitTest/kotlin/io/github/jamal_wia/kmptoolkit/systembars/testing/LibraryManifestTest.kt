package io.github.jamal_wia.kmptoolkit.systembars.testing

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
 * Test fixtures need none by definition, but the rule is not waived for them: this artifact lands on
 * a consumer's `testImplementation`, where a merged permission would quietly change what their test
 * application is allowed to do and make a test pass for a reason that will not hold in production.
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
            "kmptoolkit-systembars-testing needs no permission; anything here leaked into every consumer",
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
