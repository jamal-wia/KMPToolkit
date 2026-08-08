package io.github.jamal_wia.kmptoolkit.notification

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
 * `POST_NOTIFICATIONS` is the one every consumer of a notification library would expect it to bring
 * along, and that is exactly why it must not: a permission merged from a library appears in the
 * consumer's app silently, shows up on their Play Store listing, and — for a consumer who only uses
 * this module behind a feature flag that is off — cannot be removed without a `tools:node="remove"`
 * they would first have to discover. Declaring it is the app's decision; so is requesting it.
 *
 * See `docs/kmptoolkit-notification/05-platform-notes.md` for what the consumer must declare.
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
    fun `the library manifest does not contribute the notifications permission`() {
        assertFalse(
            Manifest.permission.POST_NOTIFICATIONS in declared,
            "POST_NOTIFICATIONS is the consumer's to declare and to justify",
        )
    }

    @Test
    fun `the library manifest does not contribute the vibrate permission`() {
        // NotificationCompat can vibrate, so this is the plausible second one to leak.
        assertFalse(Manifest.permission.VIBRATE in declared)
    }

    /**
     * The catch-all, and the one that would notice a permission nobody thought to name above —
     * including one arriving from a dependency rather than from this module's own manifest.
     *
     * It cannot assert an empty list: the manifest merged for a Robolectric run also carries the
     * test harness's own permissions, which are not in the published artifact. Everything outside
     * that known set would reach a consumer, so subtracting it keeps the assertion total rather
     * than a list of guesses. If this fails after a dependency upgrade, the right response is to
     * decide whether the new permission is acceptable, pin it here, and document it in
     * `05-platform-notes.md` — not to widen the set quietly.
     */
    @Test
    fun `nothing beyond the test harness's own permissions reaches a consumer`() {
        val harness: Set<String> = TEST_HARNESS_PERMISSIONS +
            "${context.packageName}.$DYNAMIC_RECEIVER_PERMISSION"
        val contributed: List<String> = declared - harness

        assertTrue(
            contributed.isEmpty(),
            "this module must merge no permission into a consumer's manifest, found: $contributed",
        )
    }

    private companion object {
        /** Declared by `androidx.test`'s own manifest, present only while running tests. */
        val TEST_HARNESS_PERMISSIONS: Set<String> = setOf("android.permission.REORDER_TASKS")

        /**
         * Synthesised by AGP for the **test** application only, prefixed with the test package.
         * It exists so a locally registered receiver in an instrumented run is not exported; it is
         * not in the published artifact and never reaches a consumer.
         */
        const val DYNAMIC_RECEIVER_PERMISSION: String = "DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    }
}
