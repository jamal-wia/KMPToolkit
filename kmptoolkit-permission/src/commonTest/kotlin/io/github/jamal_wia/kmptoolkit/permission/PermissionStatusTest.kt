package io.github.jamal_wia.kmptoolkit.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The two derived questions [PermissionStatus] answers, and the catalog's stated invariants. */
class PermissionStatusTest {

    @Test
    fun `only granted is granted`() {
        assertTrue(PermissionStatus.Granted.isGranted)
        assertFalse(PermissionStatus.PermanentlyDenied.isGranted)
        assertFalse(PermissionStatus.NotDetermined.isGranted)
        assertFalse(PermissionStatus.Denied().isGranted)
        assertFalse(PermissionStatus.Denied(explainFirst = true).isGranted)
        assertFalse(PermissionStatus.Limited.isGranted)
        assertFalse(PermissionStatus.Restricted.isGranted)
        assertFalse(PermissionStatus.Unavailable.isGranted)
    }

    @Test
    fun `granted and limited are usable and nothing else is`() {
        assertTrue(PermissionStatus.Granted.isUsable)
        assertTrue(PermissionStatus.Limited.isUsable)
        listOf(
            PermissionStatus.Denied(),
            PermissionStatus.Denied(explainFirst = true),
            PermissionStatus.PermanentlyDenied,
            PermissionStatus.NotDetermined,
            PermissionStatus.Restricted,
            PermissionStatus.Unavailable,
        ).forEach { status -> assertFalse(status.isUsable, "status=$status") }
    }

    @Test
    fun `prompting is possible exactly while the platform would still show a dialog`() {
        assertTrue(PermissionStatus.NotDetermined.canPrompt)
        assertTrue(PermissionStatus.Denied().canPrompt)
        assertTrue(PermissionStatus.Denied(explainFirst = true).canPrompt)
        assertFalse(PermissionStatus.Granted.canPrompt)
        assertFalse(PermissionStatus.PermanentlyDenied.canPrompt)
        assertFalse(PermissionStatus.Restricted.canPrompt)
        assertFalse(PermissionStatus.Unavailable.canPrompt)
        // Whether a limited grant can be widened by a dialog depends on the platform and the entry.
        assertFalse(PermissionStatus.Limited.canPrompt)
    }

    @Test
    fun `a denial does not ask for a rationale unless told to`() {
        assertFalse(PermissionStatus.Denied().explainFirst)
    }

    @Test
    fun `the two denial cases are distinct values`() {
        assertTrue(PermissionStatus.Denied(explainFirst = true) != PermissionStatus.PermanentlyDenied)
        assertEquals(PermissionStatus.Denied(true), PermissionStatus.Denied(true))
    }

    @Test
    fun `every 2_x entry keeps its 2_x name as its id so stored flags survive the upgrade`() {
        // Every 2.x consumer has flags stored under these exact strings.
        assertEquals(
            mapOf(
                CommonPermission.Notifications to "NOTIFICATIONS",
                CommonPermission.Microphone to "MICROPHONE",
                CommonPermission.Camera to "CAMERA",
                CommonPermission.Location to "LOCATION",
                CommonPermission.LocationBackground to "LOCATION_BACKGROUND",
                CommonPermission.AudioLibrary to "MEDIA_AUDIO",
                CommonPermission.BluetoothConnect to "BLUETOOTH_CONNECT",
                CommonPermission.BluetoothScan to "BLUETOOTH_SCAN",
            ),
            CommonPermission.entries.filter { it.id in TWO_X_IDS }.associateWith { it.id },
        )
    }

    @Test
    fun `ids are unique across the common vocabulary`() {
        val ids: List<String> = CommonPermission.entries.map { it.id }

        assertEquals(ids.size, ids.toSet().size, "ids collided: $ids")
    }

    private companion object {
        val TWO_X_IDS: Set<String> = setOf(
            "NOTIFICATIONS", "MICROPHONE", "CAMERA", "LOCATION", "LOCATION_BACKGROUND", "MEDIA_AUDIO",
            "BLUETOOTH_CONNECT", "BLUETOOTH_SCAN",
        )
    }
}
