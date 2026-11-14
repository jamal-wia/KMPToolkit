@file:OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class)

package io.github.jamal_wia.kmptoolkit.permission

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.logging.Logger
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import platform.AVFoundation.AVAuthorizationStatusAuthorized
import platform.CoreBluetooth.CBManagerAuthorizationAllowedAlways
import platform.CoreLocation.kCLAuthorizationStatusAuthorizedWhenInUse
import platform.Contacts.CNAuthorizationStatusLimited
import platform.EventKit.EKAuthorizationStatusFullAccess
import platform.EventKit.EKAuthorizationStatusWriteOnly
import platform.Foundation.NSError
import platform.Photos.PHAuthorizationStatusLimited
import platform.UIKit.UIApplicationOpenNotificationSettingsURLString
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusProvisional

/**
 * The iOS controller's own logic, driven through a scripted [IosAuthorizationPort] (docs/kmptoolkit-permission/06-testing.md):
 * the request mutex, the batch de-duplication, the shared Bluetooth and calendar authorizations, the limited-to-full
 * upgrade, the two-step background location request, the missing-purpose-string rule, the Boolean-to-status rule, the
 * re-read after a request, `observe`, and a caller cancelled mid-prompt. The fake completes synchronously or on the
 * test scheduler; nothing waits on the main queue, and `runTest` keeps its default timeout.
 */
class IosPermissionControllerPortTest {

    private fun controller(
        port: FakeIosAuthorizationPort,
        logger: Logger = NoopLogger,
        options: IosPermissionOptions = IosPermissionOptions(),
        hasKey: (String) -> Boolean = { true },
    ): PermissionController = IosPermissionController(logger, options, hasKey, port)

    private fun TestScope.collecting(controller: PermissionController, permission: Permission): Pair<Job, List<PermissionStatus>> {
        val seen: MutableList<PermissionStatus> = mutableListOf()
        val job: Job = launch { controller.observe(permission).collect { seen += it } }
        runCurrent()
        return job to seen
    }

    // --- T1 mutex ---------------------------------------------------------------------------------

    @Test
    fun `a second request reaches the platform only after the first one completed`() = runTest {
        val port = FakeIosAuthorizationPort().apply { holds += "requestCameraAccess" }
        val controller: PermissionController = controller(port)

        val a: Job = launch { controller.request(CommonPermission.Camera) }
        runCurrent()
        val b: Job = launch { controller.request(CommonPermission.Microphone) }
        runCurrent()

        assertEquals(1, port.count("requestCameraAccess"))
        assertEquals(0, port.count("recordPermission"), "B must not read the platform while A holds the prompt")
        assertTrue(b.isActive)

        port.heldCompletions.getValue("requestCameraAccess")(true)
        runCurrent()

        assertTrue(a.isCompleted && b.isCompleted)
        assertTrue(port.calls.indexOf("requestCameraAccess") < port.calls.indexOf("recordPermission"))
        assertEquals(1, port.count("requestRecordPermission"))
    }

    @Test
    fun `a request made while a batch prompt is held waits for the batch`() = runTest {
        val port = FakeIosAuthorizationPort().apply { holds += "requestCameraAccess" }
        val controller: PermissionController = controller(port)

        val batch: Job = launch { controller.request(listOf(CommonPermission.Camera, CommonPermission.Microphone)) }
        runCurrent()
        val single: Job = launch { controller.request(CommonPermission.Contacts) }
        runCurrent()

        assertEquals(0, port.count("contactsAuthorization"), "the single request must queue behind the batch")
        assertTrue(single.isActive)

        port.heldCompletions.getValue("requestCameraAccess")(true)
        runCurrent()

        assertTrue(batch.isCompleted && single.isCompleted)
        assertTrue(port.calls.indexOf("requestRecordPermission") < port.calls.indexOf("contactsAuthorization"))
    }

    // --- T2 batch ---------------------------------------------------------------------------------

    @Test
    fun `a batch asks each distinct permission once and answers in order`() = runTest {
        val port = FakeIosAuthorizationPort()
        val controller: PermissionController = controller(port)

        val result: Map<Permission, PermissionStatus> = controller.request(
            listOf(CommonPermission.Camera, CommonPermission.Camera, CommonPermission.Microphone),
        )

        assertEquals(listOf<Permission>(CommonPermission.Camera, CommonPermission.Microphone), result.keys.toList())
        assertEquals(1, port.count("requestCameraAccess"), "the duplicate must not prompt again")
        assertEquals(1, port.count("requestRecordPermission"))
        assertEquals(PermissionStatus.Granted, result.getValue(CommonPermission.Camera))
    }

