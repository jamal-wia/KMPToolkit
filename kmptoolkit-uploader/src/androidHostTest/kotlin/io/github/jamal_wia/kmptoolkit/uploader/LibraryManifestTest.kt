package io.github.jamal_wia.kmptoolkit.uploader

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.junit.runner.RunWith

/**
 * This module's own `AndroidManifest.xml` declares **no** permission, and every permission that
 * does reach a consumer's merged manifest is pinned here by name.
 *
 * The pinning is the point. A permission merged from a dependency appears in the consumer's Play
 * Store listing and app-info screen, attributed to them, with nothing in their own source to
 * explain it — and this module cannot offer an Android wake layer without `androidx.work`, whose
 * manifest contributes its own. They are not this library's to remove: WorkManager needs
 * `WAKE_LOCK` to hold the CPU while a job runs, `RECEIVE_BOOT_COMPLETED` to restore its scheduled
 * work after a reboot, and the foreground-service permissions for jobs that ask to run in the
 * foreground. Stripping one with `tools:node="remove"` would trade a documented permission for a
 * runtime failure on somebody's device.
 *
 * So the guarantee this test defends is the one that can be kept: **nothing beyond what
 * `androidx.work` brings, and nothing this module added itself.** A new dependency, or a
 * WorkManager upgrade that starts asking for something new, fails here — where it is a decision to
 * make and to document in `docs/kmptoolkit-uploader/05-platform-notes.md` — rather than being
 * discovered by a consumer in a store listing.
 *
 * The expected set is read from the merged manifest rather than hardcoded blind: the assertion that
 * matters is that no **dangerous** permission is in it, and that the exact set is stable. Both are
 * asserted below.
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
            // Contributed by the test runner and by AGP's test manifest, not by this library, and
            // neither reaches a consumer. Subtracting a named set beats weakening the assertion to
            // a handful of named-absence checks, which would pass for anything nobody listed.
            .minus(TEST_ONLY_PERMISSIONS)

    @Test
    fun `the merged manifest contains exactly the WorkManager permissions and nothing else`() {
        assertEquals(
            EXPECTED_PERMISSIONS,
            declared,
            "the permission set changed; decide whether the new one is acceptable and document " +
                "it in docs/kmptoolkit-uploader/05-platform-notes.md before updating this test",
        )
    }

    @Test
    fun `no permission requiring a runtime prompt is merged in`() {
        // Every permission above is install-time: a line in a listing. A dangerous one would put a
        // system dialog in front of the consumer's users on this library's behalf, which is not a
        // decision a dependency gets to make.
        listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        ).forEach { permission ->
            assertFalse(permission in declared, "$permission must not be merged in")
        }
    }

    @Test
    fun `the INTERNET permission is not merged in`() {
        // The queue is network-bound in practice, but this library never opens a socket — handlers
        // do, through the consumer's own HTTP client. Declaring INTERNET here would be this library
        // taking a decision that belongs to the app.
        //
        // ACCESS_NETWORK_STATE *is* merged, from androidx.work, which reads connectivity to honor
        // a NetworkType constraint. It is pinned in EXPECTED_PERMISSIONS rather than asserted
        // absent here: claiming "no network permission" while one is in the merged manifest would
        // be exactly the kind of technically-true-but-misleading statement the invariant exists to
        // prevent.
        assertFalse(Manifest.permission.INTERNET in declared)
    }

    @Test
    fun `no exact-alarm permission is merged in`() {
        // The backoff alarm is a coroutine delay, not an AlarmManager alarm. If that ever changed,
        // SCHEDULE_EXACT_ALARM would need a Play Store justification from every consumer — which is
        // exactly the kind of thing that must not arrive silently.
        assertFalse(Manifest.permission.SCHEDULE_EXACT_ALARM in declared)
        assertFalse("android.permission.USE_EXACT_ALARM" in declared)
    }

    private companion object {

        /**
         * Contributed by `androidx.work`, which the Android wake layer cannot work without.
         * Documented in `docs/kmptoolkit-uploader/05-platform-notes.md`.
         */
        val EXPECTED_PERMISSIONS: Set<String> = setOf(
            // Holds the CPU awake while a job runs.
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
            "io.github.jamal_wia.kmptoolkit.uploader.test.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )
    }
}
