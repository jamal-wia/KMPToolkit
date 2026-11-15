@file:OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.permission

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.CoreLocation.kCLAuthorizationStatusNotDetermined
import platform.CoreLocation.kCLAuthorizationStatusRestricted

/**
 * iOS temporary full accuracy (docs/kmptoolkit-permission/03-guide.md, 04-api-reference.md), derived from the contract:
 * the direct function [requestTemporaryFullAccuracy] and the common path
 * (`IosPermissionOptions.temporaryFullAccuracyPurposeKey` + `request(CommonPermission.Location)`), both driven
 * through the scripted [FakeIosAuthorizationPort]; nothing here shows a prompt or waits on the main queue, and
 * `runTest` keeps its default timeout.
 */
class TemporaryFullAccuracyTest {

    private val key = "NavigationKey"

    private fun port(
        authorization: CLAuthorizationStatus = kCLAuthorizationStatusAuthorizedWhenInUse,
        reduced: Boolean = true,
        keys: Set<String> = setOf(key),
    ): FakeIosAuthorizationPort = FakeIosAuthorizationPort().apply {
        location = authorization
        locationReduced = reduced
        temporaryPurposeKeys = keys
    }

    private fun controller(port: FakeIosAuthorizationPort, purposeKey: String? = key): PermissionController =
        IosPermissionController(
            NoopLogger,
            IosPermissionOptions(temporaryFullAccuracyPurposeKey = purposeKey),
            { true },
            port,
        )

    // --- requestTemporaryFullAccuracy ---------------------------------------------------------------

    @Test
    fun `a status other than Limited is returned without a prompt with the key checked all the same`() = runTest {
        val cases: Map<String, Pair<FakeIosAuthorizationPort, PermissionStatus>> = mapOf(
            "Granted" to (port(reduced = false) to PermissionStatus.Granted),
            "NotDetermined" to (port(kCLAuthorizationStatusNotDetermined, reduced = false) to PermissionStatus.NotDetermined),
            "PermanentlyDenied" to (port(kCLAuthorizationStatusDenied, reduced = false) to PermissionStatus.PermanentlyDenied),
            "Restricted" to (port(kCLAuthorizationStatusRestricted, reduced = false) to PermissionStatus.Restricted),
        )
        cases.forEach { (name, case) ->
            val (port: FakeIosAuthorizationPort, expected: PermissionStatus) = case
            assertEquals(expected, requestTemporaryFullAccuracy(port, key), name)
            assertEquals(0, port.count("requestTemporaryFullAccuracy"), "$name: the prompt must not be asked for")
            assertTrue(port.temporaryKeysAsked.isEmpty(), name)
        }
    }

    @Test
    fun `from Limited the prompt is shown under the key and accepting reads Granted`() = runTest {
        val port: FakeIosAuthorizationPort = port()
        assertEquals(PermissionStatus.Granted, requestTemporaryFullAccuracy(port, key))
        assertEquals(listOf(key), port.temporaryKeysAsked)
    }

    @Test
    fun `from Limited declining the prompt reads Limited`() = runTest {
        val port: FakeIosAuthorizationPort = port().apply { temporaryFullAccuracyAccepted = false }
        assertEquals(PermissionStatus.Limited, requestTemporaryFullAccuracy(port, key))
        assertEquals(1, port.count("requestTemporaryFullAccuracy"))
    }

    @Test
    fun `a key missing from the bundle fails naming it in every status and before the status is read`() = runTest {
        val statuses: List<FakeIosAuthorizationPort> = listOf(
            port(reduced = false, keys = emptySet()),
            port(kCLAuthorizationStatusNotDetermined, reduced = false, keys = setOf("OtherKey")),
            port(keys = emptySet()),
        )
        statuses.forEach { port ->
            val failure: IllegalStateException =
                assertFailsWith<IllegalStateException> { requestTemporaryFullAccuracy(port, "MissingKey") }
            assertTrue("MissingKey" in failure.message.orEmpty(), "the message must name the key: ${failure.message}")
            assertEquals(0, port.count("locationAuthorization"), "the key is validated before the status is read")
            assertEquals(0, port.count("requestTemporaryFullAccuracy"))
        }
    }

