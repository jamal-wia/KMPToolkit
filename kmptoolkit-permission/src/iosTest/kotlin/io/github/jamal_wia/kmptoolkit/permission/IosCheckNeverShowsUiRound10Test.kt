@file:OptIn(ExperimentalForeignApi::class, ExperimentalCoroutinesApi::class, ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.permission

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * "`check` shows no UI" and "`observe` ... never shows UI" (API reference), for every entry the iOS controller
 * answers for, with a purpose string present and with it missing: no prompt is raised and no URL is opened. A
 * type nobody knows answers `Unavailable` on check and request and `false` on `openSettings`.
 */
private object ForeignPermission : Permission {
    override val id: String = "FOREIGN"
}

class IosCheckNeverShowsUiRound10Test {

    private val entries: List<Permission> = CommonPermission.entries + IosPermission.entries

    @Test
    fun `check and observe of every entry raise no prompt and open nothing with and without purpose strings`() = runTest {
        for (hasKey in listOf(true, false)) {
            val port = ChaosIosAuthorizationPort()
            val controller: PermissionController = IosPermissionController(NoopLogger, IosPermissionOptions(), { hasKey }, port)
            for (entry in entries) {
                controller.check(entry)
                val seen: MutableList<PermissionStatus> = mutableListOf()
                val job: Job = launch { controller.observe(entry).collect { seen += it } }
                runCurrent()
                port.activate()
                runCurrent()
                job.cancel()
                runCurrent()
            }
            assertEquals(0, port.promptCount(), "hasKey=$hasKey: a prompt was raised: ${port.openedUrls.value}")
            assertEquals(emptyList(), port.openedUrls.value, "hasKey=$hasKey")
            assertEquals(0, port.liveListeners, "hasKey=$hasKey: a listener outlived its observer")
        }
    }

    @Test
    fun `every documented id of an iOS-visible entry is the literal the docs list`() {
        assertEquals(
            listOf("TRACKING", "SPEECH_RECOGNITION", "REMINDERS", "FOCUS_STATUS"),
            listOf(IosPermission.Tracking, IosPermission.SpeechRecognition, IosPermission.Reminders, IosPermission.FocusStatus).map { it.id },
        )
        assertEquals(4, IosPermission.entries.size)
        assertEquals(
            listOf(
                "NOTIFICATIONS", "MICROPHONE", "CAMERA", "LOCATION", "LOCATION_BACKGROUND", "MEDIA_AUDIO", "BLUETOOTH_CONNECT",
                "BLUETOOTH_SCAN", "BLUETOOTH_ADVERTISE", "PHOTO_LIBRARY", "PHOTO_LIBRARY_ADD", "CONTACTS", "CALENDAR",
                "CALENDAR_WRITE_ONLY", "MOTION_ACTIVITY",
            ),
            CommonPermission.entries.map { it.id },
        )
    }

    @Test
    fun `a type nobody knows is Unavailable to check and request and openSettings says false`() = runTest {
        val port = ChaosIosAuthorizationPort()
        val controller: PermissionController = IosPermissionController(NoopLogger, IosPermissionOptions(), { true }, port)

        assertEquals(PermissionStatus.Unavailable, controller.check(ForeignPermission))
        assertEquals(PermissionStatus.Unavailable, controller.request(ForeignPermission))
        assertEquals(mapOf<Permission, PermissionStatus>(ForeignPermission to PermissionStatus.Unavailable), controller.request(listOf(ForeignPermission)))
        assertFalse(controller.openSettings(ForeignPermission))
        assertEquals(0, port.promptCount())
        assertEquals(emptyList(), port.openedUrls.value)
    }
}
