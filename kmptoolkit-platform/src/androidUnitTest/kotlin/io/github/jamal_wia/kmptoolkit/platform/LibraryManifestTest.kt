package io.github.jamal_wia.kmptoolkit.platform

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
 * It matters more here than in most modules: several of these seams do have a permission attached
 * to them — connectivity wants `ACCESS_NETWORK_STATE`, a `PowerManager`-based wake lock would want
 * `WAKE_LOCK` — and each is documented as the consumer's decision, with a typed degraded result
 * when it is absent. A permission slipping into the library manifest would silently take that
 * decision away from every consumer, and nothing else in the build would notice.
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
    fun `the library manifest does not contribute the network state permission`() {
        assertFalse(
            Manifest.permission.ACCESS_NETWORK_STATE in declared,
            "connectivity must degrade to ConnectivityStatus.UNKNOWN instead of merging a " +
                "permission into a consumer's manifest",
        )
    }

    @Test
    fun `the library manifest does not contribute the wake lock permission`() {
        assertFalse(
            Manifest.permission.WAKE_LOCK in declared,
            "the screen wake lock uses a window flag precisely so that no permission is needed",
        )
    }

    @Test
    fun `the library manifest does not contribute the internet permission`() {
        assertFalse(Manifest.permission.INTERNET in declared)
    }

    @Test
    fun `the library manifest does not contribute a storage permission`() {
        assertFalse(
            Manifest.permission.READ_EXTERNAL_STORAGE in declared,
            "the file picker relies on the per-file grant from OpenDocument, not on storage access",
        )
    }
}
