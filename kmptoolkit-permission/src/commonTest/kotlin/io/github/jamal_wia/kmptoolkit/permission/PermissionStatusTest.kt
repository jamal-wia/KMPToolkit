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
        assertFalse(PermissionStatus.Denied(shouldShowRationale = true).isGranted)
    }

    @Test
    fun `prompting is possible exactly while the platform would still show a dialog`() {
        assertTrue(PermissionStatus.NotDetermined.canPrompt)
        assertTrue(PermissionStatus.Denied().canPrompt)
        assertTrue(PermissionStatus.Denied(shouldShowRationale = true).canPrompt)
        assertFalse(PermissionStatus.Granted.canPrompt)
        assertFalse(PermissionStatus.PermanentlyDenied.canPrompt)
    }

    @Test
    fun `a denial does not ask for a rationale unless told to`() {
        assertFalse(PermissionStatus.Denied().shouldShowRationale)
    }

    @Test
    fun `the two denial cases are distinct values`() {
        assertTrue(PermissionStatus.Denied(shouldShowRationale = true) != PermissionStatus.PermanentlyDenied)
        assertEquals(PermissionStatus.Denied(true), PermissionStatus.Denied(true))
    }

    @Test
    fun `the catalog holds only permissions with an exercised mapping on both platforms`() {
        assertEquals(
            listOf(Permission.NOTIFICATIONS, Permission.MICROPHONE, Permission.CAMERA),
            Permission.entries.toList(),
        )
    }
}
