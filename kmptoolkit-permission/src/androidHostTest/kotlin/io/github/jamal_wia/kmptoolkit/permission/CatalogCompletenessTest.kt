package io.github.jamal_wia.kmptoolkit.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `CommonPermission.entries` is "every common permission" and `AndroidPermission.specialAccesses` is
 * "every SpecialAccess that takes no parameter". A new entry added to the hierarchy but forgotten in the
 * list would silently be missing from both, so the lists are compared with the declared objects.
 */
class CatalogCompletenessTest {

    private fun objectsNestedIn(type: Class<*>): List<Any> =
        type.declaredClasses
            .filter { nested -> type.isAssignableFrom(nested) }
            .mapNotNull { nested -> runCatching { nested.getField("INSTANCE").get(null) }.getOrNull() }

    @Test
    fun `every common permission object is in entries and entries holds nothing else`() {
        val declared: Set<Any> = objectsNestedIn(CommonPermission::class.java).toSet()

        assertTrue(declared.size >= 15, "the reflection found only ${declared.size} objects")
        assertEquals(declared, CommonPermission.entries.toSet())
        assertEquals(CommonPermission.entries.size, CommonPermission.entries.toSet().size, "an entry is listed twice")
    }

    @Test
    fun `every parameterless special access is in the list`() {
        val declared: Set<Any> = objectsNestedIn(AndroidPermission::class.java)
            .filter { it is AndroidPermission.SpecialAccess }
            .toSet()

        assertTrue(declared.size >= 12, "the reflection found only ${declared.size} objects")
        assertEquals(declared, AndroidPermission.specialAccesses.toSet())
    }

    @Test
    fun `ids are unique across every entry of both vocabularies`() {
        val all: List<Permission> = CommonPermission.entries +
            AndroidPermission.specialAccesses +
            listOf(
                AndroidPermission.NearbyWifiDevices,
                AndroidPermission.LocalNetwork,
                AndroidPermission.BodySensors,
                AndroidPermission.BodySensorsBackground,
            )

        val ids: List<String> = all.map { it.id }

        assertEquals(ids.size, ids.toSet().size, "ids collided: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}")
        assertTrue(ids.all { it.isNotBlank() && it == it.trim() })
    }
}
