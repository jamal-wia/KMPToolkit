@file:OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.permission

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.AVFAudio.AVAudioSessionRecordPermissionDenied
import platform.AVFAudio.AVAudioSessionRecordPermissionGranted
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.AVFoundation.AVAuthorizationStatusDenied
import platform.AVFoundation.AVAuthorizationStatusRestricted
import platform.CoreBluetooth.CBManagerAuthorizationAllowedAlways
import platform.CoreBluetooth.CBManagerAuthorizationDenied
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.CoreLocation.kCLAuthorizationStatusDenied
import platform.EventKit.EKAuthorizationStatusDenied
import platform.EventKit.EKAuthorizationStatusFullAccess
import platform.EventKit.EKAuthorizationStatusWriteOnly
import platform.UserNotifications.UNAuthorizationStatus
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied

/**
 * Breaker round 2 of group logging-permission (PERM-1), attacking the round 1 fix of the iOS batch: every entry of
 * `request(Collection)` is now answered from a status read after the last prompt. Derived from the commonMain KDoc of
 * `PermissionController.request(Collection)` ("returns every one's final status", "a permission listed twice is
 * requested once", cancellation mid-batch) and `docs/kmptoolkit-permission/04-api-reference.md` (batches, requests
 * are serialized, the iOS queue is freed the moment the caller is cancelled).
 *
 * Attacks: duplicates and distinct entries that share one iOS authorization; a write-only calendar entry listed
 * before the full one (a later prompt upgrades an earlier entry, like the location pair); a second batch launched
 * while the first is prompting (re-entrancy, no prompt asked twice, `check` never waits); a batch cancelled while its
 * final status read is pending (the only read that suspends, notifications), and the late answer of that read; an
 * entry restricted behind the app while a later prompt is up; a seeded storm over ten entries with duplicates,
 * random starting states, random answers and entries changed behind the app.
 *
 * Scripted through [FakeIosAuthorizationPort] on virtual time; every random choice comes from the seed in the message.
 */
class PermissionBatchBreakerR2Test {

    private fun controller(port: IosAuthorizationPort): PermissionController =
        IosPermissionController(NoopLogger, IosPermissionOptions(), { true }, port)

    @Test
    fun `duplicates and entries sharing one authorization are prompted once and keyed in first-seen order`() = runTest {
        val port = FakeIosAuthorizationPort().apply {
            hooks["requestBluetooth"] = { bluetooth = CBManagerAuthorizationAllowedAlways }
        }
        val batch: List<Permission> = listOf(
            CommonPermission.BluetoothScan,
            CommonPermission.BluetoothConnect,
            CommonPermission.BluetoothScan,
            CommonPermission.BluetoothAdvertise,
            CommonPermission.BluetoothConnect,
        )
        val result: Map<Permission, PermissionStatus> = controller(port).request(batch)

        assertEquals(1, port.count("requestBluetooth"), "one authorization, asked more than once: ${port.calls}")
        assertEquals(
            listOf<Permission>(CommonPermission.BluetoothScan, CommonPermission.BluetoothConnect, CommonPermission.BluetoothAdvertise),
            result.keys.toList(),
        )
        assertTrue(result.values.all { it == PermissionStatus.Granted }, "an entry kept a stale answer: $result")
    }

    @Test
    fun `a write-only calendar entry listed before the full one is answered with the access the later prompt gave`() = runTest {
        val accepted = FakeIosAuthorizationPort().apply {
            hooks["requestCalendar(writeOnly=true)"] = { calendar = EKAuthorizationStatusWriteOnly }
            hooks["requestCalendar(writeOnly=false)"] = { calendar = EKAuthorizationStatusFullAccess }
        }
        val both: Map<Permission, PermissionStatus> =
            controller(accepted).request(listOf(CommonPermission.CalendarWriteOnly, CommonPermission.Calendar))
        assertEquals(1, accepted.count("requestCalendar(writeOnly=true)"))
        assertEquals(1, accepted.count("requestCalendar(writeOnly=false)"))
        assertEquals(PermissionStatus.Granted, both[CommonPermission.CalendarWriteOnly])
        assertEquals(PermissionStatus.Granted, both[CommonPermission.Calendar])

        // The user then turns calendar access off in Settings while a later prompt of the next batch is up.
        val revoked = FakeIosAuthorizationPort().apply {
            calendar = EKAuthorizationStatusFullAccess
            hooks["requestCameraAccess"] = {
                camera = AVAuthorizationStatusAuthorized
                calendar = EKAuthorizationStatusDenied
            }
        }
        val controller: PermissionController = controller(revoked)
        val after: Map<Permission, PermissionStatus> =
            controller.request(listOf(CommonPermission.Calendar, CommonPermission.CalendarWriteOnly, CommonPermission.Camera))
        assertEquals(PermissionStatus.Granted, after[CommonPermission.Camera], "control: the later prompt was answered")
        assertEquals(PermissionStatus.PermanentlyDenied, after[CommonPermission.Calendar])
        assertEquals(PermissionStatus.PermanentlyDenied, after[CommonPermission.CalendarWriteOnly])
    }

