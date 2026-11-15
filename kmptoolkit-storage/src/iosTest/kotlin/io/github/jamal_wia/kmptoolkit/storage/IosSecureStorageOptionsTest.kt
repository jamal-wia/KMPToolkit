package io.github.jamal_wia.kmptoolkit.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The validation rules of [IosSecureStorageOptions] and [IosUserPresence] (plan 3.3, section 6.6): an invalid
 * combination throws at construction, so no store operation can fail on its own configuration.
 */
class IosSecureStorageOptionsTest {

    private val deviceOnly: List<KeychainAccessibility> = listOf(
        KeychainAccessibility.WHEN_UNLOCKED_THIS_DEVICE_ONLY,
        KeychainAccessibility.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY,
        KeychainAccessibility.WHEN_PASSCODE_SET_THIS_DEVICE_ONLY,
    )

    private val portable: List<KeychainAccessibility> = listOf(
        KeychainAccessibility.WHEN_UNLOCKED,
        KeychainAccessibility.AFTER_FIRST_UNLOCK,
    )

    @Test
    fun `the defaults are the previous behaviour`() {
        val options = IosSecureStorageOptions()

        assertEquals(KeychainAccessibility.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY, options.accessibility)
        assertNull(options.accessGroup)
        assertEquals(false, options.synchronizable)
        assertNull(options.userPresence)
    }

    @Test
    fun `every accessibility is accepted for a store that stays on the device`() {
        for (accessibility: KeychainAccessibility in KeychainAccessibility.entries) {
            assertSame(accessibility, IosSecureStorageOptions(accessibility = accessibility).accessibility)
        }
    }

    @Test
    fun `synchronizable is rejected with every THIS_DEVICE_ONLY accessibility`() {
        for (accessibility: KeychainAccessibility in deviceOnly) {
            assertFailsWith<IllegalArgumentException>("synchronizable with $accessibility") {
                IosSecureStorageOptions(accessibility = accessibility, synchronizable = true)
            }
        }
    }

    @Test
    fun `synchronizable alone is rejected because the default accessibility is THIS_DEVICE_ONLY`() {
        // Documented in the KDoc: the caller must pass a portable accessibility along with true.
        assertFailsWith<IllegalArgumentException> { IosSecureStorageOptions(synchronizable = true) }
    }

    @Test
    fun `synchronizable is accepted with a portable accessibility`() {
        for (accessibility: KeychainAccessibility in portable) {
            assertEquals(true, IosSecureStorageOptions(accessibility = accessibility, synchronizable = true).synchronizable)
        }
    }

    @Test
    fun `synchronizable is rejected with user presence`() {
        for (accessibility: KeychainAccessibility in portable) {
            assertFailsWith<IllegalArgumentException> {
                IosSecureStorageOptions(
                    accessibility = accessibility,
                    synchronizable = true,
                    userPresence = IosUserPresence(IosUserPresencePolicy.USER_PRESENCE, "Unlock"),
                )
            }
        }
    }

    @Test
    fun `user presence is accepted for every policy and keeps its prompt`() {
        for (policy: IosUserPresencePolicy in IosUserPresencePolicy.entries) {
            val presence = IosUserPresence(policy, "Unlock the tokens")
            val options = IosSecureStorageOptions(userPresence = presence)

            assertSame(presence, options.userPresence)
            assertEquals(policy, presence.policy)
            assertEquals("Unlock the tokens", presence.prompt)
        }
    }

    @Test
    fun `a blank prompt is rejected and empty and whitespace alike`() {
        for (prompt: String in listOf("", " ", "\t\n  ")) {
            assertFailsWith<IllegalArgumentException>("prompt '$prompt'") {
                IosUserPresence(IosUserPresencePolicy.USER_PRESENCE, prompt)
            }
        }
    }

    @Test
    fun `a blank access group is rejected and a real one is kept`() {
        for (group: String in listOf("", "  ")) {
            assertFailsWith<IllegalArgumentException>("group '$group'") { IosSecureStorageOptions(accessGroup = group) }
        }

        assertEquals("TEAM.shared", IosSecureStorageOptions(accessGroup = "TEAM.shared").accessGroup)
    }

    @Test
    fun `isThisDeviceOnly is true for exactly the three device-only classes`() {
        assertEquals(deviceOnly.toSet(), KeychainAccessibility.entries.filter { it.isThisDeviceOnly }.toSet())
    }

    @Test
    fun `the factory accepts options without touching the Keychain`() {
        createSecureKeyValueStorage(options = IosSecureStorageOptions(accessGroup = "TEAM.not.granted"))
        createSecureKeyValueStorage(
            StorageConfig("options.factory.test"),
            IosSecureStorageOptions(userPresence = IosUserPresence(IosUserPresencePolicy.BIOMETRY_ANY, "Unlock")),
        )
    }
}
