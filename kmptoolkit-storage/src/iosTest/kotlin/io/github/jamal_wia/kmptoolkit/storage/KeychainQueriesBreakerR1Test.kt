package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.darwin.NSObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Breaker round 1 of group sto-ios (ticket 5.1.0, STO-5): the exact attribute set of every dictionary
 * [KeychainQueries] builds, for EVERY valid combination of the options (5 accessibilities x access group or none x
 * synchronizable or not x no user presence or each of the 3 policies), plus the options validation matrix.
 *
 * Contract: plan 4.10 and 6.6 (accessibility on every write incl. update, exact synchronizable on reads and writes,
 * `Any` only for `clear()`/`ensureKey()`, `clear()` names one group and no account, `ensureKey()` attribute-only with
 * UI failing, the `SecAccessControl` entry replacing `kSecAttrAccessible`), and plan 3.3 for the validation. An extra
 * attribute is as wrong as a missing one: an update attribute dictionary that names the class or the account is
 * rejected by the framework, and a stray `kSecAttrSynchronizable = Any` on a read makes a store meet the other kind of
 * item. Pure: no Keychain call is made.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class KeychainQueriesBreakerR1Test {

    private class Combo(
        val accessibility: KeychainAccessibility,
        val group: String?,
        val synchronizable: Boolean,
        val presence: IosUserPresence?,
    ) {
        val queries: KeychainQueries = KeychainQueries(SERVICE, accessibility, group, synchronizable, presence)
        override fun toString(): String = "accessibility=$accessibility group=$group sync=$synchronizable presence=${presence?.policy}"
    }

    private val tokens: Map<KeychainAccessibility, String> = mapOf(
        KeychainAccessibility.WHEN_UNLOCKED to "whenUnlocked",
        KeychainAccessibility.WHEN_UNLOCKED_THIS_DEVICE_ONLY to "whenUnlockedThisDeviceOnly",
        KeychainAccessibility.AFTER_FIRST_UNLOCK to "afterFirstUnlock",
        KeychainAccessibility.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY to "afterFirstUnlockThisDeviceOnly",
        KeychainAccessibility.WHEN_PASSCODE_SET_THIS_DEVICE_ONLY to "whenPasscodeSetThisDeviceOnly",
    )

    private val presences: List<IosUserPresence?> =
        listOf(null) + IosUserPresencePolicy.entries.map { IosUserPresence(it, "Unlock") }

    private fun validCombos(): List<Combo> = buildList {
        for (accessibility: KeychainAccessibility in KeychainAccessibility.entries) {
            for (group: String? in listOf(null, GROUP)) {
                for (synchronizable: Boolean in listOf(false, true)) {
                    for (presence: IosUserPresence? in presences) {
                        if (synchronizable && (accessibility.isThisDeviceOnly || presence != null)) continue
                        add(Combo(accessibility, group, synchronizable, presence))
                    }
                }
            }
        }
    }

    private val session: AuthenticationSession = object : AuthenticationSession {
        override val handle: Any = NSObject()
        override fun invalidate() = Unit
    }

    private fun KeychainQuery.read(): Map<String, String> = use { describe(it) }

    @Test
    fun `every query of every valid option combination carries exactly the plan's attributes`() {
        val combos: List<Combo> = validCombos()
        assertEquals(5 * 2 * 4 + 2 * 2, combos.size, "control: the matrix is complete")
        val problems: MutableList<String> = mutableListOf()
        for (combo: Combo in combos) {
            val q: KeychainQueries = combo.queries
            val sync: String = if (combo.synchronizable) "true" else "false"
            val groupEntry: Map<String, String> = combo.group?.let { mapOf("group" to it) }.orEmpty()
            val item: Map<String, String> =
                mapOf("class" to "genericPassword", "service" to SERVICE, "account" to KEY) + groupEntry +
                    mapOf("synchronizable" to sync)
            val protection: Map<String, String> = if (combo.presence == null) {
                mapOf("accessible" to tokens.getValue(combo.accessibility))
            } else {
                mapOf("accessControl" to "object")
            }

            fun expect(name: String, actual: Map<String, String>, expected: Map<String, String>) {
                if (actual != expected) problems += "$combo $name: expected $expected but was $actual"
            }

            expect("item", q.item(KEY, null).read(), item)
            expect("item with session", q.item(KEY, session).read(), item + mapOf("context" to "object"))
            expect("read", q.read(KEY, null).read(), item + mapOf("returnData" to "true", "limit" to "one"))
            expect(
                "read with session",
                q.read(KEY, session).read(),
                item + mapOf("context" to "object", "returnData" to "true", "limit" to "one"),
            )
            expect("add", q.add(KEY, utf8("v")).read(), item + mapOf("value" to "data") + protection)
            expect("update attributes", q.updateAttributes(utf8("v")).read(), mapOf("value" to "data") + protection)
            expect(
                "clear",
                q.clear(GROUP).read(),
                mapOf("class" to "genericPassword", "service" to SERVICE, "group" to GROUP, "synchronizable" to "any"),
            )
            expect(
                "ensureKey",
                q.ensureKey().read(),
                mapOf("class" to "genericPassword", "service" to SERVICE) + groupEntry +
                    mapOf("synchronizable" to "any", "authUI" to "fail", "limit" to "one"),
            )
            val probeLookup: Map<String, String> = q.probeLookup().read()
            val probeAdd: Map<String, String> = q.probeAdd().read()
            val probeRemove: Map<String, String> = q.probeRemove(GROUP).read()
            for ((name: String, probe: Map<String, String>) in listOf("probeLookup" to probeLookup, "probeAdd" to probeAdd, "probeRemove" to probeRemove)) {
                if (probe["service"] == SERVICE) problems += "$combo $name: the probe uses the store's own service"
                if (probe["synchronizable"] != "false") problems += "$combo $name: the probe must be local: $probe"
                if ("context" in probe || "authUI" in probe || "accessControl" in probe || "returnData" in probe) {
                    problems += "$combo $name: the probe carries an authentication attribute or asks for data: $probe"
                }
            }
            if ("group" in probeAdd) problems += "$combo probeAdd names a group, so it cannot learn the default one: $probeAdd"
            if (probeRemove["group"] != GROUP) problems += "$combo probeRemove must name the resolved group: $probeRemove"
        }
        assertTrue(problems.isEmpty(), problems.take(10).joinToString("\n") + "\n(${problems.size} problems)")
    }

    @Test
    fun `an empty key and a very long key reach the dictionary unchanged`() {
        val q = KeychainQueries(SERVICE, KeychainAccessibility.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY, null, false, null)
        val long: String = buildString { repeat(65_536) { append(('a' + it % 26)) } }
        for (key: String in listOf("", " ", long, "ключ-🔑")) {
            assertEquals(key, q.item(key, null).read()["account"], "key of length ${key.length}")
            assertEquals(key, q.read(key, null).read()["account"], "key of length ${key.length}")
        }
    }

    @Test
    fun `the options accept exactly the combinations the plan allows`() {
        val problems: MutableList<String> = mutableListOf()
        for (accessibility: KeychainAccessibility in KeychainAccessibility.entries) {
            for (group: String? in listOf(null, GROUP)) {
                for (synchronizable: Boolean in listOf(false, true)) {
                    for (presence: IosUserPresence? in presences) {
                        val allowed: Boolean = !(synchronizable && (accessibility.isThisDeviceOnly || presence != null))
                        val built: Result<IosSecureStorageOptions> = runCatching {
                            IosSecureStorageOptions(accessibility, group, synchronizable, presence)
                        }
                        val label = "accessibility=$accessibility group=$group sync=$synchronizable presence=${presence?.policy}"
                        if (allowed && built.isFailure) problems += "$label rejected: ${built.exceptionOrNull()}"
                        if (!allowed && built.exceptionOrNull() !is IllegalArgumentException) {
                            problems += "$label must throw IllegalArgumentException, got $built"
                        }
                        built.getOrNull()?.let { options ->
                            if (options.accessibility != accessibility || options.accessGroup != group ||
                                options.synchronizable != synchronizable || options.userPresence !== presence
                            ) {
                                problems += "$label: the options do not keep what was given"
                            }
                        }
                    }
                }
            }
        }
        for (blank: String in listOf("", " ", "\t", "\n", " ", " ")) {
            if (runCatching { IosSecureStorageOptions(accessGroup = blank) }.exceptionOrNull() !is IllegalArgumentException) {
                problems += "blank access group '${blank.encodeToByteArray().toList()}' accepted"
            }
            if (runCatching { IosUserPresence(IosUserPresencePolicy.USER_PRESENCE, blank) }.exceptionOrNull() !is IllegalArgumentException) {
                problems += "blank prompt '${blank.encodeToByteArray().toList()}' accepted"
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    private companion object {
        const val SERVICE: String = "breaker.kmptoolkit.securestorage"
        const val GROUP: String = "TEAM.shared"
        const val KEY: String = "k"
    }
}
