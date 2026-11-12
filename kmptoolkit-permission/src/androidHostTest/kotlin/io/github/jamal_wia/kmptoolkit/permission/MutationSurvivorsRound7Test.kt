package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Behaviours of the permission controller that no test pinned until a hand-applied mutant survived the
 * whole suite (round 7): a policy that revokes one string of a group, a limited grant that clears the
 * remembered refusals the moment the dialog answers, and a partial rationale that is not mirrored to the
 * entry that asks for a subset of the strings.
 */
@RunWith(AndroidJUnit4::class)
class MutationSurvivorsRound7Test {

    private val application: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `a device policy that revokes one string of a group makes the whole entry Restricted`() = runTest {
        val controller = testController(
            application, InMemoryKeyValueStorage(),
            isRevokedByPolicy = { it == Manifest.permission.ACCESS_COARSE_LOCATION },
        )

        assertEquals(PermissionStatus.Restricted, controller.check(CommonPermission.Location))
        // control: the same policy on a string no entry of the group asks for changes nothing
        val unaffected = testController(
            application, InMemoryKeyValueStorage(),
            isRevokedByPolicy = { it == Manifest.permission.CAMERA },
        )
        assertEquals(PermissionStatus.NotDetermined, unaffected.check(CommonPermission.Location))
    }

    @Test
    fun `a limited grant from the dialog clears the remembered refusals before anything reads the status`() = runTest {
        val rationale = booleanArrayOf(true)
        var answers: (String) -> Boolean = { false }
        val host = RecordingHost(answers = { answers(it) })
        val controller = testController(
            application, InMemoryKeyValueStorage(), host,
            shouldShowRationale = { rationale[0] },
        )
        // A first refusal, read with a rationale: both flags are written.
        assertEquals(PermissionStatus.Denied(explainFirst = true), controller.request(CommonPermission.Location))
        // The user now picks approximate only: Limited, which is a grant and clears both flags.
        answers = { it == Manifest.permission.ACCESS_COARSE_LOCATION }
        assertEquals(PermissionStatus.Limited, controller.request(CommonPermission.Location))
        // The platform reports nothing granted again (revoked in Settings) and no rationale. The flags are gone,
        // so this reads as never asked; stale flags would read as refused for good.
        rationale[0] = false
        assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.Location))
    }

    @Test
    fun `a rationale for one string of a group is not mirrored to the entry that asks for the other string alone`() = runTest {
        val rationale: (String) -> Boolean = { it == Manifest.permission.READ_CALENDAR }
        val host = RecordingHost(answers = { false })
        val controller = testController(
            application, InMemoryKeyValueStorage(), host,
            shouldShowRationale = rationale,
        )

        assertEquals(PermissionStatus.Denied(explainFirst = true), controller.request(CommonPermission.Calendar))
        // The write-only entry saw a refusal (its string was answered false) but never a rationale of its own.
        assertEquals(PermissionStatus.NotDetermined, controller.check(CommonPermission.CalendarWriteOnly))
    }

    @Test
    fun `background location is its own request from API 29 and is location itself below it`() = runTest {
        val fine = Manifest.permission.ACCESS_FINE_LOCATION
        val coarse = Manifest.permission.ACCESS_COARSE_LOCATION
        val background = Manifest.permission.ACCESS_BACKGROUND_LOCATION
        org.robolectric.Shadows.shadowOf(application).grantPermissions(fine, coarse)
        org.robolectric.Shadows.shadowOf(application).denyPermissions(background)

        val host29 = RecordingHost(answers = { true })
        val on29 = testController(application, InMemoryKeyValueStorage(), host29, sdkInt = 29)
        assertEquals(PermissionStatus.NotDetermined, on29.check(CommonPermission.LocationBackground))
        on29.request(CommonPermission.LocationBackground)
        assertEquals(listOf(listOf(background)), host29.groups, "API 29 asks for background location itself")

        val host28 = RecordingHost(answers = { true })
        val on28 = testController(application, InMemoryKeyValueStorage(), host28, sdkInt = 28)
        assertEquals(PermissionStatus.Granted, on28.check(CommonPermission.LocationBackground))
        assertEquals(0, host28.launchCount, "below API 29 it is Location, already granted")
    }
}