    @Test
    fun `scan and connect share one Bluetooth prompt`() = runTest {
        val port = FakeIosAuthorizationPort().apply {
            hooks["requestBluetooth"] = { bluetooth = CBManagerAuthorizationAllowedAlways }
        }
        val controller: PermissionController = controller(port)

        val result: Map<Permission, PermissionStatus> =
            controller.request(listOf(CommonPermission.BluetoothScan, CommonPermission.BluetoothConnect))

        assertEquals(1, port.count("requestBluetooth"))
        assertEquals(PermissionStatus.Granted, result.getValue(CommonPermission.BluetoothScan))
        assertEquals(PermissionStatus.Granted, result.getValue(CommonPermission.BluetoothConnect))
    }

    // --- T3 aliases -------------------------------------------------------------------------------

    @Test
    fun `a Bluetooth scan request re-reads every Bluetooth observer without an activation`() = runTest {
        val port = FakeIosAuthorizationPort().apply {
            hooks["requestBluetooth"] = { bluetooth = CBManagerAuthorizationAllowedAlways }
        }
        val controller: PermissionController = controller(port)
        val observers: List<Pair<Job, List<PermissionStatus>>> =
            listOf(CommonPermission.BluetoothConnect, CommonPermission.BluetoothScan, CommonPermission.BluetoothAdvertise)
                .map { collecting(controller, it) }

        controller.request(CommonPermission.BluetoothScan)
        runCurrent()

        observers.forEach { (job, seen) ->
            assertEquals(listOf(PermissionStatus.NotDetermined, PermissionStatus.Granted), seen)
            job.cancel()
        }
    }

    @Test
    fun `a Bluetooth advertise request re-reads the connect observer without an activation`() = runTest {
        val port = FakeIosAuthorizationPort().apply {
            hooks["requestBluetooth"] = { bluetooth = CBManagerAuthorizationAllowedAlways }
        }
        val controller: PermissionController = controller(port)
        val (job, seen) = collecting(controller, CommonPermission.BluetoothConnect)

        controller.request(CommonPermission.BluetoothAdvertise)
        runCurrent()

        assertEquals(listOf(PermissionStatus.NotDetermined, PermissionStatus.Granted), seen)
        job.cancel()
    }

    @Test
    fun `a write-only calendar request re-reads the calendar observer and the reverse`() = runTest {
        val port = FakeIosAuthorizationPort().apply {
            hooks["requestCalendar(writeOnly=true)"] = { calendar = EKAuthorizationStatusWriteOnly }
            hooks["requestCalendar(writeOnly=false)"] = { calendar = EKAuthorizationStatusFullAccess }
        }
        val controller: PermissionController = controller(port)
        val (calendarJob, calendarSeen) = collecting(controller, CommonPermission.Calendar)
        val (writeOnlyJob, writeOnlySeen) = collecting(controller, CommonPermission.CalendarWriteOnly)

        controller.request(CommonPermission.CalendarWriteOnly)
        runCurrent()

        assertEquals(listOf(PermissionStatus.NotDetermined, PermissionStatus.Limited), calendarSeen)
        assertEquals(listOf(PermissionStatus.NotDetermined, PermissionStatus.Granted), writeOnlySeen)

        // Limited calendar access is upgraded by a full request, and the write-only observer follows it.
        controller.request(CommonPermission.Calendar)
        runCurrent()

        assertEquals(1, port.count("requestCalendar(writeOnly=false)"))
        assertEquals(listOf(PermissionStatus.NotDetermined, PermissionStatus.Limited, PermissionStatus.Granted), calendarSeen)
        calendarJob.cancel()
        writeOnlyJob.cancel()
    }

    @Test
    fun `a full calendar request re-reads the write-only observer without an activation`() = runTest {
        val port = FakeIosAuthorizationPort().apply {
            hooks["requestCalendar(writeOnly=false)"] = { calendar = EKAuthorizationStatusFullAccess }
        }
        val controller: PermissionController = controller(port)
        val (job, seen) = collecting(controller, CommonPermission.CalendarWriteOnly)

        controller.request(CommonPermission.Calendar)
        runCurrent()

        assertEquals(listOf(PermissionStatus.NotDetermined, PermissionStatus.Granted), seen)
        job.cancel()
    }

