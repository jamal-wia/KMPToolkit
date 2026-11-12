@file:OptIn(ExperimentalForeignApi::class)

package io.github.jamal_wia.kmptoolkit.permission

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import platform.AVFoundation.AVAuthorizationStatusAuthorized

/**
 * 04-api-reference: "`observe` subscribes to request results before it first reads the status, so a request that
 * resolves in between is not missed." Round 9 could not test it (survivor I3: under a FIFO dispatcher the child
 * always runs first). A dispatcher that runs the newest task first puts the read ahead of any child launched
 * without UNDISPATCHED: the request then resolves between the first read and the subscription.
 */
class IosObserveSubscribeFirstRound10Test {

    @Test
    fun `a request that resolves right after the first read is not missed when the dispatcher runs the read first`() {
        val port = FakeIosAuthorizationPort()
                port.hooks["requestCameraAccess"] = { port.camera = AVAuthorizationStatusAuthorized }
        val controller: PermissionController = IosPermissionController(NoopLogger, IosPermissionOptions(), { true }, port)
        val lifo = LifoDispatcher()
        val scope = CoroutineScope(lifo)
        val seen: MutableList<PermissionStatus> = mutableListOf()

        val job: Job = scope.launch { controller.observe(CommonPermission.Camera).collect { seen += it } }
        var guard = 0
        while (seen.isEmpty()) {
            check(lifo.step()) { "nothing left to run before the first value" }
            check(++guard < 1000)
        }
        assertEquals(listOf<PermissionStatus>(PermissionStatus.NotDetermined), seen)

        // The first read is done; whatever the observer still has queued has not run. The user answers now.
        assertEquals(PermissionStatus.Granted, runBlocking { controller.request(CommonPermission.Camera) })
        lifo.drain()

        assertEquals(listOf(PermissionStatus.NotDetermined, PermissionStatus.Granted), seen)
        job.cancel()
        lifo.drain()
        scope.cancel()
    }
}
