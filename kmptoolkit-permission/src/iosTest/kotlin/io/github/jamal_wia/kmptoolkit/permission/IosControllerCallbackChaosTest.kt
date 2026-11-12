@file:OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class, ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.permission

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UIKit.UIApplicationOpenSettingsURLString

/**
 * Round 9 (E1, bm): the iOS controller against a platform that answers badly: a completion delivered twice,
 * late, never, from another thread, from inside a consumer's collector; two controllers over one platform;
 * a platform call that throws; a collector that throws. Every test has a hard bound: `runTest` keeps its default
 * timeout and the real-thread tests run under `withTimeout`.
 */
class IosControllerCallbackChaosTest {

    private fun controller(
        port: ChaosIosAuthorizationPort,
        logger: Logger = NoopLogger,
        hasKey: (String) -> Boolean = { true },
    ): PermissionController = IosPermissionController(logger, IosPermissionOptions(), hasKey, port)

    private fun TestScope.collecting(
        controller: PermissionController,
        permission: Permission,
    ): Pair<Job, MutableList<PermissionStatus>> {
        val seen: MutableList<PermissionStatus> = mutableListOf()
        val job: Job = launch { controller.observe(permission).collect { seen += it } }
        runCurrent()
        return job to seen
    }

    // --- a completion delivered twice ---------------------------------------------------------------

    @Test
    fun `a microphone completion delivered twice resumes the request once with the first answer`() = runTest {
        val port = ChaosIosAuthorizationPort().apply { hold.value = true }
        val controller: PermissionController = controller(port)
        val request: Deferred<PermissionStatus> = async { controller.request(CommonPermission.Microphone) }
        runCurrent()
        val prompt: HeldPrompt = port.heldPrompts.value.single()

        prompt.answer(true)
        prompt.redeliver(false)
        runCurrent()

        assertEquals(PermissionStatus.Granted, request.await())
        assertEquals(PermissionStatus.Granted, controller.check(CommonPermission.Microphone))
        assertEquals(1, port.count("requestRecordPermission"))
    }

    @Test
    fun `a camera completion delivered twice resumes the request once with the first answer`() = runTest {
        val port = ChaosIosAuthorizationPort().apply { hold.value = true }
        val controller: PermissionController = controller(port)
        val request: Deferred<PermissionStatus> = async { controller.request(CommonPermission.Camera) }
        runCurrent()
        val prompt: HeldPrompt = port.heldPrompts.value.single()

        prompt.answer(false)
        prompt.redeliver(true)
        prompt.redeliver(true)
        runCurrent()

        assertEquals(PermissionStatus.PermanentlyDenied, request.await())
        assertEquals(PermissionStatus.PermanentlyDenied, controller.check(CommonPermission.Camera))
    }

    @Test
    fun `a notification request completion delivered twice re-reads the status once`() = runTest {
        val port = ChaosIosAuthorizationPort().apply { hold.value = true }
        val controller: PermissionController = controller(port)
        val request: Deferred<PermissionStatus> = async { controller.request(CommonPermission.Notifications) }
        runCurrent()
        val prompt: HeldPrompt = port.heldPrompts.value.single()

        prompt.answer(true)
        runCurrent()
        val readsAfterAnswer: Int = port.count("notificationAuthorization")
        prompt.redeliver(true)
        prompt.redeliver(false)
        runCurrent()

        assertEquals(PermissionStatus.Granted, request.await())
        assertEquals(readsAfterAnswer, port.count("notificationAuthorization"), "a duplicate must not read again")
    }

    @Test
    fun `a notification status read answered twice with different statuses uses the first`() = runTest {
        val port = ChaosIosAuthorizationPort().apply { hangNotificationReads.value = true }
        val controller: PermissionController = controller(port)
        val check: Deferred<PermissionStatus> = async { controller.check(CommonPermission.Notifications) }
        runCurrent()
        val read: (platform.UserNotifications.UNAuthorizationStatus?) -> Unit = port.heldNotificationReads.value.single()

        read(UNAuthorizationStatusAuthorized)
        read(UNAuthorizationStatusDenied)
        read(null)
        runCurrent()

        assertEquals(PermissionStatus.Granted, check.await())
    }

    // --- a completion never delivered ----------------------------------------------------------------

