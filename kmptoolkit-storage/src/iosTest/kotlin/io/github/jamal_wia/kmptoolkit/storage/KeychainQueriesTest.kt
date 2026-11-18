package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The dictionaries each store operation sends, asserted without a Keychain (building one calls no Keychain
 * function). Every case names the rule of the iOS review it pins: I-1 (accessibility on every write), I-2 (what
 * `clear()` matches), the exact `synchronizable` on every item query, and the `SecAccessControl` entry.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class KeychainQueriesTest {

    private fun queries(
        accessibility: KeychainAccessibility = KeychainAccessibility.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY,
        accessGroup: String? = null,
        synchronizable: Boolean = false,
        userPresence: IosUserPresence? = null,
    ): KeychainQueries = KeychainQueries(SERVICE, accessibility, accessGroup, synchronizable, userPresence)

    private fun bytes(value: String): NSData = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding)!!

    private val everyAccessibility: Map<KeychainAccessibility, String> = mapOf(
        KeychainAccessibility.WHEN_UNLOCKED to "whenUnlocked",
        KeychainAccessibility.WHEN_UNLOCKED_THIS_DEVICE_ONLY to "whenUnlockedThisDeviceOnly",
        KeychainAccessibility.AFTER_FIRST_UNLOCK to "afterFirstUnlock",
        KeychainAccessibility.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY to "afterFirstUnlockThisDeviceOnly",
        KeychainAccessibility.WHEN_PASSCODE_SET_THIS_DEVICE_ONLY to "whenPasscodeSetThisDeviceOnly",
    )

    // --- exact synchronizable on read and write -------------------------------------------------------------

    @Test
    fun `a local store names synchronizable false on every item query`() {
        val q: KeychainQueries = queries(synchronizable = false)

        assertEquals("false", q.item("k", null).use { describe(it) }["synchronizable"])
        assertEquals("false", q.read("k", null).use { describe(it) }["synchronizable"])
        assertEquals("false", q.add("k", bytes("v")).use { describe(it) }["synchronizable"])
    }

    @Test
    fun `a synchronizable store names synchronizable true on every item query`() {
        val q: KeychainQueries = queries(accessibility = KeychainAccessibility.AFTER_FIRST_UNLOCK, synchronizable = true)

        assertEquals("true", q.item("k", null).use { describe(it) }["synchronizable"])
        assertEquals("true", q.read("k", null).use { describe(it) }["synchronizable"])
        assertEquals("true", q.add("k", bytes("v")).use { describe(it) }["synchronizable"])
    }

    @Test
    fun `a read asks for the data of exactly one item in the store's service`() {
        val read: Map<String, String> = queries().read("the-key", null).use { describe(it) }

        assertEquals("genericPassword", read["class"])
        assertEquals(SERVICE, read["service"])
        assertEquals("the-key", read["account"])
        assertEquals("true", read["returnData"])
        assertEquals("one", read["limit"])
    }

    // --- I-1: accessibility on every write ------------------------------------------------------------------

    @Test
    fun `a new item carries the configured accessibility for every class`() {
        for ((accessibility: KeychainAccessibility, token: String) in everyAccessibility) {
            val add: Map<String, String> = queries(accessibility = accessibility).add("k", bytes("v")).use { describe(it) }

            assertEquals(token, add["accessible"], "add with $accessibility")
        }
    }

    @Test
    fun `an update carries the configured accessibility and not only the new data I-1`() {
        for ((accessibility: KeychainAccessibility, token: String) in everyAccessibility) {
            val attributes: Map<String, String> =
                queries(accessibility = accessibility).updateAttributes(bytes("v")).use { describe(it) }

            assertEquals(token, attributes["accessible"], "update with $accessibility")
            assertEquals("data", attributes["value"], "update with $accessibility")
        }
    }

    @Test
    fun `an add and an update carry the same bytes that were given`() {
        val q: KeychainQueries = queries()
        val value: NSData = bytes("round trip")

        val added: Boolean = q.add("k", value).use { dataOf(it) == value }
        val updated: Boolean = q.updateAttributes(value).use { dataOf(it) == value }

        assertTrue(added && updated)
    }

    @Test
    fun `an empty value is a real item with zero-length data`() {
        val empty: NSData = bytes("")

        val add: Map<String, String> = queries().add("k", empty).use { describe(it) }

        assertEquals("data", add["value"])
        assertEquals(0UL, empty.length)
    }

    // --- I-2: what clear() matches --------------------------------------------------------------------------

    @Test
    fun `clear matches synchronizable and local items alike I-2a`() {
        val clear: Map<String, String> = queries().clear("TEAM.default").use { describe(it) }

        assertEquals("any", clear["synchronizable"])
    }

    @Test
    fun `clear names the store's service and exactly one access group and no account I-2b`() {
        val clear: Map<String, String> = queries().clear("TEAM.default").use { describe(it) }

        assertEquals("genericPassword", clear["class"])
        assertEquals(SERVICE, clear["service"])
        assertEquals("TEAM.default", clear["group"])
        assertNull(clear["account"], "clear must match every key of the store")
    }

    @Test
    fun `a store with a configured group clears that group even for a synchronizable store`() {
        val q: KeychainQueries = queries(
            accessibility = KeychainAccessibility.AFTER_FIRST_UNLOCK,
            accessGroup = "TEAM.shared",
            synchronizable = true,
        )

        val clear: Map<String, String> = q.clear("TEAM.shared").use { describe(it) }

        assertEquals("TEAM.shared", clear["group"])
        assertEquals("any", clear["synchronizable"])
    }

    @Test
    fun `an item query passes the configured access group and none when there is none`() {
        assertEquals("TEAM.shared", queries(accessGroup = "TEAM.shared").item("k", null).use { describe(it) }["group"])
        assertNull(queries(accessGroup = null).item("k", null).use { describe(it) }["group"])
        assertEquals("TEAM.shared", queries(accessGroup = "TEAM.shared").add("k", bytes("v")).use { describe(it) }["group"])
    }

    // --- ensureKey ------------------------------------------------------------------------------------------

    @Test
    fun `the ensureKey query is attribute-only and UI-forbidden and sees both kinds of item`() {
        val ensure: Map<String, String> = queries().ensureKey().use { describe(it) }

        assertEquals("fail", ensure["authUI"])
        assertEquals("any", ensure["synchronizable"])
        assertEquals(SERVICE, ensure["service"])
        assertNull(ensure["account"])
        assertNull(ensure["returnData"], "no data may be requested: that is what makes it non-prompting")
        assertNull(ensure["value"])
    }

    @Test
    fun `the ensureKey query passes the configured group`() {
        assertEquals("TEAM.shared", queries(accessGroup = "TEAM.shared").ensureKey().use { describe(it) }["group"])
    }

    // --- user presence: the SecAccessControl entry ----------------------------------------------------------

    @Test
    fun `a user-presence store protects with an access control and never with an accessibility attribute`() {
        for (policy: IosUserPresencePolicy in IosUserPresencePolicy.entries) {
            val q: KeychainQueries = queries(userPresence = IosUserPresence(policy, "Unlock your tokens"))

            val add: Map<String, String> = q.add("k", bytes("v")).use { describe(it) }
            val update: Map<String, String> = q.updateAttributes(bytes("v")).use { describe(it) }

            for ((label: String, query: Map<String, String>) in listOf("add" to add, "update" to update)) {
                assertEquals("object", query["accessControl"], "$label with $policy")
                assertFalse("accessible" in query, "$label with $policy: the two attributes cannot be combined (errSecParam)")
            }
        }
    }

    @Test
    fun `a store without user presence never sends an access control`() {
        val q: KeychainQueries = queries()

        assertFalse("accessControl" in q.add("k", bytes("v")).use { describe(it) })
        assertFalse("accessControl" in q.updateAttributes(bytes("v")).use { describe(it) })
    }

    @Test
    fun `every policy builds a SecAccessControl`() {
        for (policy: IosUserPresencePolicy in IosUserPresencePolicy.entries) {
            for (accessibility: KeychainAccessibility in KeychainAccessibility.entries) {
                val result: AccessControlResult = createAccessControl(accessibility, policy)

                assertTrue(result is AccessControlResult.Created, "$policy over $accessibility answered $result")
                platform.CoreFoundation.CFRelease(result.control)
            }
        }
    }

    @Test
    fun `the authentication context is attached to reads and update targets only when given`() {
        val sessions = CountingAuthenticationSessions()
        val session: AuthenticationSession = sessions.open("Unlock your tokens")
        val q: KeychainQueries = queries(userPresence = IosUserPresence(IosUserPresencePolicy.USER_PRESENCE, "Unlock your tokens"))

        assertEquals("object", q.read("k", session).use { describe(it) }["context"])
        assertEquals("object", q.item("k", session).use { describe(it) }["context"])
        assertNull(q.read("k", null).use { describe(it) }["context"])
        assertNull(q.item("k", null).use { describe(it) }["context"])
        session.invalidate()
    }

    // --- the default-group probe ----------------------------------------------------------------------------

    @Test
    fun `the probe lives in a service of its own so a delete by the store's service can never match it`() {
        val probe: Map<String, String> = queries().probeAdd("kmptoolkit.accessgroupprobe.fixed").use { describe(it) }

        assertNotEquals(SERVICE, probe["service"])
        assertEquals(KeychainQueries.probeService(SERVICE), probe["service"])
        assertEquals("kmptoolkit.accessgroupprobe.fixed", probe["account"])
        assertNull(probe["group"], "the probe is added without a group: that is how the default group is learned")
        assertEquals("afterFirstUnlockThisDeviceOnly", probe["accessible"])
        assertEquals("true", probe["returnAttributes"])
        assertEquals("false", probe["synchronizable"])
    }

    @Test
    fun `a probe added without an account gets a fresh account every time`() {
        val q: KeychainQueries = queries()
        val accounts: List<String?> = List(5) { q.probeAdd().use { describe(it)["account"] } }

        assertEquals(5, accounts.toSet().size, "$accounts")
        assertTrue(accounts.all { it != null && it.startsWith(KeychainQueries.PROBE_ACCOUNT + ".") && it.length > KeychainQueries.PROBE_ACCOUNT.length + 1 })
    }

    @Test
    fun `the probe is removed by service and group alone so a leftover of an earlier call goes with it`() {
        val remove: Map<String, String> = queries().probeRemove("TEAM.default").use { describe(it) }

        assertEquals("TEAM.default", remove["group"])
        assertEquals(KeychainQueries.probeService(SERVICE), remove["service"])
        assertNull(remove["account"], "naming no account sweeps every probe of the group")
    }

    @Test
    fun `the cleanup of an unreported probe names its unique account and no group`() {
        val remove: Map<String, String> = queries().probeRemoveOwn("kmptoolkit.accessgroupprobe.fixed").use { describe(it) }

        assertEquals("kmptoolkit.accessgroupprobe.fixed", remove["account"])
        assertEquals(KeychainQueries.probeService(SERVICE), remove["service"])
        assertEquals("false", remove["synchronizable"])
        assertNull(remove["group"], "the group is exactly what the caller does not know")
    }

    private companion object {
        const val SERVICE: String = "app.queries.kmptoolkit.securestorage"
    }
}
