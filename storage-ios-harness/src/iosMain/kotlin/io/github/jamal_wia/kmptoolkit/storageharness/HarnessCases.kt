package io.github.jamal_wia.kmptoolkit.storageharness

import io.github.jamal_wia.kmptoolkit.storage.IosSecureStorageOptions
import io.github.jamal_wia.kmptoolkit.storage.IosUserPresence
import io.github.jamal_wia.kmptoolkit.storage.IosUserPresencePolicy
import io.github.jamal_wia.kmptoolkit.storage.KeychainAccessibility
import io.github.jamal_wia.kmptoolkit.storage.SecureKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageConfig
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.UnsupportedReason
import io.github.jamal_wia.kmptoolkit.storage.createSecureKeyValueStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** The access groups the host app's entitlements grant besides its default one (`ios-host/StorageHarness.entitlements`). */
internal const val SHARED_GROUP: String = "io.github.jamal-wia.kmptoolkit.storageharness.shared"
internal const val OTHER_GROUP: String = "io.github.jamal-wia.kmptoolkit.storageharness.other"

/** A group no entitlement of the host app grants: every operation on it must answer MISSING_ENTITLEMENT. */
internal const val UNGRANTED_GROUP: String = "io.github.jamal-wia.kmptoolkit.storageharness.notgranted"

/** What one case writes down: checks that pass or fail, and observations a person (or the CHANGELOG) will read. */
internal class CaseLog {
    private val lines: MutableList<String> = mutableListOf()
    var failures: Int = 0
        private set

    fun note(line: String) {
        lines += line
    }

    /** A claim the library makes. A false one fails the case. */
    fun check(claim: String, holds: Boolean, evidence: String = "") {
        if (!holds) failures++
        lines += (if (holds) "  ok   " else "  FAIL ") + claim + if (evidence.isEmpty()) "" else "  [$evidence]"
    }

    fun text(): String = lines.joinToString("\n")
}

internal class Case(
    val id: String,
    val title: String,
    /** A case a person judges (user presence, re-enrollment, passcode removal): its verdict is OBSERVED, never PASS. */
    val manual: Boolean,
    /** What the person does, or what the case asserts, in a few sentences. */
    val instructions: String,
    val run: suspend CaseLog.() -> Unit,
)

private fun storeNamed(
    name: String,
    accessibility: KeychainAccessibility = KeychainAccessibility.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY,
    accessGroup: String? = null,
    synchronizable: Boolean = false,
    userPresence: IosUserPresence? = null,
): SecureKeyValueStorage = createSecureKeyValueStorage(
    StorageConfig(name),
    IosSecureStorageOptions(accessibility, accessGroup, synchronizable, userPresence),
)

private fun describe(result: StorageResult<*>): String = when (result) {
    is StorageResult.Success -> "Success(${result.value})"
    is StorageResult.Failure -> "Failure(${result.error})"
}

private fun StorageResult<*>.errorOrNull(): StorageError? = (this as? StorageResult.Failure)?.error

private fun fresh(name: String): String {
    RawKeychain.wipe(RawKeychain.serviceOf(name))
    return name
}

