package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The stored-flags contract: "checking a granted permission with no flag stored performs no write", and the
 * rationale flag is written "once per permission". `check` is a query; it must not turn into a write on
 * every call, whatever the API level or entry.
 */
@RunWith(AndroidJUnit4::class)
class CheckWritesNothingTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    private class CountingStorage(private val delegate: KeyValueStorage = InMemoryKeyValueStorage()) : KeyValueStorage by delegate {
        var writes: Int = 0
        override suspend fun put(key: String, value: String): StorageResult<Unit> { writes++; return delegate.put(key, value) }
        override suspend fun remove(key: String): StorageResult<Unit> { writes++; return delegate.remove(key) }
    }

    @Test
    fun `checking every granted catalog entry on every API level writes nothing`() = runTest {
        for (sdk in listOf(Build.VERSION_CODES.M, Build.VERSION_CODES.Q, Build.VERSION_CODES.R, Build.VERSION_CODES.S, Build.VERSION_CODES.TIRAMISU, Build.VERSION_CODES.UPSIDE_DOWN_CAKE)) {
            val storage = CountingStorage()
            val granted: List<String> = listOf(
                Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS, Manifest.permission.READ_CALENDAR,
                Manifest.permission.WRITE_CALENDAR, Manifest.permission.POST_NOTIFICATIONS,
            )
            shadowOf(application).grantPermissions(*granted.toTypedArray())
            val controller: AndroidPermissionController = testController(context = application, storage = storage, sdkInt = sdk)
            repeat(5) {
                for (entry in CommonPermission.entries) controller.check(entry)
            }
            assertEquals(0, storage.writes, "API $sdk: checking granted entries wrote to the storage")
        }
    }

    @Test
    fun `a refused permission that keeps asking for a rationale is written once however often it is checked`() = runTest {
        shadowOf(application).denyPermissions(Manifest.permission.CAMERA)
        val storage = CountingStorage()
        val controller: AndroidPermissionController = testController(
            context = application,
            storage = storage,
            shouldShowRationale = { true },
        )

        repeat(50) { controller.check(CommonPermission.Camera) }

        assertEquals(1, storage.writes, "the rationale flag was written more than once")
    }
}
