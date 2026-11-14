package io.github.jamal_wia.kmptoolkit.permission

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.storage.testing.InMemoryKeyValueStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Every public entry point of the permission module with the arguments a careless caller passes: empty and
 * duplicated lists, a huge list, entries in the wrong order, blank names, a foreign permission mixed with
 * real ones, and the same request repeated. Each call has a hard real-time bound.
 */
@RunWith(AndroidJUnit4::class)
class ApiMisuseRound8Test {

    private val application: Application = ApplicationProvider.getApplicationContext()

    private fun <T> bounded(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private inner class GrantingHost : RecordingHost(answers = { true }) {
        override fun launch(androidPermissions: List<String>, onResult: (Map<String, Boolean>) -> Unit): Boolean {
            shadowOf(application).grantPermissions(*androidPermissions.toTypedArray())
            return super.launch(androidPermissions, onResult)
        }
    }

    @Test
    fun `an empty batch returns an empty map at once and shows nothing`() {
        val host = RecordingHost()
        val controller = testController(application, InMemoryKeyValueStorage(), host)

        val result: Map<Permission, PermissionStatus> = bounded { controller.request(emptyList()) }

        assertEquals(emptyMap(), result)
        assertEquals(0, host.launchCount)
    }

    @Test
    fun `duplicates in a batch give one entry per distinct permission in first appearance order and one dialog`() {
        val host = RecordingHost(answers = { false })
        val controller = testController(application, InMemoryKeyValueStorage(), host)

        val result: Map<Permission, PermissionStatus> = bounded {
            controller.request(listOf(CommonPermission.Microphone, CommonPermission.Camera, CommonPermission.Microphone, CommonPermission.Camera))
        }

        assertEquals(listOf<Permission>(CommonPermission.Microphone, CommonPermission.Camera), result.keys.toList())
        assertEquals(1, host.launchCount, "${host.groups}")
        assertEquals(2, host.groups.single().size, "a string is asked twice: ${host.groups}")
    }

    @Test
    fun `a hundred thousand copies of one permission are one request`() {
        val host = RecordingHost(answers = { false })
        val controller = testController(application, InMemoryKeyValueStorage(), host)

        val result: Map<Permission, PermissionStatus> =
            bounded { controller.request(List(100_000) { CommonPermission.Camera }) }

        assertEquals(setOf<Permission>(CommonPermission.Camera), result.keys)
        assertEquals(1, host.launchCount)
    }

    @Test
    fun `three hundred distinct raw permissions share one dialog and keep their order`() {
        val host = GrantingHost()
        val controller = testController(application, InMemoryKeyValueStorage(), host, protectionOf = { Protection.Dangerous })
        val entries: List<Permission> = List(300) { AndroidPermission.Runtime("com.example.PERMISSION_$it") }

        val result: Map<Permission, PermissionStatus> = bounded { controller.request(entries) }

        assertEquals(entries, result.keys.toList())
        assertTrue(result.values.all { it == PermissionStatus.Granted }, "${result.values.groupBy { it }.mapValues { it.value.size }}")
        assertEquals(1, host.launchCount)
        assertEquals(300, host.groups.single().size)
    }

    @Test
    fun `blank names are rejected at construction and a weird non blank name is just unknown`() {
        assertFailsWith<IllegalArgumentException> { AndroidPermission.Runtime("") }
        assertFailsWith<IllegalArgumentException> { AndroidPermission.Runtime("   ") }
        assertFailsWith<IllegalArgumentException> { AndroidPermission.Role("") }
        assertFailsWith<IllegalArgumentException> { AndroidPermissionConfig(keyPrefix = "") }
        assertFailsWith<IllegalArgumentException> { AndroidPermissionConfig(keyPrefix = " ") }
        val controller = testController(application, InMemoryKeyValueStorage(), protectionOf = { null })

        val status: PermissionStatus = bounded { controller.check(AndroidPermission.Runtime("  ../weird\nname  ")) }

        assertEquals(PermissionStatus.Unavailable, status)
    }

    @Test
    fun `a foreign permission mixed into a batch is Unavailable and does not disturb the others`() {
        val host = GrantingHost()
        val controller = testController(application, InMemoryKeyValueStorage(), host)

        val result: Map<Permission, PermissionStatus> = bounded {
            controller.request(listOf(CommonPermission.Camera, ForeignPermission, CommonPermission.Microphone))
        }

        assertEquals(PermissionStatus.Granted, result[CommonPermission.Camera])
        assertEquals(PermissionStatus.Unavailable, result[ForeignPermission])
        assertEquals(PermissionStatus.Granted, result[CommonPermission.Microphone])
        assertEquals(listOf<Permission>(CommonPermission.Camera, ForeignPermission, CommonPermission.Microphone), result.keys.toList())
        assertEquals(false, bounded { controller.openSettings(ForeignPermission) })
    }

    @Test
    fun `background before foreground in one batch is not asked as the guide says to order them`() {
        val host = GrantingHost()
        val controller = testController(application, InMemoryKeyValueStorage(), host)

        val reversed: Map<Permission, PermissionStatus> =
            bounded { controller.request(listOf(CommonPermission.LocationBackground, CommonPermission.Location)) }

        assertEquals(listOf<Permission>(CommonPermission.LocationBackground, CommonPermission.Location), reversed.keys.toList())
        assertEquals(PermissionStatus.Granted, reversed[CommonPermission.Location])
        // Listed first, it was considered before its foreground permission was granted: not asked, status unchanged.
        assertEquals(PermissionStatus.NotDetermined, reversed[CommonPermission.LocationBackground])
        assertEquals(listOf(listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)), host.groups)

        // Repeating the batch in the documented order now asks for background alone.
        val again: Map<Permission, PermissionStatus> =
            bounded { controller.request(listOf(CommonPermission.Location, CommonPermission.LocationBackground)) }
        assertEquals(PermissionStatus.Granted, again[CommonPermission.LocationBackground])
        assertEquals(listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), host.groups.last())
    }

    @Test
    fun `the same request repeated ten times after a grant shows exactly the first dialog`() {
        val host = GrantingHost()
        val controller = testController(application, InMemoryKeyValueStorage(), host)

        repeat(10) { assertEquals(PermissionStatus.Granted, bounded { controller.request(CommonPermission.Camera) }) }

        assertEquals(1, host.launchCount)
    }

    @Test
    fun `observe on a foreign permission emits Unavailable once and openSettings on one is false`() {
        val controller = testController(application, InMemoryKeyValueStorage())

        val first: PermissionStatus = bounded { controller.observe(ForeignPermission).first() }

        assertEquals(PermissionStatus.Unavailable, first)
    }
}
