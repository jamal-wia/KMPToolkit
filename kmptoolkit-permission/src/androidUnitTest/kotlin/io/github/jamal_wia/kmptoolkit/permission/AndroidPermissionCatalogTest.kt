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
        neverForLocation: Boolean = true,
        logger: Logger = NoopLogger,
    ): AndroidPermissionHandler = AndroidPermissionHandler(
        context = application,
        host = host,
        storage = storage,
        keyPrefix = "test",
        logger = logger,
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
        disavowsLocationForScan = {
            manifestReads++
            neverForLocation
        },
    )

    /** How often a handler read the `neverForLocation` declaration. */
    private var manifestReads: Int = 0

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

    // --- Bluetooth scanning, API 31+ ----------------------------------------------------------

    @Test
    fun `bluetooth scanning reads BLUETOOTH_SCAN from API 31`() = runTest {
        grant(Manifest.permission.BLUETOOTH_SCAN)
        deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        assertEquals(PermissionStatus.Granted, handler(sdkInt = Build.VERSION_CODES.S).check(Permission.BLUETOOTH_SCAN))
    }

    @Test
    fun `bluetooth scanning is requested as BLUETOOTH_SCAN alone, never with location`() = runTest {
        deny(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
        val host = MultiHost(answers = mapOf(Manifest.permission.BLUETOOTH_SCAN to true))

        val status: PermissionStatus = handler(host, sdkInt = Build.VERSION_CODES.S).request(Permission.BLUETOOTH_SCAN)

        assertEquals(listOf(Manifest.permission.BLUETOOTH_SCAN), host.single)
        assertEquals(emptyList(), host.groups)
        assertEquals(PermissionStatus.Granted, status)
    }

    @Test
    fun `a refused bluetooth scan is remembered under its own entry, not under location`() = runTest {
        deny(Manifest.permission.BLUETOOTH_SCAN)

        handler(MultiHost(), sdkInt = Build.VERSION_CODES.S).request(Permission.BLUETOOTH_SCAN)

        assertEquals("dialog-shown", storage.getStringOrNull(askedKey("test", Permission.BLUETOOTH_SCAN)))
        assertNull(storage.getStringOrNull(askedKey("test", Permission.LOCATION)))
    }

    @Test
    fun `without neverForLocation a granted bluetooth scan reads not determined`() = runTest {
        grant(Manifest.permission.BLUETOOTH_SCAN)

        assertEquals(
            PermissionStatus.NotDetermined,
            handler(sdkInt = Build.VERSION_CODES.S, neverForLocation = false).check(Permission.BLUETOOTH_SCAN),
        )
    }

    @Test
    fun `without neverForLocation a bluetooth scan request shows nothing and records nothing`() = runTest {
        deny(Manifest.permission.BLUETOOTH_SCAN)
        val host = MultiHost(answers = mapOf(Manifest.permission.BLUETOOTH_SCAN to true))

        val status: PermissionStatus =
            handler(host, sdkInt = Build.VERSION_CODES.S, neverForLocation = false).request(Permission.BLUETOOTH_SCAN)

        assertEquals(PermissionStatus.NotDetermined, status)
        assertEquals(emptyList(), host.single)
        assertEquals(emptyList(), host.groups)
        assertNull(storage.getStringOrNull(askedKey("test", Permission.BLUETOOTH_SCAN)))
        assertNull(storage.getStringOrNull(askedKey("test", Permission.LOCATION)))
    }

    @Test
    fun `a missing neverForLocation is read and warned about once per handler`() = runTest {
        grant(Manifest.permission.BLUETOOTH_SCAN)
        val logger = RecordingLogger()
        val handler: AndroidPermissionHandler =
            handler(sdkInt = Build.VERSION_CODES.S, neverForLocation = false, logger = logger)

        repeat(3) { handler.check(Permission.BLUETOOTH_SCAN) }
        handler.request(Permission.BLUETOOTH_SCAN)

        assertEquals(1, manifestReads)
        assertEquals(1, logger.warnings.count { "neverForLocation" in it })
    }

    @Test
    fun `the declaration is not read for any other permission`() = runTest {
        grant(Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_CONNECT)
        val handler: AndroidPermissionHandler = handler(sdkInt = Build.VERSION_CODES.S, neverForLocation = false)

        handler.check(Permission.CAMERA)
        handler.check(Permission.BLUETOOTH_CONNECT)

        assertEquals(0, manifestReads)
    }

    // --- Bluetooth scanning below API 31: location --------------------------------------------

    @Test
    fun `below API 31 bluetooth scanning reports what location reports`() = runTest {
        grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        deny(Manifest.permission.BLUETOOTH_SCAN)

        listOf(Build.VERSION_CODES.R, Build.VERSION_CODES.Q, Build.VERSION_CODES.N).forEach { sdkInt ->
            assertEquals(PermissionStatus.Granted, handler(sdkInt = sdkInt).check(Permission.BLUETOOTH_SCAN), "API $sdkInt")
        }
    }

    @Test
    fun `below API 31 bluetooth scanning without location is not granted`() = runTest {
        // Even with the string itself granted: below API 31 it gates nothing.
        grant(Manifest.permission.BLUETOOTH_SCAN)
        deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        assertEquals(
            PermissionStatus.NotDetermined,
            handler(sdkInt = Build.VERSION_CODES.R).check(Permission.BLUETOOTH_SCAN),
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

        val status: PermissionStatus = handler(host, sdkInt = Build.VERSION_CODES.R).request(Permission.BLUETOOTH_SCAN)

        assertEquals(
            listOf(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
            host.groups,
        )
        assertEquals(emptyList(), host.single)
        assertEquals(PermissionStatus.Granted, status)
    }

    @Test
    fun `below API 31 a refusal of bluetooth scanning is a refusal of location`() = runTest {
        deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        handler(MultiHost(), sdkInt = Build.VERSION_CODES.R).request(Permission.BLUETOOTH_SCAN)

        assertEquals("dialog-shown", storage.getStringOrNull(askedKey("test", Permission.LOCATION)))
        assertNull(storage.getStringOrNull(askedKey("test", Permission.BLUETOOTH_SCAN)))
    }

    @Test
    fun `below API 31 a permanently refused location keeps bluetooth scanning permanently denied`() = runTest {
        deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        // First refusal of location: the rationale is shown, and remembered.
        handler(MultiHost(), rationale = true).request(Permission.LOCATION)
        // Second refusal: Android stops asking and the rationale turns false.
        val host = MultiHost()
        val handler: AndroidPermissionHandler = handler(host, sdkInt = Build.VERSION_CODES.R, rationale = false)

        assertEquals(PermissionStatus.PermanentlyDenied, handler.check(Permission.BLUETOOTH_SCAN))
        assertEquals(PermissionStatus.PermanentlyDenied, handler.request(Permission.BLUETOOTH_SCAN))
        assertEquals(emptyList(), host.groups)
    }

    @Test
    fun `below API 31 the neverForLocation declaration plays no part`() = runTest {
        grant(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

        val status: PermissionStatus =
            handler(sdkInt = Build.VERSION_CODES.R, neverForLocation = false).check(Permission.BLUETOOTH_SCAN)

        assertEquals(PermissionStatus.Granted, status)
        assertEquals(0, manifestReads)
    }

    @Test
    fun `below API 31 observing location sees a bluetooth scan request, and the other way round`() =
        runTest(UnconfinedTestDispatcher()) {
            deny(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            val host = object : PermissionRequestHost {
                override fun launch(androidPermission: String, onResult: (Boolean) -> Unit): Boolean = false
                override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
                    grant(*androidPermissions.toTypedArray())
                    onResult(androidPermissions.associateWith { true })
                    return true
                }
            }
            val handler: AndroidPermissionHandler = handler(host, sdkInt = Build.VERSION_CODES.R)
            val location = mutableListOf<PermissionStatus>()
            val scan = mutableListOf<PermissionStatus>()
            val locationCollection = launch { handler.observe(Permission.LOCATION).toList(location) }
            val scanCollection = launch { handler.observe(Permission.BLUETOOTH_SCAN).toList(scan) }

            handler.request(Permission.BLUETOOTH_SCAN)

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
