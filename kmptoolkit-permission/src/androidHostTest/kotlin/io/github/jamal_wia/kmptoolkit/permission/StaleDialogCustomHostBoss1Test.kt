package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Boss round 1 of E1. `PermissionRequestHost` promises a custom host that "the controller only ever has
 * one request in flight, so an implementation needs at most one pending callback per member", and
 * 04-api-reference says a request queued behind a cancelled caller's dialog that is still up after the
 * bound "fails like a refused launch: nothing shown". This host keeps one callback slot and does not
 * refuse a second launch (it relies on that promise), so a launch made while the stale dialog is up is
 * visible as a call, not hidden behind a refusal.
 *
 * The test thread is the main thread under Robolectric, so launches run inline; every wait is bounded.
 */
@RunWith(AndroidJUnit4::class)
class StaleDialogCustomHostBoss1Test {

    private val application: Application = ApplicationProvider.getApplicationContext()
    private val camera: String = Manifest.permission.CAMERA
    private val microphone: String = Manifest.permission.RECORD_AUDIO

    /** One callback slot, as the host KDoc allows; counts every launch call. */
    private class OneSlotHost : PermissionRequestHost {
        val launches: MutableList<List<String>> = mutableListOf()
        var slot: ((Map<String, Boolean>) -> Unit)? = null

        override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
            launches += androidPermissions
            slot = onResult
            return true
        }

        override fun launchForResult(intent: Intent, onResult: (resultCode: Int) -> Unit): Boolean = false
    }

    @Test
    fun `past the bound the queued caller makes no launch call while the stale dialog is up`(): Unit = runBlocking {
        listOf(camera, microphone).forEach { shadowOf(application).denyPermissions(it) }
        application.declareInManifest(camera, microphone)
        val host = OneSlotHost()
        val controller = testController(application, InMemoryKeyValueStorage(), host, staleDialogTimeout = 150.milliseconds)
        val first = launch(start = CoroutineStart.UNDISPATCHED) { controller.request(CommonPermission.Camera) }
        assertEquals(1, host.launches.size)
        withTimeout(5_000) { first.cancelAndJoin() }

        val second: PermissionStatus = withTimeout(5_000) { controller.request(CommonPermission.Microphone) }

        assertEquals(PermissionStatus.NotDetermined, second)
        assertEquals(1, host.launches.size, "the timed-out caller launched a second dialog over the stale one")
    }
}
