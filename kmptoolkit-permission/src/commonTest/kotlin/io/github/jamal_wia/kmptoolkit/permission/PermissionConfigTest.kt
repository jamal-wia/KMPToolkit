package io.github.jamal_wia.kmptoolkit.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The contract from [PermissionConfig]'s own KDoc: nothing is hardcoded to this library alone, the
 * default comes from the consuming app's identifier, and two configurations never share a key.
 */
class PermissionConfigTest {

    @Test
    fun `the default prefix is the application id plus this library's namespace`() {
        assertEquals(
            "com.example.app.kmptoolkit.permission",
            PermissionConfig().resolveKeyPrefix("com.example.app"),
        )
    }

    @Test
    fun `an explicit prefix is used verbatim and ignores the application id`() {
        assertEquals(
            "com.example.onboarding",
            PermissionConfig("com.example.onboarding").resolveKeyPrefix("com.example.app"),
        )
    }

    @Test
    fun `the default config stores no prefix of its own`() {
        assertNull(PermissionConfig().keyPrefix)
    }

    @Test
    fun `a blank prefix is rejected at the call site`() {
        assertFailsWith<IllegalArgumentException> { PermissionConfig("") }
        assertFailsWith<IllegalArgumentException> { PermissionConfig("   ") }
    }

    @Test
    fun `two apps using the default never share a key`() {
        val first: String = askedKey(
            PermissionConfig().resolveKeyPrefix("com.example.first"),
            Permission.CAMERA,
        )
        val second: String = askedKey(
            PermissionConfig().resolveKeyPrefix("com.example.second"),
            Permission.CAMERA,
        )

        assertTrue(first != second, "$first collided with $second")
    }

    @Test
    fun `two configurations in one app never share a key`() {
        val first: String =
            askedKey(PermissionConfig("a").resolveKeyPrefix("com.example.app"), Permission.CAMERA)
        val second: String =
            askedKey(PermissionConfig("b").resolveKeyPrefix("com.example.app"), Permission.CAMERA)

        assertTrue(first != second, "$first collided with $second")
    }

    @Test
    fun `every permission gets its own key under one prefix`() {
        val keys: List<String> = Permission.entries.map { permission -> askedKey("p", permission) }

        assertEquals(Permission.entries.size, keys.toSet().size, "keys collided: $keys")
    }

    @Test
    fun `a key is named after the permission rather than a platform string`() {
        assertEquals("p.asked.NOTIFICATIONS", askedKey("p", Permission.NOTIFICATIONS))
    }
}
