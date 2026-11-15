@file:OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.permission

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse

/**
 * The batch contract of `PermissionController.request(Collection)` on iOS: "returns every one's final status"
 * (commonMain KDoc), so every entry is answered with the status it has after the last prompt of the batch, not with
 * the one it had when its own prompt ended. Derived from that sentence and from `docs/kmptoolkit-permission/04-api-reference.md`
 * (batches), scripted through [FakeIosAuthorizationPort]; every random choice comes from the fixed seed named in
 * the failure message and nothing waits in real time.
 */
class PermissionBatchFinalStatusTest {

    private val key = "NavigationKey"

    private fun port(authorization: CLAuthorizationStatus = kCLAuthorizationStatusAuthorizedAlways): FakeIosAuthorizationPort =
        FakeIosAuthorizationPort().apply {
            location = authorization
            locationReduced = true
            temporaryPurposeKeys = setOf(key)
        }

    private fun controller(port: FakeIosAuthorizationPort): PermissionController =
        IosPermissionController(NoopLogger, IosPermissionOptions(temporaryFullAccuracyPurposeKey = key), { true }, port)

    @Test
    fun `Location listed before LocationBackground answers both with the status after the upgrade`() = runTest {
        val port: FakeIosAuthorizationPort = port()
        val result: Map<Permission, PermissionStatus> =
            controller(port).request(listOf(CommonPermission.Location, CommonPermission.LocationBackground))

        assertEquals(1, port.count("requestTemporaryFullAccuracy"))
        assertEquals(PermissionStatus.Granted, result[CommonPermission.Location])
        assertEquals(PermissionStatus.Granted, result[CommonPermission.LocationBackground])
    }

    @Test
    fun `LocationBackground listed before Location answers both with the status after the upgrade`() = runTest {
        val port: FakeIosAuthorizationPort = port()
        val result: Map<Permission, PermissionStatus> =
            controller(port).request(listOf(CommonPermission.LocationBackground, CommonPermission.Location))

        assertEquals(1, port.count("requestTemporaryFullAccuracy"))
        assertEquals(listOf<Permission>(CommonPermission.LocationBackground, CommonPermission.Location), result.keys.toList())
        assertEquals(PermissionStatus.Granted, result[CommonPermission.LocationBackground])
        assertEquals(PermissionStatus.Granted, result[CommonPermission.Location])
    }

    @Test
    fun `a declined upgrade leaves both entries Limited and asks once`() = runTest {
        val port: FakeIosAuthorizationPort = port().apply { temporaryFullAccuracyAccepted = false }
        val result: Map<Permission, PermissionStatus> =
            controller(port).request(listOf(CommonPermission.LocationBackground, CommonPermission.Location))

        assertEquals(1, port.count("requestTemporaryFullAccuracy"))
        assertEquals(PermissionStatus.Limited, result[CommonPermission.LocationBackground])
        assertEquals(PermissionStatus.Limited, result[CommonPermission.Location])
    }

    /**
     * The general rule, not only the location pair: the camera is revoked in Settings while the microphone prompt of the
     * same batch is up, so the camera's own answer (Granted, given first) is stale when the batch returns.
     */
    @Test
    fun `an entry changed behind the app while a later prompt was up is answered with its current status`() = runTest {
        val port: FakeIosAuthorizationPort = FakeIosAuthorizationPort().apply {
            // The controller below is built with the purpose key, and construction checks that the bundle has it.
            temporaryPurposeKeys = setOf(key)
            hooks["requestCameraAccess"] = { camera = AVAuthorizationStatusAuthorized }
            hooks["requestRecordPermission"] = {
                recordState = AVAudioSessionRecordPermissionGranted
                camera = AVAuthorizationStatusDenied
            }
        }
        val controller: PermissionController = controller(port)

        val result: Map<Permission, PermissionStatus> =
            controller.request(listOf(CommonPermission.Camera, CommonPermission.Microphone))

        assertEquals(PermissionStatus.Granted, result[CommonPermission.Microphone], "control: the later prompt was answered")
        assertNotEquals(PermissionStatus.Granted, result[CommonPermission.Camera], "the batch kept the camera answer it read before the revoke")
        assertEquals(controller.check(CommonPermission.Camera), result[CommonPermission.Camera])
    }

    @Test
    fun `an empty batch answers an empty map and reads nothing`() = runTest {
        val port: FakeIosAuthorizationPort = port()
        assertEquals(emptyMap(), controller(port).request(emptyList()))
        assertTrue(port.calls.isEmpty(), "an empty batch touched the port: ${port.calls}")
    }

    /** A batch cancelled while its second prompt is up: the lock is released, and a later request runs and is answered whole. */
    @Test
    fun `a batch cancelled mid-prompt leaves the controller usable and the next batch complete`() = runTest {
        val port: FakeIosAuthorizationPort = port()
        val gate = CompletableDeferred<Unit>()
        port.gates["requestTemporaryFullAccuracy"] = gate
        val controller: PermissionController = controller(port)

        val cancelled: Deferred<Map<Permission, PermissionStatus>> =
            async { controller.request(listOf(CommonPermission.LocationBackground, CommonPermission.Location)) }
        runCurrent()
        assertEquals(1, port.count("requestTemporaryFullAccuracy"), "control: the upgrade prompt is up")
        cancelled.cancel()
        runCurrent()
        assertTrue(cancelled.isCancelled)

        gate.complete(Unit)
        port.gates.clear()
        port.locationReduced = true
        val next: Map<Permission, PermissionStatus> =
            controller.request(listOf(CommonPermission.LocationBackground, CommonPermission.Location))
        assertEquals(PermissionStatus.Granted, next[CommonPermission.LocationBackground])
        assertEquals(PermissionStatus.Granted, next[CommonPermission.Location])
    }

    /**
     * Seeded shuffles of a mixed set: after every batch, every returned status equals what `check` reads at that
     * moment. Requests are serialized by the controller, so nothing else changes the port between the two.
     */
    @Test
    fun `every status of a seeded batch equals the status read right after the batch`() = runTest {
        val seed = 20_261_116
        val random = Random(seed)
        val pool: List<Permission> = listOf(
            CommonPermission.Location,
            CommonPermission.LocationBackground,
            CommonPermission.Camera,
            CommonPermission.Microphone,
        )
        repeat(40) { round ->
            val port: FakeIosAuthorizationPort = port(
                if (random.nextBoolean()) kCLAuthorizationStatusAuthorizedAlways else kCLAuthorizationStatusAuthorizedWhenInUse,
            ).apply {
                temporaryFullAccuracyAccepted = random.nextBoolean()
                hooks["requestCameraAccess"] = { camera = AVAuthorizationStatusAuthorized }
                hooks["requestRecordPermission"] = { recordState = AVAudioSessionRecordPermissionGranted }
            }
            val controller: PermissionController = controller(port)
            val batch: List<Permission> = pool.shuffled(random).take(random.nextInt(1, pool.size + 1))
            val result: Map<Permission, PermissionStatus> = controller.request(batch)
            assertEquals(batch.distinct(), result.keys.toList(), "seed=$seed round=$round")
            result.forEach { (permission: Permission, status: PermissionStatus) ->
                assertEquals(controller.check(permission), status, "seed=$seed round=$round batch=$batch permission=$permission")
            }
            assertFalse(port.count("requestTemporaryFullAccuracy") > 1, "seed=$seed round=$round: the upgrade was asked twice")
        }
    }
}
