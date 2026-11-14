package io.github.jamal_wia.kmptoolkit.permission

import io.github.jamal_wia.kmptoolkit.permission.FidelityScenarios.runAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** What the scenarios must read like, written out by hand from the API reference, played on the fake. */
class FakeFidelityPinnedTest {

    @Test
    fun `the fake plays the scenarios as the API reference describes the real controllers`() = runTest {
        val scope: TestScope = this
        val got: Map<String, List<String>> = runAll { FakeWorld { scope.testScheduler.runCurrent() } }

        assertEquals(
            listOf("fresh:notGranted", "request:granted", "check:granted", "again:granted", "observed:notGranted", "observed:granted"),
            got.getValue("basic"),
        )
        assertEquals(
            listOf("keys:CAMERA, MICROPHONE", "CAMERA:granted", "MICROPHONE:notGranted", "after:granted,notGranted", "empty:0"),
            got.getValue("batch"),
        )
        assertEquals(
            listOf("whileUp:false,false", "after:granted,notGranted", "order:[r1, r2]"),
            got.getValue("serialized"),
        )
        assertEquals(listOf("open:true", "opened:1", "cannotOpen:false"), got.getValue("settings"))
        assertEquals(listOf("whileUp:false,false", "after:granted,granted"), got.getValue("decidedRequestWaitsBehindDialog"))
        assertEquals(listOf("observed:notGranted", "observed:granted"), got.getValue("observeDeduplicates"))
        assertEquals(listOf("first:granted", "queuedCancelledNeverAsked:notGranted"), got.getValue("cancelQueued"))
        assertEquals(
            listOf("observed:notGranted", "observed:granted", "check:granted"),
            got.getValue("settingsTrip"),
        )
    }

    @Test
    fun `an unscripted request that shows UI resolves to Granted as the testing docs say`() = runTest {
        val fake = io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController()
        assertEquals(PermissionStatus.Granted, fake.request(CommonPermission.Camera))
        assertEquals(PermissionStatus.Granted, fake.check(CommonPermission.Camera))
    }

    @Test
    fun `a request the OS would not show returns at once even while requests are held`() = runTest {
        // "A request the OS would not show is not shown here either": the hold is the system UI being up, and a decided
        // permission has none. (The KDoc of holdRequests says every request suspends; the more specific rule wins.)
        val fake = io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController()
        fake.setStatus(CommonPermission.Camera, PermissionStatus.Granted)
        fake.setStatus(CommonPermission.Microphone, PermissionStatus.PermanentlyDenied)
        fake.holdRequests = true

        assertEquals(PermissionStatus.Granted, fake.request(CommonPermission.Camera))
        assertEquals(PermissionStatus.PermanentlyDenied, fake.request(CommonPermission.Microphone))
        assertEquals<Map<Permission, PermissionStatus>>(
            mapOf(CommonPermission.Camera to PermissionStatus.Granted, CommonPermission.Microphone to PermissionStatus.PermanentlyDenied),
            fake.request(listOf(CommonPermission.Camera, CommonPermission.Microphone)),
        )
    }
}
