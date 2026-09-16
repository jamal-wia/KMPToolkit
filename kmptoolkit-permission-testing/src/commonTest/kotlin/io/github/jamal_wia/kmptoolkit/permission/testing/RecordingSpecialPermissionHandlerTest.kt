package io.github.jamal_wia.kmptoolkit.permission.testing

import io.github.jamal_wia.kmptoolkit.permission.SpecialPermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingSpecialPermissionHandlerTest {

    @Test
    fun `defaults to granted when nothing is scripted`() {
        val handler = RecordingSpecialPermissionHandler()

        assertTrue(handler.isGranted(SpecialPermission.EXACT_ALARM))
    }

    @Test
    fun `setGranted overrides the default for that permission only`() {
        val handler = RecordingSpecialPermissionHandler()

        handler.setGranted(SpecialPermission.OVERLAY, granted = false)

        assertFalse(handler.isGranted(SpecialPermission.OVERLAY))
        assertTrue(handler.isGranted(SpecialPermission.EXACT_ALARM))
    }

    @Test
    fun `isGranted records every call in order`() {
        val handler = RecordingSpecialPermissionHandler()

        handler.isGranted(SpecialPermission.EXACT_ALARM)
        handler.isGranted(SpecialPermission.OVERLAY)

        assertEquals(listOf(SpecialPermission.EXACT_ALARM, SpecialPermission.OVERLAY), handler.checks)
    }

    @Test
    fun `requestViaSettings records and defaults to available`() {
        val handler = RecordingSpecialPermissionHandler()

        val opened = handler.requestViaSettings(SpecialPermission.WRITE_SETTINGS)

        assertTrue(opened)
        assertEquals(listOf(SpecialPermission.WRITE_SETTINGS), handler.requestedViaSettings)
    }

    @Test
    fun `setSettingsAvailable models a screen that cannot be opened`() {
        val handler = RecordingSpecialPermissionHandler()

        handler.setSettingsAvailable(SpecialPermission.ALL_FILES_ACCESS, available = false)

        assertFalse(handler.requestViaSettings(SpecialPermission.ALL_FILES_ACCESS))
    }

    @Test
    fun `clearRecordings drops history but keeps scripted answers`() {
        val handler = RecordingSpecialPermissionHandler()
        handler.setGranted(SpecialPermission.EXACT_ALARM, granted = false)
        handler.isGranted(SpecialPermission.EXACT_ALARM)

        handler.clearRecordings()

        assertTrue(handler.checks.isEmpty())
        assertFalse(handler.isGranted(SpecialPermission.EXACT_ALARM))
    }
}
