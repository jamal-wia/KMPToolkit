package io.github.jamal_wia.kmptoolkit.video.player.compose

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * This module's own manifest declares **no** permission, and every permission that does reach a
 * consumer's merged manifest is pinned here by name.
 *
 * It is not a zero-dependency case: through `kmptoolkit-video-player` it pulls Media3 ExoPlayer,
 * whose manifest contributes two install-time permissions — `ACCESS_NETWORK_STATE`, which it reads
 * to adapt streaming to the network type, and `WAKE_LOCK`, for its optional wake mode. They are not
 * this library's to strip (`tools:node="remove"` would trade a documented permission for degraded
 * streaming on somebody's device), so the guarantee defended here is the one that can be kept:
 * **nothing beyond what Media3 brings, and nothing this module added itself.** A new dependency or
 * a Media3 upgrade that asks for more fails here, where it is a decision to make and to document
 * in `docs/kmptoolkit-video-player-compose/05-platform-notes.md`.
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
            .filterNot(::isTestHarnessPermission)
            .toSet()

    @Test
    fun `the merged manifest contains exactly the Media3 permissions and nothing else`() {
        assertEquals(
            EXPECTED_PERMISSIONS,
            declared,
            "the permission set changed; decide whether the new one is acceptable and document it " +
                "in docs/kmptoolkit-video-player-compose/05-platform-notes.md before updating this test",
        )
    }

    @Test
    fun `neither INTERNET nor any runtime permission is merged in`() {
        // Streaming a Remote source needs INTERNET, but that is the app's declaration to make: an
        // app that only plays bundled files needs none.
        listOf(
            Manifest.permission.INTERNET,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.FOREGROUND_SERVICE,
        ).forEach { permission ->
            assertFalse(permission in declared, "$permission must not be merged in")
        }
    }

    private companion object {

        /** Contributed by Media3 ExoPlayer; documented in the module's platform notes. */
        val EXPECTED_PERMISSIONS: Set<String> = setOf(
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.WAKE_LOCK",
        )

        /**
         * Added by the instrumentation harness, not by any library: `REORDER_TASKS` comes with the
         * AndroidX test runner, and AGP synthesises a per-package receiver permission for every
         * test application.
         */
        fun isTestHarnessPermission(permission: String): Boolean =
            permission == Manifest.permission.REORDER_TASKS ||
                permission.endsWith(".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
    }
}
