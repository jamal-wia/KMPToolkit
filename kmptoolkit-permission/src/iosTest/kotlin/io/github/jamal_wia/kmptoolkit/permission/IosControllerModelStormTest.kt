@file:OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class, ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.permission

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Round 9 (E1, bm): a seeded random driver over the iOS controller (every operation in every order: single and batch
 * requests, checks, answers delivered in any order and late, callers cancelled, Settings flips, activations, observers
 * started and cancelled) against an oracle that is the platform's own current value, then the same operations from
 * real threads. After the dust settles the controller must agree with the platform, serve one more request, and hold
 * no listener.
 */
class IosControllerModelStormTest {

    private val permissions: List<Permission> = listOf(
        CommonPermission.Camera, CommonPermission.Microphone, CommonPermission.Contacts, CommonPermission.Notifications,
        CommonPermission.Location, CommonPermission.LocationBackground, CommonPermission.BluetoothScan,
        CommonPermission.BluetoothConnect, CommonPermission.BluetoothAdvertise, CommonPermission.Calendar,
        CommonPermission.CalendarWriteOnly, IosPermission.Tracking, IosPermission.Reminders,
    )

    private fun controller(port: ChaosIosAuthorizationPort): PermissionController =
        IosPermissionController(NoopLogger, IosPermissionOptions(), { true }, port)

    @Test
    fun `seeded random operations in every order leave the controller consistent with the platform`() {
        for (seed in 0 until 60) {
            val rnd = Random(seed)
            val cancelsAllowed: Boolean = seed % 2 == 0
            runTest {
                val port = ChaosIosAuthorizationPort().apply { hold.value = rnd.nextBoolean() }
                val controller: PermissionController = controller(port)
                val requests: MutableList<Job> = mutableListOf()
                val cancelled: MutableSet<Job> = mutableSetOf()
                val observers: MutableList<Pair<Permission, Pair<Job, MutableList<PermissionStatus>>>> = mutableListOf()
                val failures: MutableList<Throwable> = mutableListOf()

                fun track(job: Job) {
                    requests += job
                }

                repeat(70) { step ->
                    when (rnd.nextInt(11)) {
                        0, 1 -> {
                            val permission: Permission = permissions.random(rnd)
                            track(launch { runCatching { controller.request(permission) }.onFailure { if (it !is kotlinx.coroutines.CancellationException) failures += it } })
                        }
                        2 -> {
                            val batch: List<Permission> = List(rnd.nextInt(1, 5)) { permissions.random(rnd) }
                            track(launch { runCatching { controller.request(batch) }.onFailure { if (it !is kotlinx.coroutines.CancellationException) failures += it } })
                        }
                        3 -> {
                            val permission: Permission = permissions.random(rnd)
                            launch {
                                runCatching { controller.check(permission) }.onFailure { if (it !is kotlinx.coroutines.CancellationException) failures += it }
                            }
                        }
                        4 -> {
                            val prompts: List<HeldPrompt> = port.heldPrompts.value
                            if (prompts.isNotEmpty()) prompts.random(rnd).answer(rnd.nextBoolean())
                            if (port.waitingGates > 0) port.openGates(rnd.nextBoolean())
                        }
                        5 -> if (cancelsAllowed && requests.isNotEmpty()) {
                            val victim: Job = requests.random(rnd)
                            cancelled += victim
                            victim.cancel()
                        }
                        6 -> port.setState(permissions.random(rnd), rnd.nextInt(3))
                        7 -> port.activate()
                        8 -> if (observers.size < 6 && rnd.nextBoolean()) {
                            val permission: Permission = permissions.random(rnd)
                            val seen: MutableList<PermissionStatus> = mutableListOf()
                            val job: Job = launch(start = CoroutineStart.UNDISPATCHED) {
                                runCatching { controller.observe(permission).collect { seen += it } }
                                    .onFailure { if (it !is kotlinx.coroutines.CancellationException) failures += it }
                            }
                            observers += permission to (job to seen)
                        } else if (observers.isNotEmpty()) {
                            observers.removeAt(rnd.nextInt(observers.size)).second.first.cancel()
                        }
                        9 -> controller.openSettings(permissions.random(rnd))
                        else -> port.hold.value = rnd.nextBoolean()
                    }
                    runCurrent()
                    assertTrue(failures.isEmpty(), "seed $seed step $step: ${failures.firstOrNull()}")
                }

                // Settle: no more holding, every waiting prompt and gate answered, until every caller is done.
                port.hold.value = false
                var rounds = 0
                while (requests.any { it.isActive } && rounds++ < 400) {
                    port.heldPrompts.value.forEach { it.answer(true) }
                    port.heldPrompts.value = emptyList()
                    port.openGates(true)
                    runCurrent()
                }
                assertTrue(requests.none { it.isActive }, "seed $seed: callers still waiting after the prompts were answered")
                assertTrue(failures.isEmpty(), "seed $seed: $failures")
                if (!cancelsAllowed) assertTrue(port.maxInFlight.value <= 1, "seed $seed: ${port.maxInFlight.value} prompts at once")

                // The controller agrees with the platform, and observers catch up at the next activation.
                port.activate()
                runCurrent()
                permissions.forEach { permission ->
                    assertEquals(port.expectedStatus(permission), controller.check(permission), "seed $seed $permission")
                }
                observers.forEach { (permission, tracked) ->
                    if (tracked.first.isActive) {
                        assertEquals(
                            port.expectedStatus(permission),
                            tracked.second.last(),
                            "seed $seed: observer of $permission after the last activation",
                        )
                    }
                }
                // The queue is free: one more request is served.
                port.setState(CommonPermission.Camera, 0)
                port.autoGrant.value = true
                assertEquals(PermissionStatus.Granted, controller.request(CommonPermission.Camera), "seed $seed final request")
                observers.forEach { it.second.first.cancel() }
                runCurrent()
                assertEquals(0, port.liveListeners, "seed $seed: listeners left")
                assertEquals(port.registered.value, port.unregistered.value, "seed $seed: registrations")
            }
        }
    }

