package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivitySubscription
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.getStringOrNull
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The catalog entries that are more than one Android permission string, or none, or a different
 * string per API level — location, background location, audio files, Bluetooth — plus the
 * multi-permission host path location needs and [PermissionHandler.observe].
 *
 * Every case comes from the entry's KDoc on [Permission] and from `PermissionRequestHost`'s contract.
 */
@RunWith(AndroidJUnit4::class)
class AndroidPermissionCatalogTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val storage: KeyValueStorage = InMemoryKeyValueStorage()
    private val resumeListeners: MutableList<() -> Unit> = mutableListOf()

    /** A host implementing both launches, answering per permission from [answers]. */
    private class MultiHost(
        private val answers: Map<String, Boolean> = emptyMap(),
        private val implementsMulti: Boolean = true,
    ) : PermissionRequestHost {
        val single: MutableList<String> = mutableListOf()
        val groups: MutableList<List<String>> = mutableListOf()

        override fun launch(androidPermission: String, onResult: (Boolean) -> Unit): Boolean {
            single += androidPermission
            onResult(answers[androidPermission] ?: false)
            return true
        }

        override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
            if (!implementsMulti) return super.launch(androidPermissions, onResult)
            groups += androidPermissions
            onResult(androidPermissions.associateWith { answers[it] ?: false })
            return true
        }
    }

    private fun handler(
        host: PermissionRequestHost = MultiHost(),
        sdkInt: Int = Build.VERSION_CODES.TIRAMISU,
        rationale: Boolean? = false,
    ): AndroidPermissionHandler = AndroidPermissionHandler(
        context = application,
        host = host,
        storage = storage,
        keyPrefix = "test",
        logger = NoopLogger,
        sdkInt = sdkInt,
        shouldShowRationale = { rationale },
        awaitActivity = {},
        addResumedListener = { listener ->
            resumeListeners += listener
            object : ActivitySubscription {
                override fun cancel() {
                    resumeListeners -= listener
                }
            }
        },
        startSettings = { true },
    )

    private fun grant(vararg permissions: String) = shadowOf(application).grantPermissions(*permissions)

    private fun deny(vararg permissions: String) = shadowOf(application).denyPermissions(*permissions)

    // --- Location -----------------------------------------------------------------------------

    @Test
    fun `location is granted by an approximate-only grant`() = runTest {
        deny(Manifest.permission.ACCESS_FINE_LOCATION)
        grant(Manifest.permission.ACCESS_COARSE_LOCATION)

        assertEquals(PermissionStatus.Granted, handler().check(Permission.LOCATION))
    }

    @Test
    fun `location with neither grant is not determined`() = runTest {
        deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        assertEquals(PermissionStatus.NotDetermined, handler().check(Permission.LOCATION))
    }

    @Test
    fun `location is requested as fine and coarse in one dialog`() = runTest {
        deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val host = MultiHost(answers = mapOf(Manifest.permission.ACCESS_COARSE_LOCATION to true))

        val status: PermissionStatus = handler(host).request(Permission.LOCATION)

        assertEquals(
            listOf(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
            host.groups,
        )
        assertEquals(emptyList(), host.single)
        assertEquals(PermissionStatus.Granted, status)
    }

    @Test
    fun `a host without the multi-permission launch shows nothing for location and records nothing`() = runTest {
        deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        val status: PermissionStatus = handler(MultiHost(implementsMulti = false)).request(Permission.LOCATION)

        assertEquals(PermissionStatus.NotDetermined, status)
        assertNull(storage.getStringOrNull(askedKey("test", Permission.LOCATION)))
    }

    @Test
    fun `the default multi-permission launch forwards a one-element list to the single launch`() {
        val host = MultiHost(answers = mapOf("p" to true), implementsMulti = false)
        var answers: Map<String, Boolean>? = null

        val launched: Boolean = host.launch(listOf("p")) { answers = it }

        assertEquals(true, launched)
        assertEquals(listOf("p"), host.single)
        assertEquals(mapOf("p" to true), answers)
    }

    @Test
    fun `a location refusal with a rationale on either string is denied once`() = runTest {
        deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        assertEquals(
            PermissionStatus.Denied(shouldShowRationale = true),
            handler(rationale = true).check(Permission.LOCATION),
        )
    }

    // --- Background location ------------------------------------------------------------------

    @Test
    fun `background location is not requested while foreground location is not granted`() = runTest {
        deny(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
        val host = MultiHost(answers = mapOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION to true))

        val status: PermissionStatus = handler(host).request(Permission.LOCATION_BACKGROUND)

        assertEquals(PermissionStatus.NotDetermined, status)
        assertEquals(emptyList(), host.single)
    }

    @Test
    fun `background location is requested on its own once foreground location is granted`() = runTest {
        grant(Manifest.permission.ACCESS_FINE_LOCATION)
        deny(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val host = MultiHost(answers = mapOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION to true))

        val status: PermissionStatus = handler(host).request(Permission.LOCATION_BACKGROUND)

        assertEquals(listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), host.single)
        assertEquals(PermissionStatus.Granted, status)
    }

    @Test
    fun `below API 29 background location reports what foreground location reports`() = runTest {
        grant(Manifest.permission.ACCESS_COARSE_LOCATION)
        deny(Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        assertEquals(
            PermissionStatus.Granted,
            handler(sdkInt = Build.VERSION_CODES.P).check(Permission.LOCATION_BACKGROUND),
        )
    }

    // --- Audio files and Bluetooth ------------------------------------------------------------

    @Test
    fun `audio files are requested as READ_MEDIA_AUDIO from API 33`() = runTest {
        deny(Manifest.permission.READ_MEDIA_AUDIO)
        val host = MultiHost()

        handler(host, sdkInt = Build.VERSION_CODES.TIRAMISU).request(Permission.MEDIA_AUDIO)

        assertEquals(listOf(Manifest.permission.READ_MEDIA_AUDIO), host.single)
    }

    @Test
    fun `audio files are requested as READ_EXTERNAL_STORAGE below API 33`() = runTest {
        deny(Manifest.permission.READ_EXTERNAL_STORAGE)
        val host = MultiHost()

        handler(host, sdkInt = Build.VERSION_CODES.S_V2).request(Permission.MEDIA_AUDIO)

        assertEquals(listOf(Manifest.permission.READ_EXTERNAL_STORAGE), host.single)
    }

    @Test
    fun `bluetooth has no runtime grant below API 31`() = runTest {
        deny(Manifest.permission.BLUETOOTH_CONNECT)
        val host = MultiHost()

        val handler: AndroidPermissionHandler = handler(host, sdkInt = Build.VERSION_CODES.R)

        assertEquals(PermissionStatus.Granted, handler.check(Permission.BLUETOOTH_CONNECT))
        assertEquals(PermissionStatus.Granted, handler.request(Permission.BLUETOOTH_CONNECT))
        assertEquals(emptyList(), host.single)
    }

    @Test
    fun `bluetooth is a runtime permission from API 31`() = runTest {
        deny(Manifest.permission.BLUETOOTH_CONNECT)

        assertEquals(
            PermissionStatus.NotDetermined,
            handler(sdkInt = Build.VERSION_CODES.S).check(Permission.BLUETOOTH_CONNECT),
        )
    }

    // --- observe ------------------------------------------------------------------------------

    @Test
    fun `observe emits the current status, then a change seen on resume, and nothing unchanged`() =
        runTest(UnconfinedTestDispatcher()) {
            deny(Manifest.permission.CAMERA)
            val handler: AndroidPermissionHandler = handler()
            val seen = mutableListOf<PermissionStatus>()
            val collection = launch { handler.observe(Permission.CAMERA).toList(seen) }

            resumeActivity() // nothing changed
            grant(Manifest.permission.CAMERA)
            resumeActivity()

            assertEquals(listOf(PermissionStatus.NotDetermined, PermissionStatus.Granted), seen)
            collection.cancel()
        }

    @Test
    fun `observe re-reads after a request through the handler`() = runTest(UnconfinedTestDispatcher()) {
        deny(Manifest.permission.CAMERA)
        val host = object : PermissionRequestHost {
            override fun launch(androidPermission: String, onResult: (Boolean) -> Unit): Boolean {
                grant(androidPermission)
                onResult(true)
                return true
            }
        }
        val handler: AndroidPermissionHandler = handler(host)
        val seen = mutableListOf<PermissionStatus>()
        val collection = launch { handler.observe(Permission.CAMERA).toList(seen) }

        handler.request(Permission.CAMERA)

        assertEquals(listOf(PermissionStatus.NotDetermined, PermissionStatus.Granted), seen)
        collection.cancel()
    }

    @Test
    fun `a cancelled observation unregisters its resume listener`() = runTest(UnconfinedTestDispatcher()) {
        val collection = launch { handler().observe(Permission.CAMERA).collect {} }
        assertEquals(1, resumeListeners.size)

        collection.cancel()

        assertEquals(0, resumeListeners.size)
    }

    private fun resumeActivity() {
        resumeListeners.toList().forEach { it() }
    }
}
