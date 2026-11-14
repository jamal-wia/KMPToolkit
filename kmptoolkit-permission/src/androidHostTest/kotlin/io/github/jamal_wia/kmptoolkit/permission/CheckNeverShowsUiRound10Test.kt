package io.github.jamal_wia.kmptoolkit.permission

import android.app.Application
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.activity.SystemScreenRequest
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * "`check` never shows UI" and "`observe` ... never shows UI" (API reference), for every public entry the controller
 * knows, on a spread of API levels: no dialog, no result screen, no Settings screen, and no flag written for an
 * entry that is simply not granted. `openSettings` of a type nobody knows is `false`.
 */
@RunWith(AndroidJUnit4::class)
class CheckNeverShowsUiRound10Test {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val component = ComponentName("com.example", "com.example.Thing")

    private val everyEntry: List<Permission> = CommonPermission.entries + AndroidPermission.specialAccesses + listOf(
        AndroidPermission.NearbyWifiDevices, AndroidPermission.LocalNetwork, AndroidPermission.BodySensors,
        AndroidPermission.BodySensorsBackground, AndroidPermission.Runtime("android.permission.READ_PHONE_STATE"),
        AndroidPermission.Role.Home, AndroidPermission.DeviceAdmin(component), AndroidPermission.AccessibilityService(component),
    )

    @Test
    fun `check and observe of every entry on every sampled level put nothing on screen and store nothing`() {
        for (sdk in listOf(24, 28, 29, 30, 31, 33, 34, 35, 36, 37)) {
            val host = RecordingHost()
            val screens: MutableList<SystemScreenRequest> = mutableListOf()
            val storage = InMemoryKeyValueStorage()
            val controller = testController(
                app, storage, host, sdkInt = sdk, launchScreen = { screens += it; true },
                shouldShowRationale = { false },
            )
            runBlocking {
                withTimeout(30_000) {
                    for (entry in everyEntry) {
                        controller.check(entry)
                        val seen: MutableList<PermissionStatus> = mutableListOf()
                        val job: Job = CoroutineScope(Dispatchers.Unconfined).launch { controller.observe(entry).collect { seen += it } }
                        job.cancel()
                    }
                }
            }
            assertEquals(0, host.launchCount, "API $sdk: a dialog: ${host.groups}")
            assertEquals(0, host.intents.size, "API $sdk: a result screen")
            assertEquals(emptyList(), screens, "API $sdk: a Settings screen")
            assertTrue(storage.contents.isEmpty(), "API $sdk: flags written for entries nobody asked about: ${storage.contents}")
        }
    }

    @Test
    fun `openSettings of a type no controller knows is false and opens nothing`() {
        val screens: MutableList<SystemScreenRequest> = mutableListOf()
        val controller = testController(app, InMemoryKeyValueStorage(), launchScreen = { screens += it; true })

        assertEquals(false, controller.openSettings(ForeignPermission))
        assertEquals(emptyList(), screens)
    }
}
