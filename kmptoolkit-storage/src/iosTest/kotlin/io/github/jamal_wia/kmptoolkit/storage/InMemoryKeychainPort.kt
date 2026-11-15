package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreFoundation.CFDictionaryRef
import platform.Foundation.NSData
import platform.Security.errSecDuplicateItem
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.darwin.NSObject
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * A model of the Keychain behind [KeychainPort], faithful to the rules this module depends on and nothing more, so
 * the store's logic runs in a Kotlin/Native test binary that has no Keychain (every real `SecItem*` call there
 * answers `errSecNotAvailable`). What it models, as the platform documents it:
 *  - an item is identified by (service, account, access group, synchronizable); `add` of an existing identity is
 *    `errSecDuplicateItem`;
 *  - a query that names no access group matches the items of every group the app is in; an `add` that names none
 *    files the item under [defaultGroup];
 *  - a query that omits `kSecAttrSynchronizable` matches local items only; `any` matches both kinds; `true`/`false`
 *    match exactly;
 *  - a group the app is not in answers `-34018` (`errSecMissingEntitlement`);
 *  - `update` changes only the attributes it carries.
 * What it does not model is anything a real device decides: prompts, backups, device lock, biometry. Those are
 * harness cases, not unit tests (`storage-ios-harness`).
 *
 * Every call is recorded with a readable snapshot of its dictionaries ([describe]) before the model acts, and the
 * state is one immutable snapshot swapped by compare-and-set, so the model is safe from any number of threads.
 * [script] lets a test answer a call itself (scripted status, or a call that blocks); returning `null` falls
 * through to the model.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
