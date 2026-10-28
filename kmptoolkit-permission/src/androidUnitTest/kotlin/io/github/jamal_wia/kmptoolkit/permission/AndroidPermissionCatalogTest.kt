package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.ActivitySubscription
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.storage.KeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.getStringOrNull
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The common entries that are more than one Android permission string, or none, or a different
 * string per API level — location, background location, audio files, Bluetooth — plus the manifest
 * and policy reads every entry goes through, and [PermissionController.observe].
 *
 * Every case comes from the entry's KDoc on [CommonPermission] and from `PermissionRequestHost`'s contract.
 */
@RunWith(AndroidJUnit4::class)
class AndroidPermissionCatalogTest {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val storage: KeyValueStorage = InMemoryKeyValueStorage()

    /** A host answering per permission from [answers]; every launch is a group, possibly of one. */
    private class MultiHost(answers: Map<String, Boolean> = emptyMap()) : RecordingHost(answers = { answers[it] ?: false })

    private val resumes: ResumeListeners = ResumeListeners()

    private fun handler(
        host: PermissionRequestHost = MultiHost(),
        sdkInt: Int = Build.VERSION_CODES.TIRAMISU,
        rationale: Boolean? = false,
        neverForLocation: Boolean = true,
        logger: Logger = NoopLogger,
    ): AndroidPermissionController = testController(
        context = application,
        host = host,
        storage = storage,
        sdkInt = sdkInt,
        shouldShowRationale = { rationale },
        resumeListeners = resumes,
        disavowsLocationForScan = {
            manifestReads++
            neverForLocation
        },
        logger = logger,
    )

    /** How often a handler read the `neverForLocation` declaration. */
    private var manifestReads: Int = 0

    private fun grant(vararg permissions: String) = shadowOf(application).grantPermissions(*permissions)

    private fun deny(vararg permissions: String) = shadowOf(application).denyPermissions(*permissions)

    // --- Location -----------------------------------------------------------------------------

    @Test
    fun `an approximate-only location grant is limited`() = runTest {
        deny(Manifest.permission.ACCESS_FINE_LOCATION)
        grant(Manifest.permission.ACCESS_COARSE_LOCATION)

        assertEquals(PermissionStatus.Limited, handler().check(CommonPermission.Location))
    }

    @Test
    fun `a precise location grant is granted`() = runTest {
        grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        assertEquals(PermissionStatus.Granted, handler().check(CommonPermission.Location))
    }

    @Test
    fun `a request from approximate location shows the dialog again, which offers precise`() = runTest {
        deny(Manifest.permission.ACCESS_FINE_LOCATION)
        grant(Manifest.permission.ACCESS_COARSE_LOCATION)
        val host = MultiHost(
            answers = mapOf(Manifest.permission.ACCESS_FINE_LOCATION to true, Manifest.permission.ACCESS_COARSE_LOCATION to true),
        )

        val status: PermissionStatus = handler(host).request(CommonPermission.Location)

        assertEquals(1, host.launchCount)
        assertEquals(PermissionStatus.Granted, status)
    }

    @Test
    fun `location with neither grant is not determined`() = runTest {
        deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        assertEquals(PermissionStatus.NotDetermined, handler().check(CommonPermission.Location))
    }

    @Test
    fun `location is requested as fine and coarse in one dialog`() = runTest {
        deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val host = MultiHost(answers = mapOf(Manifest.permission.ACCESS_COARSE_LOCATION to true))

        val status: PermissionStatus = handler(host).request(CommonPermission.Location)

        assertEquals(
            listOf(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
            host.groups,
        )
        assertEquals(PermissionStatus.Limited, status)
    }

    @Test
    fun `an interrupted request, answered with no entries, records nothing`() = runTest {
        deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val host = object : RecordingHost() {
            override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
                onResult(emptyMap())
                return true
            }
        }

        val status: PermissionStatus = handler(host).request(CommonPermission.Location)

        assertEquals(PermissionStatus.NotDetermined, status)
        assertNull(storage.getStringOrNull(askedKey("test", CommonPermission.Location)))
    }

