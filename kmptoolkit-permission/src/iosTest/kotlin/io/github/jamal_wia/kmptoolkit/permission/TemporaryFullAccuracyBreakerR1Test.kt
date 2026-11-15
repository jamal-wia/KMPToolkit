@file:OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class, BetaInteropApi::class)

package io.github.jamal_wia.kmptoolkit.permission

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.CoreLocation.CLAuthorizationStatus
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSString
import platform.Foundation.create

/**
 * Breaker round 1 of group logging-permission (PERM-1, iOS temporary full accuracy), derived from the contract in
 * `PermissionController` (commonMain KDoc: a batch "returns every one's final status", `check` never shows UI,
 * requests never overlap), `docs/kmptoolkit-permission/03-guide.md` (the Limited table: `request(LocationBackground)`
 * still returns `Limited` at once) and the plan, location PLAN.md 3.6 / 6.6. Everything runs through the scripted
 * [FakeIosAuthorizationPort]; nothing shows a prompt or waits on the main queue, `runTest` keeps its default hang
 * timeout, and every random choice comes from a fixed seed named in the message.
 */
class TemporaryFullAccuracyBreakerR1Test {

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
        IosPermissionController(NoopLogger, IosPermissionOptions(temporaryFullAccuracyPurposeKey = purposeKey), { true }, port)

    // --- torn state between two snapshots ------------------------------------------------------------

    /**
     * "Always" with reduced accuracy: `LocationBackground` reads `Limited` and `Location` reads `Limited`. A batch
     * that lists `LocationBackground` first answers it before the `Location` upgrade, which lifts both entries to
     * `Granted`. The batch contract is "returns every one's final status"; a map that says `Location = Granted` and
     * `LocationBackground = Limited` describes a state iOS cannot be in (Limited background = always + reduced, Granted
     * foreground = authorized + full). Red while the batch keeps the answer read before a later entry changed it.
     */
    @Test
    fun `a batch listing LocationBackground before Location answers LocationBackground with its final status`() = runTest {
        val port: FakeIosAuthorizationPort = port(kCLAuthorizationStatusAuthorizedAlways)
        val controller: PermissionController = controller(port)
        // Control: both entries really start Limited.
        assertEquals(PermissionStatus.Limited, controller.check(CommonPermission.LocationBackground))
        assertEquals(PermissionStatus.Limited, controller.check(CommonPermission.Location))

        val result: Map<Permission, PermissionStatus> =
            controller.request(listOf(CommonPermission.LocationBackground, CommonPermission.Location))

        assertEquals(1, port.count("requestTemporaryFullAccuracy"), "control: the Location entry was upgraded")
        assertEquals(PermissionStatus.Granted, result[CommonPermission.Location])
        assertEquals(
            controller.check(CommonPermission.LocationBackground),
            result[CommonPermission.LocationBackground],
            "the batch answered LocationBackground with a status it no longer had when the batch returned",
        )
    }

    // --- livelock: an observer that upgrades on every Limited ---------------------------------------

    /**
     * A common shared-code pattern: collect `observe(Location)` and call `request(Location)` whenever it reads
     * `Limited`. Every request re-triggers the observers (the request resolved), so a decline must not loop into
     * another prompt: the re-read is still `Limited`, and `observe` emits only on a change.
     */
    @Test
    fun `an observer that requests the upgrade on every Limited emission prompts once per decline and does not loop`() = runTest {
        val port: FakeIosAuthorizationPort = port().apply { temporaryFullAccuracyAccepted = false }
        val controller: PermissionController = controller(port)
        val seen: MutableList<PermissionStatus> = mutableListOf()
        val collector: Job = launch {
            controller.observe(CommonPermission.Location).collect { status ->
                seen += status
                if (status is PermissionStatus.Limited) controller.request(CommonPermission.Location)
            }
        }
        runCurrent()
        assertEquals(1, port.count("requestTemporaryFullAccuracy"), "control: the observer asked once")
        // The app comes back to the foreground twice: the status is re-read, unchanged, and nothing is asked again.
        port.activate()
        runCurrent()
        port.activate()
        runCurrent()
        assertEquals(1, port.count("requestTemporaryFullAccuracy"), "a declined upgrade was asked for again with no change")
        assertEquals(listOf<PermissionStatus>(PermissionStatus.Limited), seen)

        // Accuracy becomes full behind the app's back (Settings): the observer sees Granted and stops asking.
        port.locationReduced = false
        port.activate()
        runCurrent()
        assertEquals(listOf(PermissionStatus.Limited, PermissionStatus.Granted), seen)
        assertEquals(1, port.count("requestTemporaryFullAccuracy"))
        collector.cancel()
        runCurrent()
        assertEquals(1, port.unregistered, "the became-active listener must be released with the collector")
    }

    // --- an in-flight prompt does not block reads; other requests queue behind it --------------------

