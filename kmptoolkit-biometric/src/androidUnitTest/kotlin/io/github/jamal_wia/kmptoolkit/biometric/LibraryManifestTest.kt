package io.github.jamal_wia.kmptoolkit.biometric

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
 * The pinning is the point. A permission merged from a dependency shows up in the consumer's Play
 * Store listing and app-info screen, attributed to them, with nothing in their own source to
 * explain it — and this module cannot be depended on without inheriting `androidx.biometric`, whose
 * manifest contributes three. They are not this library's to remove: `USE_BIOMETRIC` is what the
 * framework's own `BiometricPrompt` requires from API 28 up, `USE_FINGERPRINT` is its pre-28
 * predecessor, and `REORDER_TASKS` is used by the device-credential flow to bring the task forward.
 * Stripping any of them with `tools:node="remove"` would trade a documented permission for a
 * runtime `SecurityException` on somebody's device.
 *
 * So the guarantee this test defends is the one that can be kept: **nothing beyond those three, and
 * nothing this module added itself.** A new dependency, or an `androidx.biometric` upgrade that
 * starts asking for something new, fails here — where it is a decision to make and document in
 * `docs/kmptoolkit-biometric/05-platform-notes.md`, rather than being discovered by a consumer in a
 * store listing.
 *
 * All three are install-time permissions with no runtime prompt, which is why this module never
 * asks the user for anything.
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
    fun `the merged manifest contains exactly the androidx biometric permissions and nothing else`() {
        @Suppress("DEPRECATION")
        val expected: Set<String> = setOf(
            Manifest.permission.USE_BIOMETRIC,
            Manifest.permission.USE_FINGERPRINT,
            Manifest.permission.REORDER_TASKS,
        )

        assertEquals(
            expected,
            declared.toSet(),
            "the permission set changed; decide whether the new one is acceptable and document " +
                "it in docs/kmptoolkit-biometric/05-platform-notes.md before updating this test",
        )
    }

    @Test
    fun `no permission requiring a runtime prompt is merged in`() {
        // The install-time permissions above are a line in a listing. A dangerous one would put a
        // system dialog in front of the consumer's users on this library's behalf, which is not a
        // decision a dependency gets to make.
        listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        ).forEach { permission ->
            assertFalse(permission in declared, "$permission must not be merged in")
        }
    }

    @Test
    fun `no network permission is merged in`() {
        // Nothing here talks to a network, and a biometric library that asked for one would be
        // worth a second look.
        assertFalse(Manifest.permission.INTERNET in declared)
        assertFalse(Manifest.permission.ACCESS_NETWORK_STATE in declared)
    }

    @Test
    fun `the transitive platform dependency contributes no permission of its own`() {
        // kmptoolkit-platform makes the same promise; this asserts it still holds through the
        // merge rather than trusting that module's own test.
        assertFalse(Manifest.permission.WAKE_LOCK in declared)
        assertFalse(Manifest.permission.DISABLE_KEYGUARD in declared)
    }
}