    @Test
    fun `a request stuck on a notification read that never answers blocks the queue until it is cancelled`() = runTest {
        val port = ChaosIosAuthorizationPort().apply { hangNotificationReads.value = true }
        val controller: PermissionController = controller(port)
        val stuck: Job = launch { controller.request(CommonPermission.Notifications) }
        runCurrent()
        val next: Deferred<PermissionStatus> = async { controller.request(CommonPermission.Camera) }
        runCurrent()

        assertTrue(stuck.isActive)
        assertEquals(0, port.count("cameraAuthorization"), "B waits behind the stuck request")
        // check is not serialized: it answers while a request is stuck.
        assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.Camera))

        stuck.cancel()
        runCurrent()

        assertEquals(PermissionStatus.Granted, next.await(), "cancelling the stuck request frees the queue")
        // The status read answers late: nothing resumes, nothing throws.
        port.heldNotificationReads.value.forEach { it(UNAuthorizationStatusAuthorized) }
        runCurrent()
        assertFalse(stuck.isActive)
    }

    // --- a completion delivered late, after another request began ------------------------------------

    @Test
    fun `a late completion of a cancelled prompt does not resolve the next request for the same permission`() = runTest {
        val port = ChaosIosAuthorizationPort().apply { hold.value = true }
        val controller: PermissionController = controller(port)
        val a: Job = launch { controller.request(CommonPermission.Camera) }
        runCurrent()
        a.cancel()
        runCurrent()
        val b: Deferred<PermissionStatus> = async { controller.request(CommonPermission.Camera) }
        runCurrent()
        assertEquals(2, port.heldPrompts.value.size, "the second request raised its own prompt")
        val (late: HeldPrompt, own: HeldPrompt) = port.heldPrompts.value

        late.answer(true)
        runCurrent()

        assertTrue(b.isActive, "A's late answer must not resume B")

        own.answer(false)
        runCurrent()
        // The status the platform now holds is the last answer (denied): B reports its own prompt's answer.
        assertEquals(PermissionStatus.PermanentlyDenied, b.await())
    }

    // --- re-entrant requests from the consumer's own collector ---------------------------------------

    @Test
    fun `a collector that asks again from inside its emission neither deadlocks nor prompts twice`() = runTest {
        val port = ChaosIosAuthorizationPort()
        val controller: PermissionController = controller(port)
        val answers: MutableList<PermissionStatus> = mutableListOf()
        val job: Job = launch {
            controller.observe(CommonPermission.Camera).collect { status ->
                if (status !is PermissionStatus.NotDetermined) {
                    answers += controller.request(CommonPermission.Camera)
                    answers += controller.request(CommonPermission.Microphone)
                    assertTrue(controller.openSettings(CommonPermission.Camera))
                }
            }
        }
        runCurrent()

        controller.request(CommonPermission.Camera)
        runCurrent()

        assertEquals<List<PermissionStatus>>(listOf(PermissionStatus.Granted, PermissionStatus.Granted), answers)
        assertEquals(1, port.count("requestCameraAccess"), "the nested request found the permission decided")
        assertEquals(1, port.count("requestRecordPermission"))
        job.cancel()
    }

    // --- two controllers over one platform -----------------------------------------------------------

    @Test
    fun `two controllers each raise their own prompt and an observer sees the other's answer only at activation`() = runTest {
        val port = ChaosIosAuthorizationPort().apply { hold.value = true }
        val first: PermissionController = controller(port)
        val second: PermissionController = controller(port)
        val (job, seen) = collecting(second, CommonPermission.Camera)

        val a: Deferred<PermissionStatus> = async { first.request(CommonPermission.Camera) }
        val b: Deferred<PermissionStatus> = async { second.request(CommonPermission.Camera) }
        runCurrent()
        assertEquals(2, port.count("requestCameraAccess"), "the mutex is per controller")

        port.heldPrompts.value.forEach { it.answer(true) }
        runCurrent()

        assertEquals(PermissionStatus.Granted, a.await())
        assertEquals(PermissionStatus.Granted, b.await())
        // `second` made its own request, so its observer re-read; a third party's answer shows at activation.
        assertEquals(listOf(PermissionStatus.NotDetermined, PermissionStatus.Granted), seen)
        job.cancel()
    }

    @Test
    fun `an observer of another controller does not re-read after a request through the first one`() = runTest {
        val port = ChaosIosAuthorizationPort()
        val first: PermissionController = controller(port)
        val second: PermissionController = controller(port)
        val (job, seen) = collecting(second, CommonPermission.Camera)

        first.request(CommonPermission.Camera)
        runCurrent()
        assertEquals(listOf<PermissionStatus>(PermissionStatus.NotDetermined), seen)

        port.activate()
        runCurrent()
        assertEquals(listOf(PermissionStatus.NotDetermined, PermissionStatus.Granted), seen)
        job.cancel()
    }

    // --- a collector that throws, a platform that throws ----------------------------------------------

    @Test
    fun `a collector that throws on its first emission unregisters the listener and leaves the controller usable`() = runTest {
        val port = ChaosIosAuthorizationPort()
        val controller: PermissionController = controller(port)

        assertFailsWith<IllegalStateException> {
            controller.observe(CommonPermission.Camera).collect { throw IllegalStateException("collector failed") }
        }

        assertEquals(1, port.registered.value)
        assertEquals(port.registered.value, port.unregistered.value)
        assertEquals(0, port.liveListeners)
        assertEquals(PermissionStatus.Granted, controller.request(CommonPermission.Camera))
        port.activate()
        runCurrent()
    }

    @Test
    fun `a platform read that throws while observing fails the flow and still unregisters`() = runTest {
        val port = ChaosIosAuthorizationPort()
        val controller: PermissionController = controller(port)
        val seen: MutableList<PermissionStatus> = mutableListOf()
        var failure: Throwable? = null
        val job: Job = launch {
            try {
                controller.observe(CommonPermission.Camera).collect { seen += it }
            } catch (cause: IllegalStateException) {
                failure = cause
            }
        }
        runCurrent()
        assertEquals(listOf<PermissionStatus>(PermissionStatus.NotDetermined), seen)

        port.throwOn.value = "cameraAuthorization"
        port.activate()
        runCurrent()

        assertNotNull(failure, "the failure reaches the collector")
        assertTrue(job.isCompleted)
        assertEquals(port.registered.value, port.unregistered.value)
        assertEquals(0, port.liveListeners)

        port.throwOn.value = null
        assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.Camera))
    }

    @Test
    fun `a platform call that throws mid request releases the queue`() = runTest {
        val port = ChaosIosAuthorizationPort().apply { throwOn.value = "requestWhenInUse" }
        val controller: PermissionController = controller(port)

        assertFailsWith<IllegalStateException> { controller.request(CommonPermission.Location) }

        port.throwOn.value = null
        assertEquals(PermissionStatus.Granted, controller.request(CommonPermission.Camera), "the queue is free again")
    }

    @Test
    fun `a batch cut short by a platform failure does not ask the decided ones again on retry`() = runTest {
        val port = ChaosIosAuthorizationPort().apply { throwOn.value = "requestWhenInUse" }
        val controller: PermissionController = controller(port)
        val batch: List<Permission> =
            listOf(CommonPermission.Camera, CommonPermission.Location, CommonPermission.Microphone)

        assertFailsWith<IllegalStateException> { controller.request(batch) }
        assertEquals(1, port.count("requestCameraAccess"))
        assertEquals(0, port.count("requestRecordPermission"), "the batch stopped at the failure")

        port.throwOn.value = null
        val result: Map<Permission, PermissionStatus> = controller.request(batch)

        assertEquals(1, port.count("requestCameraAccess"), "camera was decided by the first attempt")
        assertEquals(1, port.count("requestRecordPermission"))
        assertEquals(batch, result.keys.toList())
        assertEquals(PermissionStatus.Granted, result.getValue(CommonPermission.Location))
    }

    @Test
    fun `a failing settings page read leaves openSettings answering false and logs it once per call`() {
        val port = ChaosIosAuthorizationPort().apply { openResult.value = false }
        val logger = ListLogger()
        val controller: PermissionController = controller(port, logger)

        assertFalse(controller.openSettings(CommonPermission.Camera))
        assertFalse(controller.openSettings(CommonPermission.Camera))

        assertEquals(2, port.openedUrls.value.size)
        assertEquals(2, logger.messages.count { it.contains("cannot be opened") })
        assertEquals(UIApplicationOpenSettingsURLString, port.openedUrls.value.first())
    }

    // --- shapes that need no platform call ------------------------------------------------------------

    private object CustomPermission : Permission {
        override val id: String = "CUSTOM"
    }

    @Test
    fun `an unknown permission type reads unavailable and touches nothing`() = runTest {
        val port = ChaosIosAuthorizationPort()
        val controller: PermissionController = controller(port)

        assertEquals(PermissionStatus.Unavailable, controller.check(CustomPermission))
        assertEquals(PermissionStatus.Unavailable, controller.request(CustomPermission))
        val batch: Map<Permission, PermissionStatus> =
            controller.request(listOf(CustomPermission, CommonPermission.Camera, CustomPermission))
        assertEquals(listOf<Permission>(CustomPermission, CommonPermission.Camera), batch.keys.toList())
        assertEquals(PermissionStatus.Unavailable, batch.getValue(CustomPermission))
        assertFalse(controller.openSettings(CustomPermission))
        assertEquals(1, port.promptCount(), "only the camera prompted")

        val (job, seen) = collecting(controller, CustomPermission)
        assertEquals(listOf<PermissionStatus>(PermissionStatus.Unavailable), seen)
        port.activate()
        runCurrent()
        assertEquals(listOf<PermissionStatus>(PermissionStatus.Unavailable), seen)
        job.cancel()
    }

    @Test
    fun `an empty batch answers an empty map and raises nothing`() = runTest {
        val port = ChaosIosAuthorizationPort()
        val controller: PermissionController = controller(port)

        assertEquals(emptyMap<Permission, PermissionStatus>(), controller.request(emptyList()))
        assertEquals(0, port.promptCount())
        assertEquals(PermissionStatus.Granted, controller.request(CommonPermission.Camera))
    }

    @Test
    fun `an observer cancelled before it ever started registers nothing and one cancelled right after registers and unregisters`() =
        runTest {
            val port = ChaosIosAuthorizationPort()
            val controller: PermissionController = controller(port)

            val neverStarted: Job = launch { controller.observe(CommonPermission.Camera).collect { } }
            neverStarted.cancel()
            runCurrent()
            assertEquals(0, port.registered.value)

            val started: Job =
                launch(start = CoroutineStart.UNDISPATCHED) { controller.observe(CommonPermission.Camera).collect { } }
            started.cancel()
            runCurrent()

            assertEquals(port.registered.value, port.unregistered.value)
            assertEquals(0, port.liveListeners)
        }

    @Test
    fun `thirty observers of one permission each register once and all unregister`() = runTest {
        val port = ChaosIosAuthorizationPort()
        val controller: PermissionController = controller(port)
        val jobs: List<Job> = List(30) { launch { controller.observe(CommonPermission.Microphone).collect { } } }
        runCurrent()
        assertEquals(30, port.liveListeners)

        controller.request(CommonPermission.Microphone)
        port.activate()
        runCurrent()
        jobs.forEach { it.cancel() }
        runCurrent()

        assertEquals(0, port.liveListeners)
        assertEquals(30, port.unregistered.value)
    }

    // --- every permission through the Settings round trip ---------------------------------------------

    private val roundTrip: List<Permission> = listOf(
        CommonPermission.Notifications, CommonPermission.Microphone, CommonPermission.Camera, CommonPermission.Location,
        CommonPermission.AudioLibrary, CommonPermission.BluetoothConnect, CommonPermission.PhotoLibrary,
        CommonPermission.PhotoLibraryAdd, CommonPermission.Contacts, CommonPermission.Calendar,
        CommonPermission.MotionActivity, IosPermission.Tracking, IosPermission.SpeechRecognition,
        IosPermission.Reminders, IosPermission.FocusStatus,
    )

    @Test
    fun `every permission follows prompt refuse settings grant settings revoke settings reset with one prompt per undecided state`() =
        runTest {
            roundTrip.forEach { permission: Permission ->
                val port = ChaosIosAuthorizationPort().apply { autoGrant.value = false }
                val controller: PermissionController = controller(port)

                assertEquals(PermissionStatus.NotDetermined, controller.check(permission), "$permission start")
                assertEquals(PermissionStatus.PermanentlyDenied, controller.request(permission), "$permission refused")
                assertEquals(1, port.promptCount(), "$permission prompted once")

                port.setState(permission, 1)
                assertEquals(PermissionStatus.Granted, controller.check(permission), "$permission granted in Settings")
                assertEquals(PermissionStatus.Granted, controller.request(permission), "$permission request granted")
                assertEquals(1, port.promptCount(), "$permission: a granted permission raises no prompt")

                port.setState(permission, 2)
                assertEquals(PermissionStatus.PermanentlyDenied, controller.check(permission), "$permission revoked")
                assertEquals(PermissionStatus.PermanentlyDenied, controller.request(permission), "$permission request revoked")
                assertEquals(1, port.promptCount(), "$permission: a refusal raises no prompt")

                port.setState(permission, 0)
                assertEquals(PermissionStatus.NotDetermined, controller.check(permission), "$permission reset")
                port.autoGrant.value = true
                assertEquals(PermissionStatus.Granted, controller.request(permission), "$permission asked again after a reset")
                assertEquals(2, port.promptCount(), "$permission second prompt")
            }
        }

    @Test
    fun `an observer follows the Settings round trip and shows only the changes`() = runTest {
        val port = ChaosIosAuthorizationPort()
        val controller: PermissionController = controller(port)
        val (job, seen) = collecting(controller, CommonPermission.Camera)

        fun step(state: Int) {
            port.setState(CommonPermission.Camera, state)
            port.activate()
            port.activate()
            runCurrent()
        }
        step(2)
        step(2)
        step(1)
        step(0)
        step(1)

        assertEquals(
            listOf(
                PermissionStatus.NotDetermined, PermissionStatus.PermanentlyDenied, PermissionStatus.Granted,
                PermissionStatus.NotDetermined, PermissionStatus.Granted,
            ),
            seen,
        )
        job.cancel()
    }

    // --- real threads ---------------------------------------------------------------------------------

    private suspend fun <T> awaitNotNull(what: String, read: () -> T?): T {
        var value: T? = read()
        while (value == null) {
            delay(2)
            value = read()
        }
        return value
    }

    @Test
    fun `a completion delivered from another thread resumes the request`() = runBlocking {
        withTimeout(30_000) {
            val port = ChaosIosAuthorizationPort().apply { hold.value = true }
            val controller: PermissionController = controller(port)
            val request: Deferred<PermissionStatus> = async(Dispatchers.Default) { controller.request(CommonPermission.Camera) }
            val prompt: HeldPrompt = awaitNotNull("the held prompt") { port.heldPrompts.value.firstOrNull() }

            withContext(Dispatchers.Default) { prompt.answer(true) }

            assertEquals(PermissionStatus.Granted, request.await())
        }
    }

    @Test
    fun `completions racing the cancellation of their callers from four threads leave the controller usable`() = runBlocking {
        withTimeout(60_000) {
            val port = ChaosIosAuthorizationPort().apply { hold.value = true }
            val controller: PermissionController = controller(port)
            repeat(25) {
                val callers: List<Job> = List(4) { index ->
                    launch(Dispatchers.Default) {
                        controller.request(if (index % 2 == 0) CommonPermission.Camera else CommonPermission.Microphone)
                    }
                }
                delay(3)
                val answers: Job = launch(Dispatchers.Default) {
                    port.heldPrompts.value.forEach { it.answer(it.name.hashCode() % 2 == 0) }
                }
                callers.forEachIndexed { index, caller -> if (index % 2 == 1) caller.cancel() }
                joinAll(answers)
                // Whatever the order was, the queue drains: reset the platform and finish what is left.
                port.heldPrompts.value = emptyList()
                port.setState(CommonPermission.Camera, 0)
                port.setState(CommonPermission.Microphone, 0)
                port.hold.value = false
                val finisher: Deferred<PermissionStatus> =
                    async(Dispatchers.Default) { controller.request(CommonPermission.Camera) }
                // Callers still waiting on their held prompt: let them go.
                callers.forEach { it.cancel() }
                assertEquals(PermissionStatus.Granted, finisher.await())
                port.hold.value = true
            }
        }
    }
}
