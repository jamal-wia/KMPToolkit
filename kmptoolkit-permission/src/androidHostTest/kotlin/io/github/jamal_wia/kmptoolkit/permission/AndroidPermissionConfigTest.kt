package io.github.jamal_wia.kmptoolkit.permission

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The contract from [AndroidPermissionConfig]'s own KDoc: nothing is hardcoded to this library alone, the
 * default comes from the consuming app's identifier, and two configurations never share a key.
 */
class AndroidPermissionConfigTest {

    @Test
    fun `the default prefix is the application id plus this library's namespace`() {
        assertEquals(
            "com.example.app.kmptoolkit.permission",
            AndroidPermissionConfig().resolveKeyPrefix("com.example.app"),
        )
    }

    @Test
    fun `an explicit prefix is used verbatim and ignores the application id`() {
        assertEquals(
            "com.example.onboarding",
            AndroidPermissionConfig("com.example.onboarding").resolveKeyPrefix("com.example.app"),
        )
    }

    @Test
    fun `the default config stores no prefix of its own`() {
        assertNull(AndroidPermissionConfig().keyPrefix)
    }

    @Test
    fun `a blank prefix is rejected at the call site`() {
        assertFailsWith<IllegalArgumentException> { AndroidPermissionConfig("") }
        assertFailsWith<IllegalArgumentException> { AndroidPermissionConfig("   ") }
    }

    @Test
    fun `two apps using the default never share a key`() {
        val first: String = askedKey(
            AndroidPermissionConfig().resolveKeyPrefix("com.example.first"),
            CommonPermission.Camera,
        )
        val second: String = askedKey(
            AndroidPermissionConfig().resolveKeyPrefix("com.example.second"),
            CommonPermission.Camera,
        )

        assertTrue(first != second, "$first collided with $second")
    }

    @Test
    fun `two configurations in one app never share a key`() {
        val first: String =
            askedKey(AndroidPermissionConfig("a").resolveKeyPrefix("com.example.app"), CommonPermission.Camera)
        val second: String =
            askedKey(AndroidPermissionConfig("b").resolveKeyPrefix("com.example.app"), CommonPermission.Camera)

        assertTrue(first != second, "$first collided with $second")
    }

    @Test
    fun `every permission gets its own key under one prefix`() {
        val keys: List<String> = CommonPermission.entries.map { permission -> askedKey("p", permission) }

        assertEquals(CommonPermission.entries.size, keys.toSet().size, "keys collided: $keys")
    }

    @Test
    fun `a key is named after the permission rather than a platform string`() {
        assertEquals("p.asked.NOTIFICATIONS", askedKey("p", CommonPermission.Notifications))
    }

    @Test
    fun `special accesses keep their 2_x names as keys`() {
        assertEquals("p.asked.EXACT_ALARM", askedKey("p", AndroidPermission.ExactAlarm))
        assertEquals("p.rationale.INSTALL_UNKNOWN_APPS", rationaleSeenKey("p", AndroidPermission.InstallPackages))
        assertEquals("p.asked.FULL_SCREEN_INTENT", askedKey("p", AndroidPermission.FullScreenNotifications))
        assertEquals("p.asked.NOTIFICATION_LISTENER_ACCESS", askedKey("p", AndroidPermission.NotificationListenerAccess))
    }

    @Test
    fun `audio library keeps the 2_x MEDIA_AUDIO key`() {
        assertEquals("p.asked.MEDIA_AUDIO", askedKey("p", CommonPermission.AudioLibrary))
        assertEquals("p.rationale.MEDIA_AUDIO", rationaleSeenKey("p", CommonPermission.AudioLibrary))
    }
}