internal val allCases: List<Case> = listOf(
    Case(
        id = "duplicate-retry",
        title = "errSecDuplicateItem retry",
        manual = false,
        instructions = "40 stores over one name add the same new key at once. Every put must succeed (a lost add is retried as an update) and exactly one item must exist.",
    ) {
        val name: String = fresh("harness.duplicate")
        val results: List<StorageResult<Unit>> = coroutineScope {
            (0 until 40).map { index ->
                async(Dispatchers.Default) { storeNamed(name).put("race", "v$index") }
            }.awaitAll()
        }
        check("every put succeeded", results.all { it is StorageResult.Success }, results.filterIsInstance<StorageResult.Failure>().take(3).toString())
        check("exactly one item exists", RawKeychain.items(RawKeychain.serviceOf(name)).size == 1)
        val read: StorageResult<String?> = storeNamed(name).get("race")
        check("the value is one a writer wrote", (read as? StorageResult.Success)?.value?.startsWith("v") == true, describe(read))
        RawKeychain.wipe(RawKeychain.serviceOf(name))
    },
    Case(
        id = "clear-across-group",
        title = "clear() across an access group",
        manual = false,
        instructions = "Two stores of one name in the shared group: clear() from one empties the other. A same-named store in another granted group keeps its items.",
    ) {
        val name: String = fresh("harness.cleargroup")
        val a: SecureKeyValueStorage = storeNamed(name, accessGroup = SHARED_GROUP)
        val b: SecureKeyValueStorage = storeNamed(name, accessGroup = SHARED_GROUP)
        val other: SecureKeyValueStorage = storeNamed(name, accessGroup = OTHER_GROUP)
        note("put b: " + describe(b.put("x", "from-b")))
        note("put other: " + describe(other.put("y", "from-other-group")))
        check("b wrote into the shared group", RawKeychain.items(RawKeychain.serviceOf(name), SHARED_GROUP).size == 1)
        note("clear a: " + describe(a.clear()))
        check("b's item is gone", (b.get("x") as? StorageResult.Success)?.value == null)
        check("the other group's item survived", (other.get("y") as? StorageResult.Success)?.value == "from-other-group")
        RawKeychain.wipe(RawKeychain.serviceOf(name))
    },
    Case(
        id = "null-group-clear",
        title = "accessGroup = null clear() and a same-name store in a shared group (I-2b)",
        manual = false,
        instructions = "Confirms or refutes defect I-2b and checks the 6.0.0 fix. Step 1 reproduces the old clear() by hand (a delete by service naming no group) and records whether it reaches the shared group. Step 2 runs the library's clear() on the default-group store: the shared-group store must keep its item.",
    ) {
        val name: String = fresh("harness.nullgroup")
        val service: String = RawKeychain.serviceOf(name)
        val defaultStore: SecureKeyValueStorage = storeNamed(name)
        val sharedStore: SecureKeyValueStorage = storeNamed(name, accessGroup = SHARED_GROUP)

        note("put default: " + describe(defaultStore.put("k", "default-group")))
        note("put shared: " + describe(sharedStore.put("k", "shared-group")))
        val before: List<RawKeychain.Item> = RawKeychain.items(service)
        before.forEach { note("  item: $it") }
        check("two items exist, in two groups", before.map { it.group }.toSet().size == 2)
        note("default group of this app, as observed: " + before.firstOrNull { it.group != SHARED_GROUP }?.group)

        // Step 1: the pre-6.0.0 delete, by hand.
        val oldStatus: Int = RawKeychain.deleteWithoutGroup(service)
        val afterOld: List<RawKeychain.Item> = RawKeychain.items(service)
        note("OBSERVED I-2b: a delete naming no group (status $oldStatus) left ${afterOld.size} of 2 items; shared-group item survived: ${afterOld.any { it.group == SHARED_GROUP }}")
        note("  (I-2b is " + if (afterOld.none { it.group == SHARED_GROUP }) "CONFIRMED: the wide delete reaches the shared group)" else "REFUTED on this device: the wide delete leaves the shared group alone)")

        // Step 2: the library's clear().
        RawKeychain.wipe(service)
        defaultStore.put("k", "default-group")
        sharedStore.put("k", "shared-group")
        val cleared: StorageResult<Unit> = defaultStore.clear()
        note("clear default store: " + describe(cleared))
        check("clear succeeded", cleared is StorageResult.Success)
        check("the shared-group store kept its item", (sharedStore.get("k") as? StorageResult.Success)?.value == "shared-group")
        check("the default group's item is gone", RawKeychain.items(service).none { it.group != SHARED_GROUP })
        check("no probe item is left", RawKeychain.items("$service.accessgroupprobe").isEmpty())
        RawKeychain.wipe(service)
    },
    Case(
        id = "synchronizable-clear",
        title = "clear() removes synchronizable items (I-2a)",
        manual = false,
        instructions = "A synchronizable store writes an item; clear() must remove it. Needs a Keychain that accepts synchronizable items (simulator is fine).",
    ) {
        val name: String = fresh("harness.sync")
        val service: String = RawKeychain.serviceOf(name)
        val synced: SecureKeyValueStorage = storeNamed(name, accessibility = KeychainAccessibility.AFTER_FIRST_UNLOCK, synchronizable = true)
        val local: SecureKeyValueStorage = storeNamed(name)
        note("put synced: " + describe(synced.put("k", "cloud")))
        note("put local: " + describe(local.put("k", "device")))
        RawKeychain.items(service).forEach { note("  item: $it") }
        check("the synchronizable item exists", RawKeychain.items(service).any { it.synchronizable == "1" })
        check("each store reads only its own kind", (synced.get("k") as? StorageResult.Success)?.value == "cloud" && (local.get("k") as? StorageResult.Success)?.value == "device")
        note("clear synced: " + describe(synced.clear()))
        check("clear removed the synchronizable item and the local one of the same store name", RawKeychain.items(service).isEmpty(), RawKeychain.items(service).toString())
        RawKeychain.wipe(service)
    },
    Case(
        id = "accessibility-on-update",
        title = "accessibility is applied on update (I-1)",
        manual = false,
        instructions = "An item created WHEN_UNLOCKED is overwritten by a store configured AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY; the item must take the new class.",
    ) {
        val name: String = fresh("harness.accessibility")
        val service: String = RawKeychain.serviceOf(name)
        note("put (WHEN_UNLOCKED): " + describe(storeNamed(name, accessibility = KeychainAccessibility.WHEN_UNLOCKED).put("k", "1")))
        val created: String? = RawKeychain.items(service).singleOrNull()?.accessible
        note("accessible after create: $created")
        note("put (AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY): " + describe(storeNamed(name).put("k", "2")))
        val updated: String? = RawKeychain.items(service).singleOrNull()?.accessible
        note("accessible after update: $updated")
        // kSecAttrAccessible values: ak = WhenUnlocked, cku = AfterFirstUnlockThisDeviceOnly.
        check("created as WHEN_UNLOCKED", created == "ak", "$created")
        check("moved to AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY by the update", updated == "cku", "$updated")
        RawKeychain.wipe(service)
    },
    Case(
        id = "missing-entitlement",
        title = "-34018 with an ungranted access group",
        manual = false,
        instructions = "A store naming a group the app's entitlements do not grant: every operation, ensureKey included, must answer Unsupported(MISSING_ENTITLEMENT).",
    ) {
        val store: SecureKeyValueStorage = storeNamed("harness.entitlement", accessGroup = UNGRANTED_GROUP)
        val expected = StorageError.Unsupported(UnsupportedReason.MISSING_ENTITLEMENT)
        val answers: List<Pair<String, StorageResult<*>>> = listOf(
            "get" to store.get("k"),
            "put" to store.put("k", "v"),
            "remove" to store.remove("k"),
            "clear" to store.clear(),
            "ensureKey" to store.ensureKey(),
        )
        for ((operation: String, result: StorageResult<*>) in answers) {
            check("$operation answers MISSING_ENTITLEMENT", result.errorOrNull() == expected, describe(result))
        }
    },
    Case(
        id = "ensure-key-changes-nothing",
        title = "ensureKey() is non-mutating",
        manual = false,
        instructions = "On an empty store and on a store with items, ensureKey() succeeds and the Keychain is exactly as before.",
    ) {
        val name: String = fresh("harness.ensurekey")
        val service: String = RawKeychain.serviceOf(name)
        val store: SecureKeyValueStorage = storeNamed(name)
        check("ensureKey on an empty store succeeds", store.ensureKey() is StorageResult.Success)
        check("and creates nothing", RawKeychain.items(service).isEmpty())
        store.put("a", "1")
        store.put("b", "2")
        val before: List<String> = RawKeychain.items(service).map { it.toString() }.sorted()
        check("ensureKey with items succeeds", store.ensureKey() is StorageResult.Success)
        check("and changes no attribute", RawKeychain.items(service).map { it.toString() }.sorted() == before)
        check("and no value", (store.get("a") as? StorageResult.Success)?.value == "1" && (store.get("b") as? StorageResult.Success)?.value == "2")
        RawKeychain.wipe(service)
    },
    userPresenceCase("up-user-presence", "User presence: biometry or passcode", IosUserPresencePolicy.USER_PRESENCE),
    userPresenceCase("up-biometry-any", "User presence: any biometry", IosUserPresencePolicy.BIOMETRY_ANY),
    userPresenceCase("up-biometry-current-set", "User presence: current biometry set", IosUserPresencePolicy.BIOMETRY_CURRENT_SET),
    Case(
        id = "bcs-store",
        title = "BIOMETRY_CURRENT_SET re-enrollment, step 1: store",
        manual = true,
        instructions = "Simulator: Features > Face ID > Enrolled must be ON. Run this step (it may show the sheet; match the face). Then switch Enrolled OFF and ON again (a new enrollment) and run step 2.",
    ) {
        val name: String = fresh("harness.bcs")
        val store: SecureKeyValueStorage = storeNamed(name, userPresence = IosUserPresence(IosUserPresencePolicy.BIOMETRY_CURRENT_SET, "Harness: store a secret"))
        note("put: " + describe(store.put("secret", "value")))
        note("items: " + RawKeychain.items(RawKeychain.serviceOf(name)))
    },
    Case(
        id = "bcs-read",
        title = "BIOMETRY_CURRENT_SET re-enrollment, step 2: read",
        manual = true,
        instructions = "After re-enrolling. Record the result below in 05-platform-notes.md: the plan expects Undecryptable(key); the status iOS really answers is what the docs must state.",
    ) {
        val name = "harness.bcs"
        val store: SecureKeyValueStorage = storeNamed(name, userPresence = IosUserPresence(IosUserPresencePolicy.BIOMETRY_CURRENT_SET, "Harness: read the secret"))
        note("items still present (attribute-only, needs no authentication): " + RawKeychain.items(RawKeychain.serviceOf(name)))
        note("get: " + describe(store.get("secret")))
    },
    Case(
        id = "passcode-store",
        title = "Passcode removal, step 1: store",
        manual = true,
        instructions = "Needs a device (or simulator) with a passcode set. Run this step, remove the passcode in Settings, then run step 2.",
    ) {
        val name: String = fresh("harness.passcode")
        val store: SecureKeyValueStorage = storeNamed(name, accessibility = KeychainAccessibility.WHEN_PASSCODE_SET_THIS_DEVICE_ONLY)
        note("put: " + describe(store.put("secret", "value")))
        note("items: " + RawKeychain.items(RawKeychain.serviceOf(name)))
    },
    Case(
        id = "passcode-read",
        title = "Passcode removal, step 2: read",
        manual = true,
        instructions = "After removing the passcode. The plan expects the item to be gone, so get answers Success(null) (not Undecryptable); record what is observed.",
    ) {
        val name = "harness.passcode"
        val store: SecureKeyValueStorage = storeNamed(name, accessibility = KeychainAccessibility.WHEN_PASSCODE_SET_THIS_DEVICE_ONLY)
        note("items: " + RawKeychain.items(RawKeychain.serviceOf(name)))
        note("get: " + describe(store.get("secret")))
    },
)

