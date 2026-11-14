package io.github.jamal_wia.kmptoolkit.permission

import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * Round 9 (E1, bm): the public data types as a hostile consumer uses them: bad constructor arguments, equality and
 * hash codes, `toString`, ids as storage keys, and sealed hierarchies matched without an `else` (a new subtype
 * breaks the compile here, which is the point).
 */
@RunWith(AndroidJUnit4::class)
class TypeHygieneRound9Test {

    private fun describe(status: PermissionStatus): String = when (status) {
        PermissionStatus.Granted -> "granted"
        PermissionStatus.Limited -> "limited"
        is PermissionStatus.Denied -> if (status.explainFirst) "denied-explain" else "denied"
        PermissionStatus.PermanentlyDenied -> "permanent"
        PermissionStatus.NotDetermined -> "undetermined"
        PermissionStatus.Restricted -> "restricted"
        PermissionStatus.Unavailable -> "unavailable"
    }

    private fun describe(state: PermissionFlowState): String = when (state) {
        PermissionFlowState.Idle -> "idle"
        PermissionFlowState.Requesting -> "requesting"
        PermissionFlowState.AwaitingRationale -> "rationale"
        PermissionFlowState.AwaitingSettings -> "settings"
        PermissionFlowState.Granted -> "granted"
        PermissionFlowState.Limited -> "limited"
        PermissionFlowState.Denied -> "denied"
        PermissionFlowState.Unavailable -> "unavailable"
    }

    @Test
    fun `every status and flow state has its own description and its own string form`() {
        val statuses: List<PermissionStatus> = listOf(
            PermissionStatus.Granted, PermissionStatus.Limited, PermissionStatus.Denied(false), PermissionStatus.Denied(true),
            PermissionStatus.PermanentlyDenied, PermissionStatus.NotDetermined, PermissionStatus.Restricted,
            PermissionStatus.Unavailable,
        )
        assertEquals(statuses.size, statuses.map { describe(it) }.toSet().size)
        assertEquals(statuses.size, statuses.map { it.toString() }.toSet().size)
        assertTrue(PermissionStatus.Denied(true).toString().contains("true"))
        val states: List<PermissionFlowState> = listOf(
            PermissionFlowState.Idle, PermissionFlowState.Requesting, PermissionFlowState.AwaitingRationale,
            PermissionFlowState.AwaitingSettings, PermissionFlowState.Granted, PermissionFlowState.Limited,
            PermissionFlowState.Denied, PermissionFlowState.Unavailable,
        )
        assertEquals(states.size, states.map { describe(it) }.toSet().size)
        assertEquals(states.size, states.map { it.toString() }.toSet().size)
    }

    @Test
    fun `denied compares and hashes by its flag and copy keeps the rest`() {
        assertEquals(PermissionStatus.Denied(), PermissionStatus.Denied(false))
        assertEquals(PermissionStatus.Denied(true).hashCode(), PermissionStatus.Denied(true).hashCode())
        assertNotEquals(PermissionStatus.Denied(true), PermissionStatus.Denied(false))
        assertNotEquals<PermissionStatus>(PermissionStatus.Denied(false), PermissionStatus.NotDetermined)
        assertEquals(PermissionStatus.Denied(true), PermissionStatus.Denied(false).copy(explainFirst = true))
        assertEquals(setOf(PermissionStatus.Denied(true), PermissionStatus.Denied(false)), setOf(PermissionStatus.Denied(true), PermissionStatus.Denied(false), PermissionStatus.Denied(true)))
    }