    /**
     * A second batch made while the first one's camera prompt is up waits for it (requests are serialized), `check`
     * answers at once meanwhile, and once the first batch ends the second one asks nothing again and answers from the
     * same final statuses. Mutation by reading: a batch that drops the queue before its final reads, or a final read
     * that takes the queue, turns this red (the second batch prompts, or the first never ends).
     */
    @Test
    fun `a second batch made while the first is prompting waits and asks nothing twice and check never waits`() = runTest {
        val port = FakeIosAuthorizationPort().apply {
            holds += "requestCameraAccess"
            hooks["requestRecordPermission"] = { recordState = AVAudioSessionRecordPermissionGranted }
        }
        val controller: PermissionController = controller(port)
        val first: Deferred<Map<Permission, PermissionStatus>> =
            async { controller.request(listOf(CommonPermission.Camera, CommonPermission.Microphone)) }
        runCurrent()
        val release: (Boolean) -> Unit = assertNotNull(port.heldCompletions["requestCameraAccess"], "control: the camera prompt is up")
        val second: Deferred<Map<Permission, PermissionStatus>> =
            async { controller.request(listOf(CommonPermission.Microphone, CommonPermission.Camera, CommonPermission.Microphone)) }
        runCurrent()
        assertFalse(second.isCompleted, "the second batch ran while the first one's prompt was up")
        assertEquals(0, port.count("requestRecordPermission"), "a prompt overlapped the camera prompt")
        assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.Camera), "check waited for the batch")

        port.camera = AVAuthorizationStatusAuthorized
        release(true)
        runCurrent()

        val firstResult: Map<Permission, PermissionStatus> = first.await()
        val secondResult: Map<Permission, PermissionStatus> = second.await()
        assertEquals(mapOf<Permission, PermissionStatus>(CommonPermission.Camera to PermissionStatus.Granted, CommonPermission.Microphone to PermissionStatus.Granted), firstResult)
        assertEquals(listOf<Permission>(CommonPermission.Microphone, CommonPermission.Camera), secondResult.keys.toList())
        assertTrue(secondResult.values.all { it == PermissionStatus.Granted }, "second: $secondResult")
        assertEquals(1, port.count("requestCameraAccess"))
        assertEquals(1, port.count("requestRecordPermission"))
    }

    /** Holds the notification status read once [armed], the one read iOS answers only through a callback. */
    private class HeldNotificationReads(private val inner: FakeIosAuthorizationPort) : IosAuthorizationPort by inner {
        var armed: Boolean = false
        val held: MutableList<(UNAuthorizationStatus?) -> Unit> = mutableListOf()

        override fun notificationAuthorization(completion: (UNAuthorizationStatus?) -> Unit) {
            if (armed) held += completion else inner.notificationAuthorization(completion)
        }
    }

    /**
     * The batch has asked everything and is reading the final statuses; the notification read is pending when the
     * caller is cancelled. The queue must be free at once (the iOS rule), the late answer of the abandoned read must
     * be ignored without a crash, and the next batch must be answered whole without asking anything again.
     */
    @Test
    fun `a batch cancelled while its final status read is pending frees the queue and ignores the late answer`() = runTest {
        val inner = FakeIosAuthorizationPort().apply { notification = UNAuthorizationStatusAuthorized }
        val port = HeldNotificationReads(inner)
        inner.hooks["requestCameraAccess"] = {
            inner.camera = AVAuthorizationStatusAuthorized
            port.armed = true // from here on, only the final reads of the batch are left
        }
        val controller: PermissionController = controller(port)

        val cancelled: Deferred<Map<Permission, PermissionStatus>> =
            async { controller.request(listOf(CommonPermission.Notifications, CommonPermission.Camera)) }
        runCurrent()
        assertEquals(1, port.held.size, "control: the final notification read is pending")
        assertEquals(1, inner.count("requestCameraAccess"), "control: the camera was asked before the final reads")
        cancelled.cancel()
        runCurrent()
        assertTrue(cancelled.isCancelled)

        port.armed = false
        port.held.single().invoke(UNAuthorizationStatusAuthorized) // the late answer of the abandoned read

        val next: Map<Permission, PermissionStatus> =
            controller.request(listOf(CommonPermission.Camera, CommonPermission.Notifications))
        assertEquals(
            mapOf<Permission, PermissionStatus>(CommonPermission.Camera to PermissionStatus.Granted, CommonPermission.Notifications to PermissionStatus.Granted),
            next,
        )
        assertEquals(1, inner.count("requestCameraAccess"), "the next batch asked the camera again")
        assertEquals(0, inner.count("requestNotifications"), "a granted notification authorization was asked")
    }

    @Test
    fun `an entry restricted behind the app while a later location prompt is up is answered Restricted`() = runTest {
        val port = FakeIosAuthorizationPort().apply {
            hooks["requestCameraAccess"] = { camera = AVAuthorizationStatusAuthorized }
            hooks["requestWhenInUse"] = {
                location = kCLAuthorizationStatusAuthorizedWhenInUse
                camera = AVAuthorizationStatusRestricted // a Screen Time restriction applied meanwhile
            }
        }
        val controller: PermissionController = controller(port)
        val result: Map<Permission, PermissionStatus> =
            controller.request(listOf(CommonPermission.Camera, CommonPermission.Location))
        assertEquals(PermissionStatus.Granted, result[CommonPermission.Location], "control: the later prompt was answered")
        assertEquals(PermissionStatus.Restricted, result[CommonPermission.Camera])
        assertEquals(controller.check(CommonPermission.Camera), result[CommonPermission.Camera])
    }

    /**
     * Seeded storm: batches drawn with duplicates from ten entries (three of which share authorizations with another),
     * from random starting states, the user answering every prompt at random, and every prompt possibly changing an
     * unrelated entry behind the app. After every batch: the keys are the distinct entries in first-seen order, every
     * status equals what `check` reads right after, and no platform request was made twice in one batch.
     */
    @Test
    fun `a seeded storm of batches answers every entry with its final status and asks nothing twice`() = runTest {
        val seed = 20_261_122
        val pool: List<Permission> = listOf(
            CommonPermission.Camera,
            CommonPermission.Microphone,
            CommonPermission.Location,
            CommonPermission.LocationBackground,
            CommonPermission.BluetoothScan,
            CommonPermission.BluetoothConnect,
            CommonPermission.Calendar,
            CommonPermission.CalendarWriteOnly,
            CommonPermission.Notifications,
            CommonPermission.Camera,
        )
        repeat(60) { round: Int ->
            val random = Random(seed * 1000 + round)
            val port = FakeIosAuthorizationPort()
            val behindTheApp: List<FakeIosAuthorizationPort.() -> Unit> = listOf(
                { camera = AVAuthorizationStatusDenied },
                { recordState = AVAudioSessionRecordPermissionDenied },
                { bluetooth = CBManagerAuthorizationDenied },
                { calendar = EKAuthorizationStatusDenied },
                { location = kCLAuthorizationStatusDenied },
                { notification = UNAuthorizationStatusDenied },
            )
            fun answer(grant: FakeIosAuthorizationPort.() -> Unit, deny: FakeIosAuthorizationPort.() -> Unit): () -> Unit = {
                if (random.nextBoolean()) port.grant() else port.deny()
                if (random.nextInt(4) == 0) behindTheApp[random.nextInt(behindTheApp.size)].invoke(port)
            }
            port.apply {
                if (random.nextBoolean()) camera = AVAuthorizationStatusAuthorized
                if (random.nextBoolean()) location = kCLAuthorizationStatusAuthorizedWhenInUse
                if (random.nextBoolean()) calendar = EKAuthorizationStatusWriteOnly
                cameraGrant = random.nextBoolean()
                microphoneGrant = random.nextBoolean()
                hooks["requestCameraAccess"] = answer({ camera = AVAuthorizationStatusAuthorized }, { camera = AVAuthorizationStatusDenied })
                hooks["requestRecordPermission"] =
                    answer({ recordState = AVAudioSessionRecordPermissionGranted }, { recordState = AVAudioSessionRecordPermissionDenied })
                hooks["requestWhenInUse"] =
                    answer({ location = kCLAuthorizationStatusAuthorizedWhenInUse }, { location = kCLAuthorizationStatusDenied })
                hooks["requestAlways"] = answer({ location = kCLAuthorizationStatusAuthorizedAlways }, { alwaysUpgradeAsked = true })
                hooks["requestBluetooth"] =
                    answer({ bluetooth = CBManagerAuthorizationAllowedAlways }, { bluetooth = CBManagerAuthorizationDenied })
                hooks["requestCalendar(writeOnly=true)"] =
                    answer({ calendar = EKAuthorizationStatusWriteOnly }, { calendar = EKAuthorizationStatusDenied })
                hooks["requestCalendar(writeOnly=false)"] = answer({ calendar = EKAuthorizationStatusFullAccess }, {})
                hooks["requestNotifications"] =
                    answer({ notification = UNAuthorizationStatusAuthorized }, { notification = UNAuthorizationStatusDenied })
            }
            val controller: PermissionController = controller(port)
            val batch: List<Permission> = List(random.nextInt(1, 9)) { pool[random.nextInt(pool.size)] }

            val callsBefore: Int = port.calls.size
            val result: Map<Permission, PermissionStatus> = controller.request(batch)
            val requests: List<String> = port.calls.drop(callsBefore).filter { it.startsWith("request") }

            assertEquals(batch.distinct(), result.keys.toList(), "seed=$seed round=$round batch=$batch")
            result.forEach { (permission: Permission, status: PermissionStatus) ->
                assertEquals(controller.check(permission), status, "seed=$seed round=$round batch=$batch permission=$permission calls=$requests")
            }
            assertEquals(requests.distinct(), requests, "seed=$seed round=$round batch=$batch: a platform request was made twice")
        }
    }
}
