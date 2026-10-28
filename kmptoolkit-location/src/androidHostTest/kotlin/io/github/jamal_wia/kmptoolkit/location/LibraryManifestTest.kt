package io.github.jamal_wia.kmptoolkit.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertFalse
import org.junit.runner.RunWith

/**
 * This module declares **no** Android permission, and this asserts it against a real package
 * manager rather than against a reading of the manifest.
 *
 * It matters more here than in most modules: [createLocationProvider] genuinely needs
 * `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` to return a real fix, and it is exactly the
 * kind of permission that is tempting to declare "for convenience" in a library manifest. Doing so
 * would silently merge it into every consuming app instead of leaving that decision — and its
 * store-listing disclosure — with the consumer.
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
    fun `the library manifest does not contribute the fine location permission`() {
        assertFalse(
            Manifest.permission.ACCESS_FINE_LOCATION in declared,
            "getCurrentLocation/observeLocation must degrade to null instead of merging a " +
                "permission into a consumer's manifest",
        )
    }

    @Test
    fun `the library manifest does not contribute the coarse location permission`() {
        assertFalse(Manifest.permission.ACCESS_COARSE_LOCATION in declared)
    }

    @Test
    fun `the library manifest does not contribute the background location permission`() {
        assertFalse(Manifest.permission.ACCESS_BACKGROUND_LOCATION in declared)
    }
}
