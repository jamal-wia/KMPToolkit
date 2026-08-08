package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * This module declares **no** Android permission, asserted against a real package manager rather
 * than against a reading of the manifest.
 *
 * Nowhere is the temptation stronger or the mistake worse. A permission library that merged
 * `CAMERA` into its consumers' manifests would hand every app that depends on it a Play Store
 * declaration to justify, a permission its users see on the listing, and — for the ones that never
 * touch a camera — no way to remove it short of a `tools:node="remove"` they would first have to
 * discover. The library's whole job is to help an app manage the permissions it chose; choosing
 * them for the app is the opposite of that.
 *
 * So the module ships no `AndroidManifest.xml` of its own with a `<uses-permission>` in it, and the
 * consumer declares every permission it intends to request — see
 * `docs/kmptoolkit-permission/05-platform-notes.md`.
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
    fun `the library manifest does not contribute the camera permission`() {
        assertFalse(
            Manifest.permission.CAMERA in declared,
            "requesting a permission is the consumer's decision; declaring it must be too",
        )
    }

    @Test
    fun `the library manifest does not contribute the record audio permission`() {
        assertFalse(Manifest.permission.RECORD_AUDIO in declared)
    }

    @Test
    fun `the library manifest does not contribute the notifications permission`() {
        assertFalse(
            Manifest.permission.POST_NOTIFICATIONS in declared,
            "an app that never posts a notification must not inherit POST_NOTIFICATIONS from a " +
                "library it uses for the camera",
        )
    }

    /**
     * The catch-all, and the one that would notice a permission nobody thought to name above.
     *
     * It cannot assert an empty list: the manifest merged for a Robolectric run also includes the
     * test harness's own permissions, which are not in the published artifact. Everything outside
     * that known set would have come from this module, so subtracting it keeps the assertion total
     * rather than a list of guesses.
     */
    @Test
    fun `the library contributes nothing beyond what the test harness itself declares`() {
        val contributed: List<String> = declared - TEST_HARNESS_PERMISSIONS

        assertTrue(
            contributed.isEmpty(),
            "this module must merge no permission into a consumer's manifest, found: $contributed",
        )
    }

    private companion object {
        /** Declared by `androidx.test`'s own manifest, present only while running tests. */
        val TEST_HARNESS_PERMISSIONS: Set<String> = setOf("android.permission.REORDER_TASKS")
    }
}
