package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The Android half of the subscribe-before-read sentence of the API reference (survivor C1 of round 9). */
@RunWith(AndroidJUnit4::class)
class ObserveSubscribeFirstRound10Test {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `a request that resolves right after the first read is not missed when the dispatcher runs the read first`() {
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val host = object : RecordingHost() {
            override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
                shadowOf(app).grantPermissions(*androidPermissions.toTypedArray())
                groups += androidPermissions
                onResult(androidPermissions.associateWith { true })
                return true
            }
        }
        val controller: PermissionController = testController(app, InMemoryKeyValueStorage(), host)
        val lifo = LifoDispatcher()
        val scope = CoroutineScope(lifo)
        val seen: MutableList<PermissionStatus> = mutableListOf()

        val job: Job = scope.launch { controller.observe(CommonPermission.Microphone).collect { seen += it } }
        var guard = 0
        while (seen.isEmpty()) {
            check(lifo.step()) { "nothing left to run before the first value" }
            check(++guard < 1000)
        }
        assertEquals(listOf<PermissionStatus>(PermissionStatus.NotDetermined), seen)

        assertEquals(PermissionStatus.Granted, runBlocking { withTimeout(10_000) { controller.request(CommonPermission.Microphone) } })
        lifo.drain()

        assertEquals(listOf(PermissionStatus.NotDetermined, PermissionStatus.Granted), seen)
        job.cancel()
        lifo.drain()
        scope.cancel()
    }
}
