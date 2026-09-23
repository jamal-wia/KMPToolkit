package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.content.ContextWrapper
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The manifest read behind [Permission.BLUETOOTH_SCAN]'s API 31+ contract: `neverForLocation` on the
 * app's own `BLUETOOTH_SCAN` declaration, read from the same `PackageInfo` flags Android reads.
 */
@RunWith(AndroidJUnit4::class)
class ScanDeclarationTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    /** Replaces this app's installed package with one requesting exactly [permissions], with their flags. */
    private fun declare(vararg permissions: Pair<String, Int>) {
        val info = PackageInfo().apply {
            packageName = application.packageName
            applicationInfo = application.applicationInfo
            requestedPermissions = permissions.map { it.first }.toTypedArray()
            requestedPermissionsFlags = permissions.map { it.second }.toIntArray()
        }
        shadowOf(application.packageManager).installPackage(info)
    }

    @Test
    fun `BLUETOOTH_SCAN declared with neverForLocation disavows location`() {
        declare(
            Manifest.permission.BLUETOOTH_CONNECT to 0,
            Manifest.permission.BLUETOOTH_SCAN to PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION,
        )

        assertTrue(application.declaresScanNeverForLocation(NoopLogger))
    }

    @Test
    fun `BLUETOOTH_SCAN declared without the flag does not`() {
        declare(
            Manifest.permission.BLUETOOTH_SCAN to 0,
            Manifest.permission.BLUETOOTH_CONNECT to PackageInfo.REQUESTED_PERMISSION_NEVER_FOR_LOCATION,
        )

        assertFalse(application.declaresScanNeverForLocation(NoopLogger))
    }

    @Test
    fun `an app that does not declare BLUETOOTH_SCAN does not`() {
        declare(Manifest.permission.CAMERA to 0)

        assertFalse(application.declaresScanNeverForLocation(NoopLogger))
    }

    @Test
    fun `a PackageInfo without flags does not`() {
        val info = PackageInfo().apply {
            packageName = application.packageName
            applicationInfo = application.applicationInfo
            requestedPermissions = arrayOf(Manifest.permission.BLUETOOTH_SCAN)
            requestedPermissionsFlags = null
        }
        shadowOf(application.packageManager).installPackage(info)

        assertFalse(application.declaresScanNeverForLocation(NoopLogger))
    }

    @Test
    fun `a package that cannot be read does not, and does not throw`() {
        val unknown = object : ContextWrapper(application) {
            override fun getPackageName(): String = "no.such.package"
        }

        assertFalse(unknown.declaresScanNeverForLocation(NoopLogger))
    }

    @Test
    @Config(sdk = [30])
    fun `below API 31 there is no flag to read`() {
        assertFalse(application.declaresScanNeverForLocation(NoopLogger))
    }
}
