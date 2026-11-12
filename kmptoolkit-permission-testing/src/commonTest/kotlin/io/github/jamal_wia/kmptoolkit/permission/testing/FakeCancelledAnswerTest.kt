package io.github.jamal_wia.kmptoolkit.permission.testing

import io.github.jamal_wia.kmptoolkit.permission.CommonPermission
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * 04-api-reference "Cancellation": cancelling the caller of `request` abandons the result, not the system UI, so the
 * user's answer still reaches the OS and the next `check` sees it. The fake models that for a request cancelled while
 * it is held; a request cancelled while only queued never reached the dialog and stores nothing.
 */
class FakeCancelledAnswerTest {

    @Test
    fun `a request cancelled while held still takes the answer once the hold is released`() = runTest {
        val fake = FakePermissionController()
        fake.scriptRequest(CommonPermission.Camera, PermissionStatus.Granted)
        fake.holdRequests = true
        val job: Job = launch { fake.request(CommonPermission.Camera) }
        runCurrent()
        job.cancel()
        runCurrent()

        fake.holdRequests = false
        runCurrent()

        assertEquals(PermissionStatus.Granted, fake.check(CommonPermission.Camera))
    }

    @Test
    fun `a scripted refusal of a cancelled held request is stored too`() = runTest {
        val fake = FakePermissionController()
        fake.scriptRequest(CommonPermission.Camera, PermissionStatus.PermanentlyDenied)
        fake.holdRequests = true
        val job: Job = launch { fake.request(CommonPermission.Camera) }
        runCurrent()
        job.cancel()
        runCurrent()
        fake.holdRequests = false

        assertEquals(PermissionStatus.PermanentlyDenied, fake.check(CommonPermission.Camera))
    }

    @Test
    fun `a request cancelled while only queued behind another stores nothing`() = runTest {
        val fake = FakePermissionController()
        fake.holdRequests = true
        val first: Job = launch { fake.request(CommonPermission.Camera) }
        runCurrent()
        val queued: Job = launch { fake.request(CommonPermission.Microphone) }
        runCurrent()
        queued.cancel()
        runCurrent()

        fake.holdRequests = false
        runCurrent()

        assertEquals(PermissionStatus.NotDetermined, fake.check(CommonPermission.Microphone))
        first.cancel()
    }

    @Test
    fun `a batch cancelled while held keeps only the answer of the permission on screen`() = runTest {
        val fake = FakePermissionController()
        fake.holdRequests = true
        val job: Job = launch { fake.request(listOf(CommonPermission.Camera, CommonPermission.Microphone)) }
        runCurrent()
        job.cancel()
        runCurrent()

        fake.holdRequests = false
        runCurrent()

        assertEquals(PermissionStatus.Granted, fake.check(CommonPermission.Camera))
        assertEquals(PermissionStatus.NotDetermined, fake.check(CommonPermission.Microphone))
    }
}