    @Test
    fun `eight threads checking requesting observing and flipping settings at once never overlap prompts or leak listeners`() =
        runBlocking {
            withTimeout(120_000) {
                val port = ChaosIosAuthorizationPort()
                val controller: PermissionController = controller(port)
                val failures: MutableList<Throwable> = mutableListOf()
                val failureLock: Cell<List<Throwable>> = Cell(emptyList())
                val workers: List<Job> = List(8) { worker ->
                    launch(Dispatchers.Default) {
                        val rnd = Random(1000 + worker)
                        repeat(120) {
                            val permission: Permission = permissions.random(rnd)
                            try {
                                when (rnd.nextInt(8)) {
                                    0, 1 -> controller.request(permission)
                                    2 -> controller.request(List(rnd.nextInt(1, 4)) { permissions.random(rnd) })
                                    3 -> controller.check(permission)
                                    4 -> controller.openSettings(permission)
                                    5 -> withTimeoutOrNull(rnd.nextLong(1, 6)) { controller.observe(permission).collect { } }
                                    6 -> port.setState(permission, rnd.nextInt(3))
                                    else -> {
                                        port.activate()
                                        withTimeoutOrNull(rnd.nextLong(1, 4)) { controller.request(permission) }
                                    }
                                }
                            } catch (cause: Throwable) {
                                if (cause is kotlinx.coroutines.CancellationException) throw cause
                                failureLock.update { it + cause }
                            }
                        }
                    }
                }
                joinAll(*workers.toTypedArray())
                failures += failureLock.value
                assertTrue(failures.isEmpty(), "failures: ${failures.firstOrNull()}")
                assertTrue(port.maxInFlight.value <= 1, "${port.maxInFlight.value} prompts at once")
                assertEquals(0, port.liveListeners)
                assertEquals(port.registered.value, port.unregistered.value)
                permissions.forEach { permission ->
                    assertEquals(port.expectedStatus(permission), controller.check(permission), "$permission")
                }
                port.setState(CommonPermission.Camera, 0)
                assertEquals(PermissionStatus.Granted, controller.request(CommonPermission.Camera))
            }
        }

    @Test
    fun `an observer started on one thread and fed activations from three others converges on the platform`() = runBlocking {
        withTimeout(60_000) {
            val port = ChaosIosAuthorizationPort()
            val controller: PermissionController = controller(port)
            val last: Cell<PermissionStatus?> = Cell(null)
            val observer: Job = launch(Dispatchers.Default) {
                controller.observe(CommonPermission.Camera).collect { last.value = it }
            }
            val pumps: List<Job> = List(3) { index ->
                launch(Dispatchers.Default) {
                    repeat(150) { round ->
                        port.setState(CommonPermission.Camera, (index + round) % 3)
                        port.activate()
                    }
                }
            }
            joinAll(*pumps.toTypedArray())
            port.setState(CommonPermission.Camera, 1)
            port.activate()
            // conflate + distinct: the last emission must reach the final status.
            while (last.value != PermissionStatus.Granted) delay(2)
            observer.cancel()
            observer.join()
            assertEquals(0, port.liveListeners)
        }
    }
}
