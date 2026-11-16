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
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSError
import platform.LocalAuthentication.LAContext
import platform.LocalAuthentication.LAPolicyDeviceOwnerAuthentication
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/** The access groups the host app's entitlements grant besides its default one (`ios-host/StorageHarness.entitlements`). */
internal const val SHARED_GROUP: String = "io.github.jamal-wia.kmptoolkit.storageharness.shared"
internal const val OTHER_GROUP: String = "io.github.jamal-wia.kmptoolkit.storageharness.other"

/** A group no entitlement of the host app grants: every operation on it must answer MISSING_ENTITLEMENT. */
internal const val UNGRANTED_GROUP: String = "io.github.jamal-wia.kmptoolkit.storageharness.notgranted"

/** The shape of every value the race case writes. */
private val WRITTEN_VALUE: Regex = Regex("w\\d+-\\d+")

/**
 * What one case writes down: checks that pass or fail, and observations a person (or the CHANGELOG) will read. Safe to
 * read from another thread while the case still writes (the runner reads it when a case timed out and its body may be
 * stuck in a blocking Security call).
 */
@OptIn(ExperimentalAtomicApi::class)
internal class CaseLog {
    private val lines: AtomicReference<List<String>> = AtomicReference(emptyList())
    private val failed: AtomicInt = AtomicInt(0)
    private val skippedBecause: AtomicReference<String?> = AtomicReference(null)

    val failures: Int get() = failed.load()

    /** Why the case could not run on this device (a precondition of the environment), or `null` when it ran. */
    val skipReason: String? get() = skippedBecause.load()

    private fun append(line: String) {
        while (true) {
            val current: List<String> = lines.load()
            if (lines.compareAndSet(current, current + line)) return
        }
    }

    fun note(line: String) {
        append(line)
    }

    /** A claim the library makes. A false one fails the case. */
    fun check(claim: String, holds: Boolean, evidence: String = "") {
        if (!holds) failed.incrementAndFetch()
        append((if (holds) "  ok   " else "  FAIL ") + claim + if (evidence.isEmpty()) "" else "  [$evidence]")
    }

    /**
     * Declares that the environment lacks what the case needs (for example a passcode): the case is reported
     * `SKIPPED` with [reason], neither passed nor failed, so a simulator without the precondition cannot show a
     * false `PASS` or a false `FAIL` of the library.
     */
    fun skip(reason: String) {
        skippedBecause.store(reason)
        append("  SKIPPED $reason")
    }

    fun text(): String = lines.load().joinToString("\n")
}

/**
 * A step a later check depends on (a seeding put, a clear before the look at the Keychain). A failed library call
 * fails the case here, in its own line, instead of letting a later check hold vacuously on an empty Keychain.
 */