    @Test
    fun `the helper properties agree with the status in every case`() {
        val all: List<PermissionStatus> = listOf(
            PermissionStatus.Granted, PermissionStatus.Limited, PermissionStatus.Denied(false), PermissionStatus.Denied(true),
            PermissionStatus.PermanentlyDenied, PermissionStatus.NotDetermined, PermissionStatus.Restricted,
            PermissionStatus.Unavailable,
        )
        assertEquals(listOf(PermissionStatus.Granted), all.filter { it.isGranted })
        assertEquals(listOf(PermissionStatus.Granted, PermissionStatus.Limited), all.filter { it.isUsable })
        assertEquals(
            listOf(PermissionStatus.Denied(false), PermissionStatus.Denied(true), PermissionStatus.NotDetermined),
            all.filter { it.canPrompt },
        )
    }

    @Test
    fun `every catalog entry has a distinct non blank id and a distinct string form`() {
        val entries: List<Permission> = CommonPermission.entries + AndroidPermission.specialAccesses
        assertEquals(entries.size, entries.map { it.id }.toSet().size, "ids are storage keys and must not collide")
        assertTrue(entries.all { it.id.isNotBlank() && it.id.none(Char::isWhitespace) })
        assertEquals(entries.size, entries.map { it.toString() }.toSet().size)
    }

    @Test
    fun `parameterised permissions compare by value print their type and flatten component names into the id`() {
        assertEquals(AndroidPermission.Runtime("android.permission.X"), AndroidPermission.Runtime("android.permission.X"))
        assertEquals(
            AndroidPermission.Runtime("android.permission.X").hashCode(),
            AndroidPermission.Runtime("android.permission.X").hashCode(),
        )
        assertNotEquals<Permission>(AndroidPermission.Runtime("a"), AndroidPermission.Role("a"))
        assertTrue(AndroidPermission.Runtime("a").toString().startsWith("Runtime("))
        assertTrue(AndroidPermission.Role("a").toString().startsWith("Role("))
        assertEquals("android.app.role.HOME", AndroidPermission.Role.Home.id)

        val service = ComponentName("com.example", "com.example.Service")
        val sameService = ComponentName("com.example", "com.example.Service")
        assertEquals(AndroidPermission.AccessibilityService(service), AndroidPermission.AccessibilityService(sameService))
        assertEquals("ACCESSIBILITY_SERVICE:com.example/com.example.Service", AndroidPermission.AccessibilityService(service).id)
        assertEquals("DEVICE_ADMIN:com.example/com.example.Service", AndroidPermission.DeviceAdmin(service).id)
        assertNotEquals(AndroidPermission.AccessibilityService(service).id, AndroidPermission.DeviceAdmin(service).id)
    }

    @Test
    fun `blank names and prefixes are refused in every spelling`() {
        listOf("", " ", "\t", "\n", "  \r\n ").forEach { blank ->
            assertFailsWith<IllegalArgumentException>("Runtime '$blank'") { AndroidPermission.Runtime(blank) }
            assertFailsWith<IllegalArgumentException>("Role '$blank'") { AndroidPermission.Role(blank) }
            assertFailsWith<IllegalArgumentException>("prefix '$blank'") { AndroidPermissionConfig(blank) }
        }
        assertEquals(null, AndroidPermissionConfig().keyPrefix)
        assertEquals(AndroidPermissionConfig("a.b"), AndroidPermissionConfig("a.b"))
        assertEquals(AndroidPermissionConfig("a.b").hashCode(), AndroidPermissionConfig("a.b").hashCode())
        assertFalse(AndroidPermissionConfig("a.b") == AndroidPermissionConfig("a.c"))
        assertEquals("c", AndroidPermissionConfig("a").copy(keyPrefix = "c").keyPrefix)
        assertTrue(AndroidPermissionConfig("a.b").toString().contains("a.b"))
    }

    @Test
    fun `the id of every special access and common entry is stable text and not a class name`() {
        (CommonPermission.entries + AndroidPermission.specialAccesses).forEach { entry ->
            assertTrue(entry.id == entry.id.uppercase(), "${entry.id}: ids are SCREAMING_SNAKE")
            assertFalse(entry.id.contains("kmptoolkit", ignoreCase = true), "${entry.id}: an id must not be a class name")
        }
    }
}
