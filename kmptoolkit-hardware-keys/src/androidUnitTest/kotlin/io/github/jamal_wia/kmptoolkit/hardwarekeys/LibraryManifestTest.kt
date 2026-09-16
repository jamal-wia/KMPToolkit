package io.github.jamal_wia.kmptoolkit.hardwarekeys

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * This module declares **no** Android permission, asserted against a real package manager.
 *
 * Intercepting keys inside the app's own windows needs none; the adjacent-sounding ones
 * (`SYSTEM_ALERT_WINDOW`, accessibility services) are what an app reaches for to intercept keys
 * *outside* its windows, which this module deliberately does not do. A permission merged from a
 * library manifest appears in every consumer's app silently.
 */
@RunWith(AndroidJUnit4::class)
class LibraryManifestTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the library manifest contributes no permission of its own`() {
        val declared: List<String> = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toList()
            ?: emptyList()

        // The test harness adds REORDER_TASKS and a per-package receiver permission; neither reaches
        // a consumer, because the debug variant is never published.
        assertEquals(
            emptyList(),
            declared.filterNot {
                it == android.Manifest.permission.REORDER_TASKS ||
                    it.endsWith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
            },
            "kmptoolkit-hardware-keys needs no permission; anything here leaked into every consumer",
        )
    }
}
