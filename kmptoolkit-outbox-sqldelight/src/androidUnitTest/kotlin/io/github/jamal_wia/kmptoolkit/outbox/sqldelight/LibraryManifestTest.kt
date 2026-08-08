package io.github.jamal_wia.kmptoolkit.outbox.sqldelight

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * This module's own `AndroidManifest.xml` declares **no** permission, and the exact set that
 * reaches a consumer's merged manifest is pinned here by name.
 *
 * Pinning is the point. A permission merged from a dependency appears in the consumer's Play Store
 * listing and app-info screen, attributed to them, with nothing in their own source to explain it.
 *
 * SQLDelight's `android-driver` — this module's only Android dependency of its own — declares none:
 * the queue lives in the app's private data directory, which needs no permission. The four below
 * arrive through `kmptoolkit-outbox`, which this module depends on by definition, and which cannot
 * offer an Android wake layer without `androidx.work`. They are documented and defended in that
 * module's own `LibraryManifestTest`; they are repeated here so that a change to *its* dependencies
 * fails this module's build too, rather than reaching a consumer who added this artifact and never
 * read that page.
 *
 * The assertion is exact rather than a handful of named-absence checks, which would pass for any
 * permission nobody thought to list.
 */
@RunWith(AndroidJUnit4::class)
class LibraryManifestTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val declared: Set<String>
        get() = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toSet()
            .orEmpty()
            // Contributed by the AndroidX test runner and by AGP's synthesised test manifest, not
            // by this library, and neither reaches a consumer.
            .minus(TEST_ONLY_PERMISSIONS)

    @Test
    fun `this module's own manifest declares nothing and the inherited set is exactly WorkManager's`() {
        assertEquals(
            EXPECTED_PERMISSIONS,
            declared,
            "the permission set changed; decide whether the new one is acceptable and document " +
                "it in docs/kmptoolkit-outbox-sqldelight/05-platform-notes.md before updating " +
                "this test",
        )
    }

    @Test
    fun `the SQLDelight driver contributes no permission of its own`() {
        // Everything in the merged manifest is accounted for by kmptoolkit-outbox's WorkManager
        // dependency. A SQLDelight upgrade that started declaring something would show up as a
        // permission outside that set — which is what the assertion above turns into a failure.
        assertTrue(declared.all { it in EXPECTED_PERMISSIONS })
    }

    @Test
    fun `no storage permission is merged in`() {
        // The queue lives in the app's own private data directory, which needs no permission. A
        // library that reached for external storage would be putting a user's queued effects
        // somewhere every other app can read.
        assertFalse(Manifest.permission.READ_EXTERNAL_STORAGE in declared)
        assertFalse(Manifest.permission.WRITE_EXTERNAL_STORAGE in declared)
        assertFalse(Manifest.permission.MANAGE_EXTERNAL_STORAGE in declared)
    }

    @Test
    fun `no permission requiring a runtime prompt is merged in`() {
        // A dangerous permission would put a system dialog in front of the consumer's users on this
        // library's behalf, which is not a decision a dependency gets to make.
        listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        ).forEach { permission ->
            assertFalse(permission in declared, "$permission must not be merged in")
        }
    }

    private companion object {

        /**
         * Contributed by `androidx.work`, through `kmptoolkit-outbox`'s Android wake layer.
         * Documented in `docs/kmptoolkit-outbox-sqldelight/05-platform-notes.md`.
         */
        val EXPECTED_PERMISSIONS: Set<String> = setOf(
            // Holds the CPU awake while a WorkManager job runs.
            "android.permission.WAKE_LOCK",
            // Restores scheduled work after a reboot.
            "android.permission.RECEIVE_BOOT_COMPLETED",
            // For work a consumer chooses to run as a foreground service.
            "android.permission.FOREGROUND_SERVICE",
            // Reads connectivity to honor a NetworkType constraint.
            "android.permission.ACCESS_NETWORK_STATE",
        )

        /** Present only in a test APK: the AndroidX runner's, and one AGP synthesises. */
        val TEST_ONLY_PERMISSIONS: Set<String> = setOf(
            "android.permission.REORDER_TASKS",
            "io.github.jamal_wia.kmptoolkit.outbox.sqldelight.test." +
                "DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )
    }
}