    @Test
    fun `the public function fails naming a key a bundle without the dictionary lacks`() = runTest {
        // The test binary's Info.plist has no NSLocationTemporaryUsageDescriptionDictionary, so this runs the
        // real adapter's bundle read and stops before any status read or prompt.
        assertTrue(temporaryUsagePurposeKeysInInfoPlist().isEmpty())
        val failure: IllegalStateException =
            assertFailsWith<IllegalStateException> { requestTemporaryFullAccuracy("NoSuchPurposeKey") }
        assertTrue("NoSuchPurposeKey" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `the function can be called again after a decline and after an accepted prompt`() = runTest {
        val port: FakeIosAuthorizationPort = port().apply { temporaryFullAccuracyAccepted = false }
        assertEquals(PermissionStatus.Limited, requestTemporaryFullAccuracy(port, key))
        port.temporaryFullAccuracyAccepted = true
        assertEquals(PermissionStatus.Granted, requestTemporaryFullAccuracy(port, key))
        // Granted now: the third call shows nothing.
        assertEquals(PermissionStatus.Granted, requestTemporaryFullAccuracy(port, key))
        assertEquals(2, port.count("requestTemporaryFullAccuracy"))
    }

    // --- options and the factory check --------------------------------------------------------------

    @Test
    fun `a blank key is rejected by the options and no key is the default`() {
        assertNull(IosPermissionOptions().temporaryFullAccuracyPurposeKey)
        assertFailsWith<IllegalArgumentException> { IosPermissionOptions(temporaryFullAccuracyPurposeKey = "") }
        assertFailsWith<IllegalArgumentException> { IosPermissionOptions(temporaryFullAccuracyPurposeKey = "  ") }
        assertFailsWith<IllegalArgumentException> { IosPermissionOptions().copy(temporaryFullAccuracyPurposeKey = " ") }
        assertEquals(key, IosPermissionOptions().copy(temporaryFullAccuracyPurposeKey = key).temporaryFullAccuracyPurposeKey)
    }

    @Test
    fun `creating the controller with a configured key absent from the bundle fails naming it`() {
        val port: FakeIosAuthorizationPort = port(keys = setOf("OtherKey"))
        val failure: IllegalStateException = assertFailsWith<IllegalStateException> { controller(port, "MissingKey") }
        assertTrue("MissingKey" in failure.message.orEmpty(), failure.message)
        assertEquals(0, port.calls.size, "the check reads the bundle only")
    }

    @Test
    fun `creating the controller with a configured key present or with no key does not fail`() {
        controller(port(), key)
        controller(port(keys = emptySet()), purposeKey = null)
    }

    // --- the common path ----------------------------------------------------------------------------

    @Test
    fun `with the key configured request of Location from Limited prompts and returns the new status`() = runTest {
        val accepted: FakeIosAuthorizationPort = port()
        assertEquals(PermissionStatus.Granted, controller(accepted).request(CommonPermission.Location))
        assertEquals(listOf(key), accepted.temporaryKeysAsked)
        assertEquals(0, accepted.count("requestWhenInUse"), "an authorized app must not be asked for when-in-use again")

        val declined: FakeIosAuthorizationPort = port().apply { temporaryFullAccuracyAccepted = false }
        assertEquals(PermissionStatus.Limited, controller(declined).request(CommonPermission.Location))
        assertEquals(1, declined.count("requestTemporaryFullAccuracy"))
    }

    @Test
    fun `without the key request of Location from Limited returns Limited at once as in 4x`() = runTest {
        val port: FakeIosAuthorizationPort = port(keys = emptySet())
        assertEquals(PermissionStatus.Limited, controller(port, purposeKey = null).request(CommonPermission.Location))
        assertEquals(0, port.count("requestTemporaryFullAccuracy"))
        assertEquals(0, port.count("requestWhenInUse"))
    }

    @Test
    fun `with the key configured a status other than Limited still follows the old route`() = runTest {
        val undetermined: FakeIosAuthorizationPort = port(kCLAuthorizationStatusNotDetermined, reduced = false).apply {
            hooks["requestWhenInUse"] = { location = kCLAuthorizationStatusAuthorizedWhenInUse }
        }
        assertEquals(PermissionStatus.Granted, controller(undetermined).request(CommonPermission.Location))
        assertEquals(1, undetermined.count("requestWhenInUse"))
        assertEquals(0, undetermined.count("requestTemporaryFullAccuracy"))

        val granted: FakeIosAuthorizationPort = port(reduced = false)
        assertEquals(PermissionStatus.Granted, controller(granted).request(CommonPermission.Location))
        assertEquals(0, granted.count("requestTemporaryFullAccuracy"))
    }

    @Test
    fun `a batch asks for the upgrade of Location once and answers every entry`() = runTest {
        val port: FakeIosAuthorizationPort = port()
        val result: Map<Permission, PermissionStatus> =
            controller(port).request(listOf(CommonPermission.Location, CommonPermission.Location))
        assertEquals(mapOf<Permission, PermissionStatus>(CommonPermission.Location to PermissionStatus.Granted), result)
        assertEquals(1, port.count("requestTemporaryFullAccuracy"))
    }

    @Test
    fun `observe of Location goes Granted to Limited when the temporary grant ends`() = runTest {
        val port: FakeIosAuthorizationPort = port()
        val controller: PermissionController = controller(port)
        val seen: MutableList<PermissionStatus> = mutableListOf()
        val job: Job = launch { controller.observe(CommonPermission.Location).collect { seen += it } }
        runCurrent()
        assertEquals(listOf<PermissionStatus>(PermissionStatus.Limited), seen)
        assertEquals(1, port.becameActiveListeners.size, "control: the observer registered its listener")

        controller.request(CommonPermission.Location)
        runCurrent()
        assertEquals(PermissionStatus.Granted, seen.last())

        // The temporary grant ends: accuracy is reduced again and the app comes back to the foreground.
        port.locationReduced = true
        port.activate()
        runCurrent()
        assertEquals(listOf(PermissionStatus.Limited, PermissionStatus.Granted, PermissionStatus.Limited), seen)
        job.cancel()
        runCurrent()
        assertEquals(1, port.unregistered, "the became-active listener must be released with the collector")
        assertTrue(port.becameActiveListeners.isEmpty())
    }

    // --- cancellation, re-initialisation, concurrency -----------------------------------------------

    private fun TestScope.heldPrompt(port: FakeIosAuthorizationPort): CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().also { gate -> port.gates["requestTemporaryFullAccuracy"] = gate }

    @Test
    fun `a caller cancelled mid-prompt leaves no half state and the next request is served`() = runTest {
        val port: FakeIosAuthorizationPort = port()
        val gate: CompletableDeferred<Unit> = heldPrompt(port)
        val controller: PermissionController = controller(port)

        val first: Job = launch { controller.request(CommonPermission.Location) }
        runCurrent()
        assertEquals(1, port.count("requestTemporaryFullAccuracy"))
        first.cancel()
        runCurrent()
        assertTrue(first.isCancelled)
        assertTrue(port.locationReduced, "the cancelled caller must not have flipped the accuracy: the prompt was never answered")

        // The platform's late completion changes nothing for anyone and the request mutex is free.
        gate.complete(Unit)
        port.gates.remove("requestTemporaryFullAccuracy")
        assertEquals(PermissionStatus.Granted, controller.request(CommonPermission.Location))
        assertEquals(2, port.count("requestTemporaryFullAccuracy"))
    }

    @Test
    fun `a function call cancelled mid-prompt can be repeated on a fresh controller after release`() = runTest {
        val port: FakeIosAuthorizationPort = port()
        val gate: CompletableDeferred<Unit> = heldPrompt(port)
        val pending: Job = launch { requestTemporaryFullAccuracy(port, key) }
        runCurrent()
        pending.cancel()
        runCurrent()
        gate.complete(Unit)
        port.gates.remove("requestTemporaryFullAccuracy")

        // Re-initialisation: a controller created after the cancelled call works on the same port.
        assertEquals(PermissionStatus.Granted, controller(port).request(CommonPermission.Location))
    }

    @Test
    fun `concurrent requests are served one prompt at a time and the sequence of answers is the seeded one`() = runTest {
        val seed = 20_261_115
        val random = Random(seed)
        val callers = 24
        // Every prompt is answered by a seeded coin; an accepted one ends the need for further prompts.
        val answers: List<Boolean> = List(callers) { random.nextBoolean() }
        val firstAccepted: Int = answers.indexOfFirst { it }
        val expectedPrompts: Int = if (firstAccepted < 0) callers else firstAccepted + 1

        val port: FakeIosAuthorizationPort = port()
        var prompt = 0
        port.hooks["requestTemporaryFullAccuracy"] = { port.temporaryFullAccuracyAccepted = answers[prompt++] }
        val controller: PermissionController = controller(port)

        val results: List<PermissionStatus> =
            List(callers) { async { controller.request(CommonPermission.Location) } }.map { it.await() }

        assertEquals(expectedPrompts, port.count("requestTemporaryFullAccuracy"), "seed=$seed")
        val expected: List<PermissionStatus> = List(callers) { index ->
            if (firstAccepted in 0..index) PermissionStatus.Granted else PermissionStatus.Limited
        }
        assertEquals(expected, results, "seed=$seed")
    }
}