    @Test
    fun `a location refusal with a rationale on either string is denied once`() = runTest {
        deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        assertEquals(
            PermissionStatus.Denied(explainFirst = true),
            handler(rationale = true).check(CommonPermission.Location),
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

        val status: PermissionStatus = handler(host).request(CommonPermission.LocationBackground)

        assertEquals(PermissionStatus.NotDetermined, status)
        assertEquals(0, host.launchCount)
    }

    @Test
    fun `background location is requested on its own once foreground location is granted`() = runTest {
        grant(Manifest.permission.ACCESS_FINE_LOCATION)
        deny(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        val host = MultiHost(answers = mapOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION to true))

        val status: PermissionStatus = handler(host).request(CommonPermission.LocationBackground)

        assertEquals(listOf(listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)), host.groups)
        assertEquals(PermissionStatus.Granted, status)
    }

    @Test
    fun `below API 29 background location reports what foreground location reports`() = runTest {
        grant(Manifest.permission.ACCESS_COARSE_LOCATION)
        deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)

        // Approximate foreground location is limited, and so is the background location it covers.
        assertEquals(
            PermissionStatus.Limited,
            handler(sdkInt = Build.VERSION_CODES.P).check(CommonPermission.LocationBackground),
        )
    }

    // --- Audio files and Bluetooth ------------------------------------------------------------

    @Test
    fun `audio files are requested as READ_MEDIA_AUDIO from API 33`() = runTest {
        deny(Manifest.permission.READ_MEDIA_AUDIO)
        val host = MultiHost()

        handler(host, sdkInt = Build.VERSION_CODES.TIRAMISU).request(CommonPermission.AudioLibrary)

        assertEquals(listOf(listOf(Manifest.permission.READ_MEDIA_AUDIO)), host.groups)
    }

    @Test
    fun `audio files are requested as READ_EXTERNAL_STORAGE below API 33`() = runTest {
        deny(Manifest.permission.READ_EXTERNAL_STORAGE)
        val host = MultiHost()

        handler(host, sdkInt = Build.VERSION_CODES.S_V2).request(CommonPermission.AudioLibrary)

        assertEquals(listOf(listOf(Manifest.permission.READ_EXTERNAL_STORAGE)), host.groups)
    }

    @Test
    fun `bluetooth has no runtime grant below API 31`() = runTest {
        deny(Manifest.permission.BLUETOOTH_CONNECT)
        val host = MultiHost()

        val handler: AndroidPermissionController = handler(host, sdkInt = Build.VERSION_CODES.R)

        assertEquals(PermissionStatus.Granted, handler.check(CommonPermission.BluetoothConnect))
        assertEquals(PermissionStatus.Granted, handler.request(CommonPermission.BluetoothConnect))
        assertEquals(0, host.launchCount)
    }

    @Test
    fun `bluetooth is a runtime permission from API 31`() = runTest {
        deny(Manifest.permission.BLUETOOTH_CONNECT)

        assertEquals(
            PermissionStatus.NotDetermined,
            handler(sdkInt = Build.VERSION_CODES.S).check(CommonPermission.BluetoothConnect),
        )
    }

    // --- Bluetooth scanning, API 31+ ----------------------------------------------------------

    @Test
    fun `bluetooth scanning reads BLUETOOTH_SCAN from API 31`() = runTest {
        grant(Manifest.permission.BLUETOOTH_SCAN)
        deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        assertEquals(PermissionStatus.Granted, handler(sdkInt = Build.VERSION_CODES.S).check(CommonPermission.BluetoothScan))
    }

    @Test
    fun `bluetooth scanning is requested as BLUETOOTH_SCAN alone, never with location`() = runTest {
        deny(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        val host = MultiHost(answers = mapOf(Manifest.permission.BLUETOOTH_SCAN to true))

        val status: PermissionStatus = handler(host, sdkInt = Build.VERSION_CODES.S).request(CommonPermission.BluetoothScan)

        assertEquals(listOf(listOf(Manifest.permission.BLUETOOTH_SCAN)), host.groups)
        assertEquals(PermissionStatus.Granted, status)
    }

    @Test
    fun `a refused bluetooth scan is remembered under its own entry, not under location`() = runTest {
        deny(Manifest.permission.BLUETOOTH_SCAN)

        handler(MultiHost(), sdkInt = Build.VERSION_CODES.S).request(CommonPermission.BluetoothScan)

        assertEquals("dialog-shown", storage.getStringOrNull(askedKey("test", CommonPermission.BluetoothScan)))
        assertNull(storage.getStringOrNull(askedKey("test", CommonPermission.Location)))
    }

    @Test
    fun `without neverForLocation a granted bluetooth scan reads unavailable`() = runTest {
        grant(Manifest.permission.BLUETOOTH_SCAN)

        assertEquals(
            PermissionStatus.Unavailable,
            handler(sdkInt = Build.VERSION_CODES.S, neverForLocation = false).check(CommonPermission.BluetoothScan),
        )
    }

    @Test
    fun `without neverForLocation a bluetooth scan request shows nothing and records nothing`() = runTest {
        deny(Manifest.permission.BLUETOOTH_SCAN)
        val host = MultiHost(answers = mapOf(Manifest.permission.BLUETOOTH_SCAN to true))

        val status: PermissionStatus =
            handler(host, sdkInt = Build.VERSION_CODES.S, neverForLocation = false).request(CommonPermission.BluetoothScan)

        assertEquals(PermissionStatus.Unavailable, status)
        assertEquals(emptyList(), host.groups)
        assertNull(storage.getStringOrNull(askedKey("test", CommonPermission.BluetoothScan)))
        assertNull(storage.getStringOrNull(askedKey("test", CommonPermission.Location)))
    }

    @Test
    fun `a missing neverForLocation is read and warned about once per handler`() = runTest {
        grant(Manifest.permission.BLUETOOTH_SCAN)
        val logger = RecordingLogger()
        val handler: AndroidPermissionController =
            handler(sdkInt = Build.VERSION_CODES.S, neverForLocation = false, logger = logger)

        repeat(3) { handler.check(CommonPermission.BluetoothScan) }
        handler.request(CommonPermission.BluetoothScan)

        assertEquals(1, manifestReads)
        assertEquals(1, logger.warnings.count { "neverForLocation" in it })
    }

    @Test
    fun `the declaration is not read for any other permission`() = runTest {
        grant(Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_CONNECT)
        val handler: AndroidPermissionController = handler(sdkInt = Build.VERSION_CODES.S, neverForLocation = false)

        handler.check(CommonPermission.Camera)
        handler.check(CommonPermission.BluetoothConnect)

        assertEquals(0, manifestReads)
    }

    // --- Bluetooth scanning below API 31: location --------------------------------------------

    @Test
    fun `below API 31 bluetooth scanning reports what location reports`() = runTest {
        grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        deny(Manifest.permission.BLUETOOTH_SCAN)

        listOf(Build.VERSION_CODES.R, Build.VERSION_CODES.Q, Build.VERSION_CODES.N).forEach { sdkInt ->
            assertEquals(PermissionStatus.Granted, handler(sdkInt = sdkInt).check(CommonPermission.BluetoothScan), "API $sdkInt")
        }
    }

    @Test
    fun `below API 31 bluetooth scanning without location is not granted`() = runTest {
        // Even with the string itself granted: below API 31 it gates nothing.
        grant(Manifest.permission.BLUETOOTH_SCAN)
        deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        assertEquals(
            PermissionStatus.NotDetermined,
            handler(sdkInt = Build.VERSION_CODES.R).check(CommonPermission.BluetoothScan),
        )
    }

    @Test
    fun `below API 31 bluetooth scanning is requested as the location dialog`() = runTest {
        deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val host = MultiHost(
            answers = mapOf(
                Manifest.permission.ACCESS_FINE_LOCATION to true,
                Manifest.permission.ACCESS_COARSE_LOCATION to true,
            ),
        )

        val status: PermissionStatus = handler(host, sdkInt = Build.VERSION_CODES.R).request(CommonPermission.BluetoothScan)

        assertEquals(
            listOf(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
            host.groups,
        )
        assertEquals(PermissionStatus.Granted, status)
    }

    @Test
    fun `below API 31 a refusal of bluetooth scanning is a refusal of location`() = runTest {
        deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        handler(MultiHost(), sdkInt = Build.VERSION_CODES.R).request(CommonPermission.BluetoothScan)

        assertEquals("dialog-shown", storage.getStringOrNull(askedKey("test", CommonPermission.Location)))
        assertNull(storage.getStringOrNull(askedKey("test", CommonPermission.BluetoothScan)))
    }

    @Test
    fun `below API 31 a permanently refused location keeps bluetooth scanning permanently denied`() = runTest {
        deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        // First refusal of location: the rationale is shown, and remembered.
        handler(MultiHost(), rationale = true).request(CommonPermission.Location)
        // Second refusal: Android stops asking and the rationale turns false.
        val host = MultiHost()
        val handler: AndroidPermissionController = handler(host, sdkInt = Build.VERSION_CODES.R, rationale = false)

        assertEquals(PermissionStatus.PermanentlyDenied, handler.check(CommonPermission.BluetoothScan))
        assertEquals(PermissionStatus.PermanentlyDenied, handler.request(CommonPermission.BluetoothScan))
        assertEquals(emptyList(), host.groups)
    }

    @Test
    fun `below API 31 the neverForLocation declaration plays no part`() = runTest {
        grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        val status: PermissionStatus =
            handler(sdkInt = Build.VERSION_CODES.R, neverForLocation = false).check(CommonPermission.BluetoothScan)

        assertEquals(PermissionStatus.Granted, status)
        assertEquals(0, manifestReads)
    }

    @Test
    fun `below API 31 observing location sees a bluetooth scan request, and the other way round`() =
        runTest(UnconfinedTestDispatcher()) {
            deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            val host = object : RecordingHost() {
                override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
                    grant(*androidPermissions.toTypedArray())
                    onResult(androidPermissions.associateWith { true })
                    return true
                }
            }
            val handler: AndroidPermissionController = handler(host, sdkInt = Build.VERSION_CODES.R)
            val location = mutableListOf<PermissionStatus>()
            val scan = mutableListOf<PermissionStatus>()
            val locationCollection = launch { handler.observe(CommonPermission.Location).toList(location) }
            val scanCollection = launch { handler.observe(CommonPermission.BluetoothScan).toList(scan) }

            handler.request(CommonPermission.BluetoothScan)

            assertEquals(listOf(PermissionStatus.NotDetermined, PermissionStatus.Granted), location)
            assertEquals(listOf(PermissionStatus.NotDetermined, PermissionStatus.Granted), scan)
            locationCollection.cancel()
            scanCollection.cancel()
        }

    // --- observe ------------------------------------------------------------------------------

    @Test
    fun `observe emits the current status, then a change seen on resume, and nothing unchanged`() =
        runTest(UnconfinedTestDispatcher()) {
            deny(Manifest.permission.CAMERA)
            val handler: AndroidPermissionController = handler()
            val seen = mutableListOf<PermissionStatus>()
            val collection = launch { handler.observe(CommonPermission.Camera).toList(seen) }

            resumeActivity() // nothing changed
            grant(Manifest.permission.CAMERA)
            resumeActivity()

            assertEquals(listOf(PermissionStatus.NotDetermined, PermissionStatus.Granted), seen)
            collection.cancel()
        }

    @Test
    fun `observe re-reads after a request through the handler`() = runTest(UnconfinedTestDispatcher()) {
        deny(Manifest.permission.CAMERA)
        val host = object : RecordingHost() {
            override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
                grant(*androidPermissions.toTypedArray())
                onResult(androidPermissions.associateWith { true })
                return true
            }
        }
        val handler: AndroidPermissionController = handler(host)
        val seen = mutableListOf<PermissionStatus>()
        val collection = launch { handler.observe(CommonPermission.Camera).toList(seen) }

        handler.request(CommonPermission.Camera)

        assertEquals(listOf(PermissionStatus.NotDetermined, PermissionStatus.Granted), seen)
        collection.cancel()
    }

    @Test
    fun `a cancelled observation unregisters its resume listener`() = runTest(UnconfinedTestDispatcher()) {
        val collection = launch { handler().observe(CommonPermission.Camera).collect {} }
        assertEquals(1, resumes.count)

        collection.cancel()

        assertEquals(0, resumes.count)
    }

    @Test
    fun `observe sees a permission granted in settings once the user is back`() = runTest(UnconfinedTestDispatcher()) {
        // Re-request after returning from Settings: permanently denied, granted by hand, resumed.
        deny(Manifest.permission.CAMERA)
        storage.put(askedKey("test", CommonPermission.Camera), "dialog-shown")
        storage.put(rationaleSeenKey("test", CommonPermission.Camera), "true")
        val handler: AndroidPermissionController = handler()
        val seen = mutableListOf<PermissionStatus>()
        val collection = launch { handler.observe(CommonPermission.Camera).toList(seen) }

        assertTrue(handler.openSettings(CommonPermission.Camera))
        grant(Manifest.permission.CAMERA)
        resumeActivity()

        assertEquals(listOf(PermissionStatus.PermanentlyDenied, PermissionStatus.Granted), seen)
        assertEquals(PermissionStatus.Granted, handler.request(CommonPermission.Camera))
        collection.cancel()
    }

    // --- Manifest and policy ------------------------------------------------------------------

    private fun declaring(vararg permissions: String, host: PermissionRequestHost = MultiHost(), revoked: Set<String> = emptySet()) =
        testController(
            context = application,
            storage = storage,
            host = host,
            declaredPermissions = { permissions.toSet() },
            isRevokedByPolicy = { it in revoked },
        )

    @Test
    fun `a permission the manifest does not declare is unavailable and never prompts`() = runTest {
        deny(Manifest.permission.CAMERA)
        val host = MultiHost(answers = mapOf(Manifest.permission.CAMERA to true))
        val handler: AndroidPermissionController = declaring(Manifest.permission.RECORD_AUDIO, host = host)

        assertEquals(PermissionStatus.Unavailable, handler.check(CommonPermission.Camera))
        assertEquals(PermissionStatus.Unavailable, handler.request(CommonPermission.Camera))
        assertEquals(0, host.launchCount)
        assertNull(storage.getStringOrNull(askedKey("test", CommonPermission.Camera)))
    }

    @Test
    fun `a group is requested with only the strings the manifest declares`() = runTest {
        deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val host = MultiHost(answers = mapOf(Manifest.permission.ACCESS_COARSE_LOCATION to true))

        val status: PermissionStatus =
            declaring(Manifest.permission.ACCESS_COARSE_LOCATION, host = host).request(CommonPermission.Location)

        assertEquals(listOf(listOf(Manifest.permission.ACCESS_COARSE_LOCATION)), host.groups)
        assertEquals(PermissionStatus.Limited, status)
    }

    @Test
    fun `an undeclared permission is warned about once however often it is read`() = runTest {
        val logger = RecordingLogger()
        val handler: AndroidPermissionController = testController(
            context = application,
            storage = storage,
            declaredPermissions = { emptySet() },
            logger = logger,
        )

        repeat(3) { handler.check(CommonPermission.Camera) }

        assertEquals(1, logger.warnings.count { "CAMERA" in it })
    }

    @Test
    fun `a permission revoked by a device policy is restricted and never prompts`() = runTest {
        deny(Manifest.permission.CAMERA)
        val host = MultiHost()
        val handler: AndroidPermissionController =
            declaring(Manifest.permission.CAMERA, host = host, revoked = setOf(Manifest.permission.CAMERA))

        assertEquals(PermissionStatus.Restricted, handler.check(CommonPermission.Camera))
        assertEquals(PermissionStatus.Restricted, handler.request(CommonPermission.Camera))
        assertEquals(0, host.launchCount)
    }

    @Test
    fun `a granted permission is granted whatever a policy flag says`() = runTest {
        grant(Manifest.permission.CAMERA)

        assertEquals(
            PermissionStatus.Granted,
            declaring(Manifest.permission.CAMERA, revoked = setOf(Manifest.permission.CAMERA)).check(CommonPermission.Camera),
        )
    }

    private fun resumeActivity() {
        resumes.fire()
    }

    /** Keeps every warning's text. */
    private class RecordingLogger : Logger {
        val warnings: MutableList<String> = mutableListOf()

        override val tag: String = "test"

        override fun isLoggable(level: LogLevel): Boolean = true

        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            if (level == LogLevel.WARN) warnings += message()
        }
    }
}