internal class InMemoryKeychainPort(
    val defaultGroup: String = "TEAM.default",
    val grantedGroups: Set<String> = setOf("TEAM.default", "TEAM.shared"),
) : KeychainPort {

    /** One stored item. [data] is the bytes written; [accessible] is `null` for an item with an access control. */
    class Item(
        val service: String,
        val account: String,
        val group: String,
        val synchronizable: Boolean,
        val accessible: String?,
        val accessControl: Boolean,
        val data: NSData?,
    )

    /** One call the store made: its name and what its dictionaries said. */
    class Call(val operation: String, val query: Map<String, String>, val attributes: Map<String, String>? = null) {
        override fun toString(): String = "$operation$query${attributes?.let { " set $it" } ?: ""}"
    }

    private class State(val items: List<Item>, val calls: List<Call>)

    private val state: AtomicReference<State> = AtomicReference(State(emptyList(), emptyList()))

    /** A test's own answer for a call; return a status to force it, or `null` to let the model decide. May block. */
    @Volatile
    var script: ((operation: String, query: Map<String, String>) -> Int?)? = null

    val items: List<Item> get() = state.load().items

    val calls: List<Call> get() = state.load().calls

    fun operations(): List<String> = calls.map { it.operation }

    /** Puts [item] in the model without a call being recorded. */
    fun seed(item: Item) {
        transact { current -> State(current.items + item, current.calls) to Unit }
    }

    override fun readData(query: CFDictionaryRef): KeychainData {
        val q: Map<String, String> = describe(query)
        forced("readData", q)?.let { return KeychainData(it, null) }
        val found: Item? = transact { current ->
            State(current.items, current.calls + Call("readData", q)) to current.items.firstOrNull { matches(it, q) }
        }
        return if (found == null) KeychainData(errSecItemNotFound, null) else KeychainData(errSecSuccess, found.data)
    }

    override fun matches(query: CFDictionaryRef): Int {
        val q: Map<String, String> = describe(query)
        forced("matches", q)?.let { return it }
        val any: Boolean = transact { current ->
            State(current.items, current.calls + Call("matches", q)) to current.items.any { matches(it, q) }
        }
        return if (any) errSecSuccess else errSecItemNotFound
    }

    override fun add(query: CFDictionaryRef): Int = insert("add", query).first

    override fun addReadingAccessGroup(query: CFDictionaryRef): KeychainAccessGroup =
        insert("addReadingAccessGroup", query).let { (status: Int, group: String?) -> KeychainAccessGroup(status, group) }

    private fun insert(operation: String, query: CFDictionaryRef): Pair<Int, String?> {
        val q: Map<String, String> = describe(query)
        val data: NSData? = dataOf(query)
        forced(operation, q)?.let { return it to null }
        return transact { current ->
            val group: String = q["group"] ?: defaultGroup
            val synchronizable: Boolean = q["synchronizable"] == "true"
            val recorded: List<Call> = current.calls + Call(operation, q)
            val exists: Boolean = current.items.any {
                it.service == q["service"] && it.account == q["account"] && it.group == group && it.synchronizable == synchronizable
            }
            if (exists) {
                State(current.items, recorded) to (errSecDuplicateItem to null)
            } else {
                val item = Item(
                    service = q.getValue("service"),
                    account = q.getValue("account"),
                    group = group,
                    synchronizable = synchronizable,
                    accessible = q["accessible"],
                    accessControl = "accessControl" in q,
                    data = data,
                )
                State(current.items + item, recorded) to (errSecSuccess to group)
            }
        }
    }

    override fun update(query: CFDictionaryRef, attributes: CFDictionaryRef): Int {
        val q: Map<String, String> = describe(query)
        val a: Map<String, String> = describe(attributes)
        val data: NSData? = dataOf(attributes)
        forced("update", q)?.let { return it }
        return transact { current ->
            val recorded: List<Call> = current.calls + Call("update", q, a)
            val hit: List<Item> = current.items.filter { matches(it, q) }
            if (hit.isEmpty()) {
                State(current.items, recorded) to errSecItemNotFound
            } else {
                val changed: List<Item> = current.items.map { item ->
                    if (item !in hit) {
                        item
                    } else {
                        Item(
                            service = item.service,
                            account = item.account,
                            group = item.group,
                            synchronizable = item.synchronizable,
                            accessible = if ("accessControl" in a) null else a["accessible"] ?: item.accessible,
                            accessControl = "accessControl" in a || (item.accessControl && "accessible" !in a),
                            data = data ?: item.data,
                        )
                    }
                }
                State(changed, recorded) to errSecSuccess
            }
        }
    }

    override fun delete(query: CFDictionaryRef): Int {
        val q: Map<String, String> = describe(query)
        forced("delete", q)?.let { return it }
        return transact { current ->
            val recorded: List<Call> = current.calls + Call("delete", q)
            val remaining: List<Item> = current.items.filterNot { matches(it, q) }
            State(remaining, recorded) to if (remaining.size == current.items.size) errSecItemNotFound else errSecSuccess
        }
    }

    /** The status the test or the entitlement rule forces for this call, or `null` when the model answers. */
    private fun forced(operation: String, q: Map<String, String>): Int? {
        script?.invoke(operation, q)?.let { status ->
            record(operation, q)
            return status
        }
        val group: String? = q["group"]
        if (group != null && group !in grantedGroups) {
            record(operation, q)
            return ERR_SEC_MISSING_ENTITLEMENT
        }
        return null
    }

    private fun record(operation: String, q: Map<String, String>) {
        transact { current -> State(current.items, current.calls + Call(operation, q)) to Unit }
    }

    private fun matches(item: Item, q: Map<String, String>): Boolean {
        if (item.service != q["service"]) return false
        q["account"]?.let { if (it != item.account) return false }
        q["group"]?.let { if (it != item.group) return false }
        return when (q["synchronizable"]) {
            null, "false" -> !item.synchronizable
            "true" -> item.synchronizable
            "any" -> true
            else -> false
        }
    }

    private inline fun <R> transact(step: (State) -> Pair<State, R>): R {
        while (true) {
            val current: State = state.load()
            val (next: State, result: R) = step(current)
            if (state.compareAndSet(current, next)) return result
        }
    }
}

/**
 * [AuthenticationSessions] that count: how many sessions were opened, with which prompts, and how many were
 * invalidated. A session invalidates once however often it is told to (as `LAContext.invalidate()` is idempotent), and
 * [onInvalidate] runs on that first invalidation, which is how a test models "dismissing the sheet makes the blocked
 * call return".
 */
@OptIn(ExperimentalAtomicApi::class)
internal class CountingAuthenticationSessions : AuthenticationSessions {

    private val openedCount: AtomicInt = AtomicInt(0)
    private val invalidatedCount: AtomicInt = AtomicInt(0)
    private val promptList: AtomicReference<List<String>> = AtomicReference(emptyList())

    @Volatile
    var onInvalidate: () -> Unit = {}

    val opened: Int get() = openedCount.load()

    /** Sessions invalidated at least once: equal to [opened] when nothing leaked. */
    val invalidated: Int get() = invalidatedCount.load()

    val prompts: List<String> get() = promptList.load()

    override fun open(prompt: String): AuthenticationSession {
        openedCount.incrementAndFetch()
        while (true) {
            val current: List<String> = promptList.load()
            if (promptList.compareAndSet(current, current + prompt)) break
        }
        return object : AuthenticationSession {
            private val done: AtomicInt = AtomicInt(0)

            override val handle: Any = NSObject()

            override fun invalidate() {
                if (done.compareAndSet(0, 1)) {
                    invalidatedCount.incrementAndFetch()
                    onInvalidate()
                }
            }
        }
    }
}
