package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** One recorded call of the fake binding: the seam function and a redacted description of its arguments. */
internal class RecordedCall(val name: String, val detail: String) {
    override fun toString(): String = "$name($detail)"
}

/**
 * A scriptable in-memory [NativeKeyStoreBinding] for every test above the seam (plan 3.5, 6.1). It models the three
 * operating-system stores just closely enough to exercise the lifecycle: items by (service, account), a reversible
 * DPAPI stand-in bound to its entropy, and Secret Service items with attributes, a locked flag, a locked default
 * collection, the portal flag and a missing `secret_password_search_sync` symbol.
 *
 * Every call is recorded (thread-safe, in call order). A test scripts behaviour with:
 * - [before], run on the calling thread before every call: block on a latch to hang, throw to simulate a
 *   `LinkageError`;
 * - [forcedStatus] and [forcedSecretStatus], a sticky status per function name that replaces the result.
 *
 * Secrets handed out are copies, so a caller that wipes what it received never alters the store. It is installed only
 * through the internal [FixedBindingSource]; nothing public or global selects it. Recorded details name sizes, never
 * secret bytes.
 */
internal class FakeNativeKeyStoreBinding : NativeKeyStoreBinding {

    private val recorded: CopyOnWriteArrayList<RecordedCall> = CopyOnWriteArrayList()

    /** Every call so far, in order. */
    val calls: List<RecordedCall> get() = recorded.toList()

    /** The names of the calls so far, in order. */
    val callNames: List<String> get() = recorded.map { it.name }

    /** How many calls of [name] so far. */
    fun count(name: String): Int = recorded.count { it.name == name }

    /** Runs on the calling thread before every call. May block or throw. */
    @Volatile
    var before: (String) -> Unit = {}

    /** A sticky status per function name for macOS and Windows: the call returns it instead of acting. */
    val forcedStatus: ConcurrentHashMap<String, Int> = ConcurrentHashMap()

    /** A sticky Secret Service status per function name. */
    val forcedSecretStatus: ConcurrentHashMap<String, RawStatus> = ConcurrentHashMap()

    /** macOS items: (service, account) to the secret. */
    val macItems: ConcurrentHashMap<Pair<String, String>, ByteArray> = ConcurrentHashMap()

    /** `true` makes every DPAPI call fail with 1312 (no logon session). */
    @Volatile
    var dpapiNoSession: Boolean = false

    /** One Secret Service item. */
    class SecretItem(val label: String, val secret: ByteArray, @Volatile var locked: Boolean = false)

    /** Secret Service items by their attributes. */
    val secretItems: ConcurrentHashMap<Map<String, String>, SecretItem> = ConcurrentHashMap()

    @Volatile
    var defaultCollectionIsLocked: Boolean = false

    @Volatile
    var portal: Boolean = false

    @Volatile
    var searchSymbolMissing: Boolean = false

    /** When an unlock prompt would appear (`allowUi` and a locked match): `true` = the user dismisses it. */
    @Volatile
    var promptDismissed: Boolean = false

    private val prompts: AtomicInteger = AtomicInteger()

    /** How many unlock prompts the fake showed. */
    val promptCount: Int get() = prompts.get()

    private fun enter(name: String, detail: String = "") {
        recorded += RecordedCall(name, detail)
        before(name)
    }