    @Test
    fun `while the upgrade prompt is up check answers at once and another request waits for the prompt`() = runTest {
        val port: FakeIosAuthorizationPort = port()
        val gate = CompletableDeferred<Unit>()
        port.gates["requestTemporaryFullAccuracy"] = gate
        val controller: PermissionController = controller(port)

        val upgrade: Deferred<PermissionStatus> = async { controller.request(CommonPermission.Location) }
        runCurrent()
        assertEquals(1, port.count("requestTemporaryFullAccuracy"), "control: the prompt is up")

        // A read never waits for a prompt (check never shows UI and takes no request lock).
        val read: Deferred<PermissionStatus> = async { controller.check(CommonPermission.Location) }
        runCurrent()
        assertTrue(read.isCompleted, "check waited for the in-flight prompt")
        assertEquals(PermissionStatus.Limited, read.await())

        // A second request must not overlap the prompt.
        val camera: Deferred<PermissionStatus> = async { controller.request(CommonPermission.Camera) }
        runCurrent()
        assertFalse(camera.isCompleted, "a second request overlapped the upgrade prompt")
        assertEquals(0, port.count("requestCameraAccess"))

        gate.complete(Unit)
        runCurrent()
        assertEquals(PermissionStatus.Granted, upgrade.await())
        camera.await()
        assertEquals(1, port.count("requestCameraAccess"))
    }

    // --- LocationBackground from Limited keeps the 4.x answer, key or not -----------------------------

    @Test
    fun `with the key configured request of LocationBackground from Limited returns Limited at once and asks nothing`() = runTest {
        val port: FakeIosAuthorizationPort = port(kCLAuthorizationStatusAuthorizedAlways)
        val controller: PermissionController = controller(port)

        assertEquals(PermissionStatus.Limited, controller.request(CommonPermission.LocationBackground))
        assertEquals(0, port.count("requestTemporaryFullAccuracy"))
        assertEquals(0, port.count("requestAlways"))
        assertEquals(0, port.count("requestWhenInUse"))
    }

    // --- the purpose key is matched exactly ----------------------------------------------------------

    @Test
    fun `a purpose key that differs only by case or whitespace or an invisible character is a missing key`() = runTest {
        val nearMisses: Set<String> = setOf("navigationkey", " NavigationKey", "NavigationKey ", "Navigation​Key")
        val port: FakeIosAuthorizationPort = port(keys = nearMisses)

        val atCreation: IllegalStateException = assertFailsWith { controller(port) }
        assertTrue(atCreation.message.orEmpty().contains("\"$key\""), "the message must name the key: ${atCreation.message}")
        val direct: IllegalStateException = assertFailsWith { requestTemporaryFullAccuracy(port, key) }
        assertTrue(direct.message.orEmpty().contains("\"$key\""), "the message must name the key: ${direct.message}")
        assertTrue(port.temporaryKeysAsked.isEmpty(), "a key the bundle lacks must never reach iOS")
    }

    // --- cancel racing completion, seeded ------------------------------------------------------------

    /**
     * Each round holds the prompt, then lets a seeded coin pick the order of "the prompt completes" and "the
     * caller is cancelled" inside one scheduler step, on the controller path or the direct function. Whatever
     * the order, nothing but a cancellation reaches the caller, the request lock is free afterwards (the next
     * round's request runs) and the status read after the round is the port's.
     */
    @Test
    fun `cancel racing the completion of the upgrade prompt leaves the controller usable in every seeded order`() = runTest {
        val seed = 20_261_115
        val random = Random(seed)
        val rounds = 40
        val port: FakeIosAuthorizationPort = port()
        val controller: PermissionController = controller(port)
        repeat(rounds) { round ->
            port.locationReduced = true
            port.temporaryFullAccuracyAccepted = random.nextBoolean()
            val gate = CompletableDeferred<Unit>()
            port.gates["requestTemporaryFullAccuracy"] = gate
            val viaController: Boolean = random.nextBoolean()
            val outcome = CompletableDeferred<Throwable?>()
            val caller: Job = launch {
                try {
                    if (viaController) controller.request(CommonPermission.Location) else requestTemporaryFullAccuracy(port, key)
                    outcome.complete(null)
                } catch (t: Throwable) {
                    outcome.complete(t)
                    throw t
                }
            }
            runCurrent()
            assertEquals(round + 1, port.count("requestTemporaryFullAccuracy"), "seed=$seed round=$round: control, the prompt is up")
            if (random.nextBoolean()) {
                gate.complete(Unit)
                caller.cancel()
            } else {
                caller.cancel()
                gate.complete(Unit)
            }
            runCurrent()
            val failure: Throwable? = outcome.await()
            assertTrue(
                failure == null || failure is kotlinx.coroutines.CancellationException,
                "seed=$seed round=$round: the caller got $failure",
            )
            port.gates.remove("requestTemporaryFullAccuracy")
            val expected: PermissionStatus = if (port.locationReduced) PermissionStatus.Limited else PermissionStatus.Granted
            assertEquals(expected, controller.check(CommonPermission.Location), "seed=$seed round=$round")
        }
        // The lock is free at the end: one more request is served.
        port.locationReduced = true
        port.temporaryFullAccuracyAccepted = true
        assertEquals(PermissionStatus.Granted, controller.request(CommonPermission.Location), "seed=$seed")
    }

    // --- the bundle's own dictionary type ------------------------------------------------------------

    /**
     * `Info.plist` gives an immutable `NSDictionary`, returned as `Any?` exactly as `objectForInfoDictionaryKey`
     * returns it; the existing parse tests pass a mutable one typed as `NSMutableDictionary`. `copy()` of it is the
     * immutable class, crossing the same `Any?` boundary.
     */
    @Test
    fun `an immutable dictionary crossing as Any yields its keys including non-ASCII ones`() {
        val mutable = NSMutableDictionary()
        listOf(key, "Навигация", "找店").forEach { name: String ->
            mutable.setObject("text", forKey = NSString.create(string = name))
        }
        val immutable: Any? = mutable.copy()

        assertEquals(setOf(key, "Навигация", "找店"), temporaryUsagePurposeKeys(immutable))
    }
}