    // --- T4 limited -------------------------------------------------------------------------------

    @Test
    fun `provisional notifications are asked without the provisional option and then re-read`() = runTest {
        val port = FakeIosAuthorizationPort().apply {
            notification = UNAuthorizationStatusProvisional
            hooks["requestNotifications"] = { notification = UNAuthorizationStatusAuthorized }
        }
        val controller: PermissionController = controller(
            port,
            options = IosPermissionOptions(setOf(IosNotificationOption.Alert, IosNotificationOption.Provisional)),
        )

        assertEquals(PermissionStatus.Granted, controller.request(CommonPermission.Notifications))

        assertEquals(listOf(UNAuthorizationOptionAlert), port.notificationOptionsAsked)
    }

    @Test
    fun `provisional alone while already provisional shows no prompt and re-reads`() = runTest {
        val port = FakeIosAuthorizationPort().apply { notification = UNAuthorizationStatusProvisional }
        val controller: PermissionController = controller(
            port,
            options = IosPermissionOptions(setOf(IosNotificationOption.Provisional)),
        )

        assertEquals(PermissionStatus.Limited, controller.request(CommonPermission.Notifications))

        assertEquals(0, port.count("requestNotifications"))
        assertEquals(2, port.count("notificationAuthorization"), "the check, then the re-read")
    }

    @Test
    fun `limited calendar prompts and limited photo library contacts and approximate location do not`() = runTest {
        val port = FakeIosAuthorizationPort().apply {
            calendar = EKAuthorizationStatusWriteOnly
            photoReadWrite = PHAuthorizationStatusLimited
            contacts = CNAuthorizationStatusLimited
            location = kCLAuthorizationStatusAuthorizedWhenInUse
            locationReduced = true
        }
        val controller: PermissionController = controller(port)

        assertEquals(PermissionStatus.Limited, controller.request(CommonPermission.PhotoLibrary))
        assertEquals(PermissionStatus.Limited, controller.request(CommonPermission.Contacts))
        assertEquals(PermissionStatus.Limited, controller.request(CommonPermission.Location))
        assertEquals(0, port.count("requestPhotoLibrary"))
        assertEquals(0, port.count("requestContacts"))
        assertEquals(0, port.count("requestWhenInUse"))

        controller.request(CommonPermission.Calendar)
        assertEquals(1, port.count("requestCalendar(writeOnly=false)"))
    }

    // --- T5 location ------------------------------------------------------------------------------

    @Test
    fun `background location asks when-in-use first and always only from when-in-use`() = runTest {
        val port = FakeIosAuthorizationPort().apply {
            hooks["requestWhenInUse"] = { location = kCLAuthorizationStatusAuthorizedWhenInUse }
            hooks["requestAlways"] = { alwaysUpgradeAsked = true }
        }
        val controller: PermissionController = controller(port)

        assertEquals(PermissionStatus.Denied(explainFirst = true), controller.request(CommonPermission.LocationBackground))
        assertEquals(1, port.count("requestWhenInUse"))
        assertEquals(0, port.count("requestAlways"), "not determined: only the when-in-use dialog")

        assertEquals(PermissionStatus.PermanentlyDenied, controller.request(CommonPermission.LocationBackground))
        assertEquals(1, port.count("requestAlways"))

        assertEquals(PermissionStatus.PermanentlyDenied, controller.request(CommonPermission.LocationBackground))
        assertEquals(1, port.count("requestAlways"), "the upgrade is asked once; the port's flag says so")
        assertEquals(1, port.count("requestWhenInUse"))
    }

    @Test
    fun `foreground location asks only when-in-use`() = runTest {
        val port = FakeIosAuthorizationPort().apply {
            hooks["requestWhenInUse"] = { location = kCLAuthorizationStatusAuthorizedWhenInUse }
        }
        val controller: PermissionController = controller(port)

        assertEquals(PermissionStatus.Granted, controller.request(CommonPermission.Location))
        assertEquals(0, port.count("requestAlways"))
    }

    // --- T6 Info.plist ----------------------------------------------------------------------------