    private fun describe(attributes: Map<String, String>): String =
        attributes.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }

    private val mac: MacKeychainCalls = object : MacKeychainCalls {
        override fun copyGenericPassword(service: String, account: String, allowUi: Boolean): RawResult<ByteArray?> {
            enter("copyGenericPassword", "$service|$account|ui=$allowUi")
            forcedStatus["copyGenericPassword"]?.let { return RawResult.Failure(RawStatus(it)) }
            val secret: ByteArray = macItems[service to account]
                ?: return RawResult.Failure(RawStatus(ERR_SEC_ITEM_NOT_FOUND))
            return RawResult.Success(secret.copyOf())
        }

        override fun addGenericPassword(service: String, account: String, label: String, secret: ByteArray, allowUi: Boolean): Int {
            enter("addGenericPassword", "$service|$account|${secret.size} bytes|ui=$allowUi")
            forcedStatus["addGenericPassword"]?.let { return it }
            if (macItems.putIfAbsent(service to account, secret.copyOf()) != null) return ERR_SEC_DUPLICATE_ITEM
            return 0
        }

        override fun deleteGenericPassword(service: String, account: String, allowUi: Boolean): Int {
            enter("deleteGenericPassword", "$service|$account|ui=$allowUi")
            forcedStatus["deleteGenericPassword"]?.let { return it }
            return if (macItems.remove(service to account) != null) 0 else ERR_SEC_ITEM_NOT_FOUND
        }

        override fun probe(allowUi: Boolean): Int {
            enter("probe", "ui=$allowUi")
            return forcedStatus["probe"] ?: ERR_SEC_ITEM_NOT_FOUND
        }
    }

    private val dpapi: DpapiCalls = object : DpapiCalls {
        override fun protect(data: ByteArray, entropy: ByteArray?): RawResult<ByteArray> {
            enter("protect", "${data.size} bytes")
            if (dpapiNoSession) return RawResult.Failure(RawStatus(ERROR_NO_SUCH_LOGON_SESSION))
            forcedStatus["protect"]?.let { return RawResult.Failure(RawStatus(it)) }
            val body = ByteArray(data.size) { (data[it].toInt() xor MASK).toByte() }
            return RawResult.Success(DPAPI_MAGIC + entropyTag(entropy) + body)
        }

        override fun unprotect(blob: ByteArray, entropy: ByteArray?): RawResult<ByteArray> {
            enter("unprotect", "${blob.size} bytes")
            if (dpapiNoSession) return RawResult.Failure(RawStatus(ERROR_NO_SUCH_LOGON_SESSION))
            forcedStatus["unprotect"]?.let { return RawResult.Failure(RawStatus(it)) }
            val header: Int = DPAPI_MAGIC.size + ENTROPY_TAG_BYTES
            val wellFormed: Boolean = blob.size >= header &&
                blob.copyOfRange(0, DPAPI_MAGIC.size).contentEquals(DPAPI_MAGIC) &&
                blob.copyOfRange(DPAPI_MAGIC.size, header).contentEquals(entropyTag(entropy))
            if (!wellFormed) return RawResult.Failure(RawStatus(ERROR_INVALID_DATA))
            return RawResult.Success(ByteArray(blob.size - header) { (blob[header + it].toInt() xor MASK).toByte() })
        }
    }

    private val secret: SecretServiceCalls = object : SecretServiceCalls {
        override fun lookup(attributes: Map<String, String>, allowUi: Boolean): RawResult<SecretLookup> {
            enter("lookup", describe(attributes) + "|ui=$allowUi")
            if (searchSymbolMissing) return RawResult.Failure(RawStatus.SYMBOL_MISSING)
            forcedSecretStatus["lookup"]?.let { return RawResult.Failure(it) }
            val matches: List<SecretItem> =
                secretItems.filter { (key: Map<String, String>, _) -> attributes.all { key[it.key] == it.value } }.values.toList()
            if (matches.isEmpty()) return RawResult.Success(SecretLookup.NotFound)
            val first: SecretItem = matches.first()
            if (first.locked) {
                if (!allowUi) return RawResult.Success(SecretLookup.LockedMatch)
                prompts.incrementAndGet()
                if (promptDismissed) return RawResult.Success(SecretLookup.LockedMatch)
            }
            return RawResult.Success(SecretLookup.Found(first.secret.copyOf()))
        }

        override fun store(label: String, attributes: Map<String, String>, secret: ByteArray): SecretStatus {
            enter("store", describe(attributes) + "|${secret.size} bytes")
            if (searchSymbolMissing) return RawStatus.SYMBOL_MISSING
            forcedSecretStatus["store"]?.let { return it }
            secretItems[attributes.toMap()] = SecretItem(label, secret.copyOf())
            return RawStatus.SUCCESS
        }

        override fun clear(attributes: Map<String, String>): SecretStatus {
            enter("clear", describe(attributes))
            forcedSecretStatus["clear"]?.let { return it }
            secretItems.keys.filter { key -> attributes.all { key[it.key] == it.value } }.forEach { secretItems.remove(it) }
            return RawStatus.SUCCESS
        }

        override fun defaultCollectionLocked(): RawResult<Boolean> {
            enter("defaultCollectionLocked")
            forcedSecretStatus["defaultCollectionLocked"]?.let { return RawResult.Failure(it) }
            return RawResult.Success(defaultCollectionIsLocked)
        }

        override fun backendIsPortal(): RawResult<Boolean> {
            enter("backendIsPortal")
            forcedSecretStatus["backendIsPortal"]?.let { return RawResult.Failure(it) }
            return RawResult.Success(portal)
        }
    }

    override fun macOs(): MacKeychainCalls = mac

    override fun windows(): DpapiCalls = dpapi

    override fun linux(): SecretServiceCalls = secret

    private fun entropyTag(entropy: ByteArray?): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(entropy ?: ByteArray(0)).copyOf(ENTROPY_TAG_BYTES)

    companion object {
        const val ERR_SEC_ITEM_NOT_FOUND: Int = -25300
        const val ERR_SEC_DUPLICATE_ITEM: Int = -25299
        const val ERROR_NO_SUCH_LOGON_SESSION: Int = 1312
        const val ERROR_INVALID_DATA: Int = 13
        private const val ENTROPY_TAG_BYTES: Int = 8
        private const val MASK: Int = 0x5A
        private val DPAPI_MAGIC: ByteArray = byteArrayOf(0x01, 0x00, 0x00, 0x00, 0xD0.toByte(), 0x8C.toByte())
    }
}