/**
 * The shared body of the three user-presence cases. Writes a value, reads it, overwrites it and reads again, noting each
 * result; the person does what the instructions say at each sheet and compares the notes with the expectations.
 */
private fun userPresenceCase(id: String, title: String, policy: IosUserPresencePolicy): Case = Case(
    id = id,
    title = title,
    manual = true,
    instructions = "Simulator: Features > Face ID > Enrolled ON. Run this case three times and read the notes each time: " +
        "(1) Matching Face when the sheet shows: put/get/overwrite succeed. (2) Non-matching Face: the get must answer " +
        "AccessDenied(-25293) (or the sheet stays up for another try: record it). (3) Cancel the sheet: AccessDenied(-128). " +
        "Also record WHICH of the calls showed a sheet: the library documents get, and says an overwrite may.",
) {
    val name: String = fresh("harness.$id")
    val store: SecureKeyValueStorage = storeNamed(name, userPresence = IosUserPresence(policy, "Harness: authenticate to continue"))
    note("ensureKey (must never show a sheet): " + describe(store.ensureKey()))
    note("put 1 (new item): " + describe(store.put("secret", "one")))
    note("get 1 (the sheet must show): " + describe(store.get("secret")))
    note("put 2 (overwrite; does a sheet show?): " + describe(store.put("secret", "two")))
    note("get 2 (the sheet must show again, once per get): " + describe(store.get("secret")))
    note("clear (must never show a sheet): " + describe(store.clear()))
    note("items after clear: " + RawKeychain.items(RawKeychain.serviceOf(name)))
}