    @Test
    fun `a missing purpose string reads unavailable touches no authorization and warns once`() = runTest {
        val port = FakeIosAuthorizationPort()
        val logger = ListLogger()
        val controller: PermissionController = controller(port, logger, hasKey = { false })

        repeat(3) {
            assertEquals(PermissionStatus.Unavailable, controller.check(CommonPermission.Camera))
            assertEquals(PermissionStatus.Unavailable, controller.request(CommonPermission.Camera))
        }
        val (job, seen) = collecting(controller, CommonPermission.Camera)
        port.activate()
        runCurrent()

        assertEquals(listOf(PermissionStatus.Unavailable), seen)
        assertEquals(0, port.calls.count { it != "addBecameActiveListener" }, "no authorization call: ${port.calls}")
        assertEquals(1, logger.messages.count { "NSCameraUsageDescription" in it }, "warn once: ${logger.messages}")
        job.cancel()
    }

    @Test
    fun `a request for the location upgrade needs both purpose strings`() = runTest {
        val port = FakeIosAuthorizationPort()
        val controller: PermissionController =
            controller(port, hasKey = { it != "NSLocationAlwaysAndWhenInUseUsageDescription" })

        assertEquals(PermissionStatus.Unavailable, controller.request(CommonPermission.LocationBackground))
        assertEquals(0, port.count("requestWhenInUse"))
        assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.Location))
    }

    @Test
    fun `notifications need no purpose string`() = runTest {
        val port = FakeIosAuthorizationPort().apply { notification = UNAuthorizationStatusAuthorized }
        val controller: PermissionController = controller(port, hasKey = { false })

        assertEquals(PermissionStatus.Granted, controller.check(CommonPermission.Notifications))
    }

    @OptIn(ToolkitInternalApi::class)
    private object Foreign : Permission {
        override val id: String = "FOREIGN"
    }

    @Test
    fun `settings for an unknown permission open nothing and a known one opens the right page`() = runTest {
        val port = FakeIosAuthorizationPort()
        val logger = ListLogger()
        val controller: PermissionController = controller(port, logger)

        assertFalse(controller.openSettings(Foreign))
        assertEquals(0, port.count("openSettingsUrl"))

        assertTrue(controller.openSettings(CommonPermission.Camera))
        assertEquals(listOf(UIApplicationOpenSettingsURLString), port.openedUrls)

        port.openedUrls.clear()
        assertTrue(controller.openSettings(CommonPermission.Notifications))
        val expected: String =
            if (isIosAtLeast(15, 4)) UIApplicationOpenNotificationSettingsURLString else UIApplicationOpenSettingsURLString
        assertEquals(listOf(expected), port.openedUrls)

        port.canOpenUrl = false
        assertFalse(controller.openSettings(CommonPermission.Camera))
        assertTrue(logger.messages.isNotEmpty(), "a settings page that cannot be opened is logged")
    }

    // --- T7 Boolean and errors --------------------------------------------------------------------

    @Test
    fun `a refused microphone or camera prompt is permanently denied and a granted one is granted`() = runTest {
        val refused = FakeIosAuthorizationPort().apply {
            microphoneGrant = false
            cameraGrant = false
        }
        val refusing: PermissionController = controller(refused)
        assertEquals(PermissionStatus.PermanentlyDenied, refusing.request(CommonPermission.Microphone))
        assertEquals(PermissionStatus.PermanentlyDenied, refusing.request(CommonPermission.Camera))

        val granted = FakeIosAuthorizationPort()
        val granting: PermissionController = controller(granted)
        assertEquals(PermissionStatus.Granted, granting.request(CommonPermission.Microphone))
        assertEquals(PermissionStatus.Granted, granting.request(CommonPermission.Camera))
    }

    @Test
    fun `a notification request error is logged and the re-read status is returned`() = runTest {
        val port = FakeIosAuthorizationPort().apply {
            notificationError = NSError.errorWithDomain("test", code = 7, userInfo = null)
            hooks["requestNotifications"] = { notification = UNAuthorizationStatusAuthorized }
        }
        val logger = ListLogger()

        val status: PermissionStatus = controller(port, logger).request(CommonPermission.Notifications)

        assertEquals(PermissionStatus.Granted, status)
        assertEquals(1, logger.messages.count { it.startsWith("Notification authorization failed") }, "${logger.messages}")
    }

    @Test
    fun `a contacts request error is logged and the status is re-read`() = runTest {
        val port = FakeIosAuthorizationPort().apply {
            contactsError = NSError.errorWithDomain("test", code = 3, userInfo = null)
            hooks["requestContacts"] = { contacts = platform.Contacts.CNAuthorizationStatusAuthorized }
        }
        val logger = ListLogger()

        val status: PermissionStatus = controller(port, logger).request(CommonPermission.Contacts)

        assertEquals(PermissionStatus.Granted, status)
        assertEquals(1, logger.messages.count { it.startsWith("Contacts authorization failed") }, "${logger.messages}")
    }

    @Test
    fun `an already decided permission is returned without a prompt`() = runTest {
        val port = FakeIosAuthorizationPort().apply { camera = AVAuthorizationStatusAuthorized }
        val controller: PermissionController = controller(port)

        assertEquals(PermissionStatus.Granted, controller.request(CommonPermission.Camera))
        assertEquals(0, port.count("requestCameraAccess"))
    }

    // --- T8 observe -------------------------------------------------------------------------------

    @Test
    fun `observe re-reads on activation drops repeats and unregisters when cancelled`() = runTest {
        val port = FakeIosAuthorizationPort()
        val controller: PermissionController = controller(port)
        val (job, seen) = collecting(controller, CommonPermission.Camera)

        assertEquals(listOf<PermissionStatus>(PermissionStatus.NotDetermined), seen)
        assertEquals(1, port.becameActiveListeners.size)

        port.activate()
        runCurrent()
        assertEquals(listOf<PermissionStatus>(PermissionStatus.NotDetermined), seen, "an unchanged status is not repeated")

        port.camera = AVAuthorizationStatusAuthorized
        port.activate()
        runCurrent()
        assertEquals(listOf(PermissionStatus.NotDetermined, PermissionStatus.Granted), seen)

        job.cancel()
        runCurrent()
        assertEquals(1, port.unregistered)
        assertTrue(port.becameActiveListeners.isEmpty())
    }

    @Test
    fun `a request through an unrelated entry does not re-read another observer`() = runTest {
        val port = FakeIosAuthorizationPort()
        val controller: PermissionController = controller(port)
        val (job, _) = collecting(controller, CommonPermission.Camera)
        val before: Int = port.count("cameraAuthorization")

        controller.request(CommonPermission.Microphone)
        runCurrent()

        assertEquals(before, port.count("cameraAuthorization"))
        job.cancel()
    }

    // --- T9 cancellation --------------------------------------------------------------------------

    @Test
    fun `a caller cancelled while the prompt is held frees the queue and the late completion is ignored`() = runTest {
        val port = FakeIosAuthorizationPort().apply { holds += "requestCameraAccess" }
        val controller: PermissionController = controller(port)

        val a: Job = launch { controller.request(CommonPermission.Camera) }
        runCurrent()
        val b = async { controller.request(CommonPermission.Microphone) }
        runCurrent()
        assertTrue(b.isActive)

        a.cancel()
        runCurrent()

        assertTrue(a.isCancelled)
        assertTrue(b.isCompleted, "the next request proceeds at once")
        assertEquals(PermissionStatus.Granted, b.await())

        // The platform answers late: nothing resumes, nothing throws, nothing else is asked.
        port.heldCompletions.getValue("requestCameraAccess")(true)
        runCurrent()
        assertEquals(1, port.count("requestCameraAccess"))
        assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.Camera))
    }

    @Test
    fun `a request that is the only waiter on a held platform call completes when the platform answers`() = runTest {
        val port = FakeIosAuthorizationPort().apply { holds += "requestRecordPermission" }
        val controller: PermissionController = controller(port)
        val deferred: CompletableDeferred<PermissionStatus> = CompletableDeferred()

        launch { deferred.complete(controller.request(CommonPermission.Microphone)) }
        runCurrent()
        assertFalse(deferred.isCompleted)

        port.heldCompletions.getValue("requestRecordPermission")(true)
        runCurrent()

        assertEquals(PermissionStatus.Granted, deferred.await())
        assertNull(port.heldCompletions["requestCameraAccess"])
        assertContains(port.calls, "requestRecordPermission")
    }
}