internal fun CaseLog.must(step: String, result: StorageResult<*>) {
    note("$step: ${describe(result)}")
    check("$step succeeded", result is StorageResult.Success, describe(result))
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

/**
 * Why a case that writes an item behind user presence (a passcode, or Face ID / Touch ID) cannot run on this device,
 * or `null` when device owner authentication is available. This is the harness asking the system, never the library:
 * a stock simulator has neither a passcode nor an enrolled face, and there the put of such an item fails by the
 * device's own rule, which says nothing about the library.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun missingDeviceOwnerAuthentication(): String? = memScoped {
    val error: ObjCObjectVar<NSError?> = alloc<ObjCObjectVar<NSError?>>()
    if (LAContext().canEvaluatePolicy(LAPolicyDeviceOwnerAuthentication, error.ptr)) {
        null
    } else {
        "this device has no passcode and no enrolled biometry (canEvaluatePolicy(deviceOwnerAuthentication) answered no, " +
            "LAError code ${error.value?.code ?: "none"}); enable Face ID (Features > Face ID > Enrolled) or set a passcode and run the case again"
    }
}

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
        id = "put-remove-race",
        title = "put() against concurrent remove() and clear() (breaker M1)",
        manual = false,
        instructions = "Eight writers put three keys while four removers remove them and two stores clear the default group, all at once. No put, remove or clear may fail (a put that lost its add and then met a remove must retry, not report -25300), and every key ends absent or holding one whole value.",
    ) {
        val name: String = fresh("harness.putrace")
        val service: String = RawKeychain.serviceOf(name)
        val keys: List<String> = listOf("a", "b", "c")
        val results: List<StorageResult<Unit>> = coroutineScope {
            val writers = (0 until 8).map { writer ->
                async(Dispatchers.Default) {
                    val store: SecureKeyValueStorage = storeNamed(name)
                    (0 until 40).map { step -> store.put(keys[(writer + step) % keys.size], "w$writer-$step") }
                }
            }
            val removers = (0 until 4).map { remover ->
                async(Dispatchers.Default) {
                    val store: SecureKeyValueStorage = storeNamed(name)
                    (0 until 40).map { step -> store.remove(keys[(remover + step) % keys.size]) }
                }
            }
            val clearers = (0 until 2).map {
                async(Dispatchers.Default) {
                    val store: SecureKeyValueStorage = storeNamed(name)
                    (0 until 20).map { store.clear() }
                }
            }
            (writers + removers + clearers).awaitAll().flatten()
        }
        val failed: List<StorageResult.Failure> = results.filterIsInstance<StorageResult.Failure>()
        check("no put, remove or clear failed (${results.size} calls)", failed.isEmpty(), failed.take(3).toString())
        val items: List<RawKeychain.Item> = RawKeychain.items(service)
        check("at most one item per key remains", items.size <= keys.size && items.map { it.account }.toSet().size == items.size, items.toString())
        check("no probe item is left", RawKeychain.items("$service.accessgroupprobe").isEmpty())
        for (key: String in keys) {
            val read: StorageResult<String?> = storeNamed(name).get(key)
            val value: String? = (read as? StorageResult.Success)?.value
            check("$key reads absent or one whole value", read is StorageResult.Success && (value == null || WRITTEN_VALUE.matches(value)), describe(read))
        }
        RawKeychain.wipe(service)
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
        must("put b", b.put("x", "from-b"))
        must("put other", other.put("y", "from-other-group"))
        check("b wrote into the shared group", RawKeychain.items(RawKeychain.serviceOf(name), SHARED_GROUP).size == 1)
        must("clear a", a.clear())
        check("b's item is gone", b.get("x") == StorageResult.Success(null), describe(b.get("x")))
        check("the other group's item survived", other.get("y") == StorageResult.Success("from-other-group"), describe(other.get("y")))
        RawKeychain.wipe(RawKeychain.serviceOf(name))
    },
    Case(
        id = "null-group-clear",
        title = "accessGroup = null clear() and a same-name store in a shared group (I-2b)",
        manual = false,
        instructions = "Confirms or refutes defect I-2b and checks the 6.0.0 fix. Step 1 reproduces the old clear() by hand (a delete by service naming no group) and records whether it reaches the shared group. Step 2 runs the library's clear() on the default-group store: the shared-group store must keep its item. Step 3 seeds a probe of another process in the shared group: clear() must not adopt it (the default-group store is cleared, the shared one and the foreign probe are untouched). Step 4 runs six clears at once.",
    ) {
        val name: String = fresh("harness.nullgroup")
        val service: String = RawKeychain.serviceOf(name)
        val defaultStore: SecureKeyValueStorage = storeNamed(name)
        val sharedStore: SecureKeyValueStorage = storeNamed(name, accessGroup = SHARED_GROUP)
        val probeService = "$service.accessgroupprobe"

        must("put default", defaultStore.put("k", "default-group"))
        must("put shared", sharedStore.put("k", "shared-group"))
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
        must("put default", defaultStore.put("k", "default-group"))
        must("put shared", sharedStore.put("k", "shared-group"))
        must("clear default store", defaultStore.clear())
        check("the shared-group store kept its item", sharedStore.get("k") == StorageResult.Success("shared-group"), describe(sharedStore.get("k")))
        check("the default group's item is gone", RawKeychain.items(service).none { it.group != SHARED_GROUP }, RawKeychain.items(service).toString())
        check("no probe item is left", RawKeychain.items(probeService).isEmpty(), RawKeychain.items(probeService).toString())

        // Step 3 (breaker M2): a probe another process left in the shared group (an extension that lists the shared
        // group first and was killed between its probe add and delete) must not be taken for this app's default group.
        RawKeychain.wipe(service)
        must("put default", defaultStore.put("k", "default-group"))
        must("put shared", sharedStore.put("k", "shared-group"))
        val seeded: Int = RawKeychain.addItem(probeService, "kmptoolkit.accessgroupprobe.foreign", SHARED_GROUP)
        check("a foreign probe was seeded in the shared group", seeded == 0, "status $seeded")
        must("clear default store next to the foreign probe", defaultStore.clear())
        check("the shared-group store kept its item", sharedStore.get("k") == StorageResult.Success("shared-group"), describe(sharedStore.get("k")))
        check("the default group's item is gone", RawKeychain.items(service).none { it.group != SHARED_GROUP }, RawKeychain.items(service).toString())
        check("the foreign probe is untouched", RawKeychain.items(probeService, SHARED_GROUP).size == 1, RawKeychain.items(probeService).toString())
        check("no probe is left in the default group", RawKeychain.items(probeService).none { it.group != SHARED_GROUP }, RawKeychain.items(probeService).toString())

        // Step 4: six clears of default-group stores at once (each probes under its own account).
        RawKeychain.wipe(service)
        RawKeychain.wipe(probeService)
        must("put default", defaultStore.put("k", "default-group"))
        must("put shared", sharedStore.put("k", "shared-group"))
        val clears: List<StorageResult<Unit>> = coroutineScope {
            (0 until 6).map { async(Dispatchers.Default) { storeNamed(name).clear() } }.awaitAll()
        }
        check("six concurrent clears all succeeded", clears.all { it is StorageResult.Success }, clears.filterIsInstance<StorageResult.Failure>().take(3).toString())
        check("the shared-group store still has its item", sharedStore.get("k") == StorageResult.Success("shared-group"), describe(sharedStore.get("k")))
        check("no probe item is left", RawKeychain.items(probeService).isEmpty(), RawKeychain.items(probeService).toString())
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
        must("put synced", synced.put("k", "cloud"))
        must("put local", local.put("k", "device"))
        RawKeychain.items(service).forEach { note("  item: $it") }
        check("the synchronizable item exists", RawKeychain.items(service).any { it.synchronizable == "1" })
        check("each store reads only its own kind", synced.get("k") == StorageResult.Success("cloud") && local.get("k") == StorageResult.Success("device"))
        must("clear synced", synced.clear())
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
        must("put (WHEN_UNLOCKED)", storeNamed(name, accessibility = KeychainAccessibility.WHEN_UNLOCKED).put("k", "1"))
        val created: String? = RawKeychain.items(service).singleOrNull()?.accessible
        note("accessible after create: $created")
        must("put (AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY)", storeNamed(name).put("k", "2"))
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
        must("put a", store.put("a", "1"))
        must("put b", store.put("b", "2"))
        val before: List<String> = RawKeychain.items(service).map { it.toString() }.sorted()
        check("ensureKey with items succeeds", store.ensureKey() is StorageResult.Success)
        check("and changes no attribute", RawKeychain.items(service).map { it.toString() }.sorted() == before)
        check("and no value", store.get("a") == StorageResult.Success("1") && store.get("b") == StorageResult.Success("2"))
        RawKeychain.wipe(service)
    },
    Case(
        id = "ensure-key-user-presence",
        title = "ensureKey() of a user-presence store that has items (m1)",
        manual = false,
        instructions = "A USER_PRESENCE store writes one new item (a write of a new item shows no sheet), then ensureKey() runs: it must show no sheet and answer Success. If it answers Unavailable the attribute-only query reports errSecInteractionNotAllowed for an item that needs authentication, and ensureKey of a user-presence store must map that status to Success (a locked device answers the same status for a plain store). Needs a passcode or Face ID enrolled: without one the case is SKIPPED with that reason (never PASS, never FAIL).",
    ) {
        missingDeviceOwnerAuthentication()?.let { reason: String ->
            skip(reason)
            return@Case
        }
        val name: String = fresh("harness.ensureuserpresence")
        val service: String = RawKeychain.serviceOf(name)
        val store: SecureKeyValueStorage = storeNamed(name, userPresence = IosUserPresence(IosUserPresencePolicy.USER_PRESENCE, "Harness: authenticate to continue"))
        must("put (a new item)", store.put("secret", "one"))
        check("the item exists", RawKeychain.items(service).size == 1, RawKeychain.items(service).toString())
        val ensured: StorageResult<Unit> = store.ensureKey()
        note("ensureKey with an item that needs authentication: " + describe(ensured))
        check("ensureKey of a user-presence store that has items is Success", ensured is StorageResult.Success, describe(ensured))
        must("clear", store.clear())
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
        must("put", store.put("secret", "value"))
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
        must("put", store.put("secret", "value"))
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
    must("put 1 (a new item shows no sheet)", store.put("secret", "one"))
    val ensured: StorageResult<Unit> = store.ensureKey()
    note("ensureKey after put 1 (must never show a sheet): " + describe(ensured))
    check("ensureKey of a store that has an item is Success (Unavailable here means the mapping needs the m1 fix)", ensured is StorageResult.Success, describe(ensured))
    note("get 1 (the sheet must show): " + describe(store.get("secret")))
    note("put 2 (overwrite; does a sheet show?): " + describe(store.put("secret", "two")))
    note("get 2 (the sheet must show again, once per get): " + describe(store.get("secret")))
    must("clear (must never show a sheet)", store.clear())
    note("items after clear: " + RawKeychain.items(RawKeychain.serviceOf(name)))
}
