package io.github.jamal_wia.kmptoolkit.settings

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith

/**
 * This module declares **no** Android permission, asserted against a real package manager rather
 * than against a reading of the manifest.
 *
 * It is worth asserting here even though nothing in this module obviously wants a permission:
 * changing the app's language is one of the operations that looks like it might need one, and a
 * future contributor reaching for `CHANGE_CONFIGURATION` — a signature-level permission an app
 * cannot hold anyway — would otherwise merge it into every consumer's manifest silently.
 */
@RunWith(AndroidJUnit4::class)
class LibraryManifestTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `the library manifest contributes no permission at all`() {
        val declared: Set<String> = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toSet()
            .orEmpty()

        // Asserting the remainder is empty rather than naming a handful of absences: a named-
        // absence check passes for any permission nobody thought to list. The subtracted set is
        // what the *test* apparatus contributes and never reaches a consumer — AndroidX's test
        // runner brings REORDER_TASKS, and AGP synthesises the dynamic-receiver permission.
        val fromTestHarnessOnly: Set<String> = setOf(
            "android.permission.REORDER_TASKS",
            "${context.packageName}.test.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )

        assertEquals(emptySet(), declared - fromTestHarnessOnly)
    }
}
