package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * PhotoLibraryAdd needs no grant from API 29 (scoped storage: media is added through MediaStore) and the legacy write permission
 * before it. Found by negating the boundary: no test read the status of it at both sides of API 29.
 */
@RunWith(AndroidJUnit4::class)
class PhotoLibraryAddBoundaryRound12Test {

    private val application: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `from API 29 adding photos needs nothing and before it needs the write permission`() = runTest {
        shadowOf(application).denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        application.declareInManifest(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        for (sdk in listOf(28, 29, 30, 34)) {
            val controller: AndroidPermissionController = testController(context = application, storage = InMemoryKeyValueStorage(), sdkInt = sdk)
            val status: PermissionStatus = controller.check(CommonPermission.PhotoLibraryAdd)
            if (sdk >= 29) {
                assertEquals(PermissionStatus.Granted, status, "sdk=$sdk")
            } else {
                assertNotEquals(PermissionStatus.Granted, status, "sdk=$sdk")
            }
        }
    }
}
