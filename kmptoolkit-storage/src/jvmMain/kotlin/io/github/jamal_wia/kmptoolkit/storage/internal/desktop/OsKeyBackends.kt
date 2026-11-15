package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.UnsupportedReason
import java.io.File
import java.util.Base64

/** What the Linux backend reads from the process: injectable so every branch runs on any machine. */
internal class SecureStoreEnvironment(
    /** `System.getenv`. */
    val getenv: (String) -> String? = { name: String -> System.getenv(name) },
    /** Whether a file exists (the session bus socket). */
    val exists: (String) -> Boolean = { path: String -> File(path).exists() },
) {
    /** Linux: whether a session bus is reachable; checked in milliseconds before any native call (plan 4.7). */
    fun hasSessionBus(): Boolean =
        ErrorMapping.linuxHasSessionBus(getenv("DBUS_SESSION_BUS_ADDRESS"), getenv("XDG_RUNTIME_DIR"), exists)
}

/** A new data key and the header that identifies where it is kept. [dataKey] is owned by the plan that holds it. */
internal class NewKey(val header: HeaderBody, val dataKey: ByteArray)

/**
 * Runs the native calls of one store: through the executor, with the right limit. A call that can show a dialog
 * (`allowUi`) has no limit and stays cancellable; every other call is limited to [NativeExecutor.NON_INTERACTIVE_LIMIT].
 */
internal class NativeCaller(private val executor: NativeExecutor, val allowUi: Boolean) {
    suspend fun <T> call(interactive: Boolean = allowUi, discard: (T) -> Unit = {}, block: () -> T): NativeOutcome<T> =
        executor.call(if (interactive) null else NativeExecutor.NON_INTERACTIVE_LIMIT, discard, block)

    /** A call that can never show a dialog (DPAPI): always limited. */
    suspend fun <T> callLimited(discard: (T) -> Unit = {}, block: () -> T): NativeOutcome<T> = call(interactive = false, discard, block)
}

/**
 * The per-OS half of an OS-key store above the seam: the raw calls and their error mapping (4.5-4.7), nothing of the
 * lifecycle. One implementation per OS; the engine never branches on the OS.
 */
internal interface OsKeyBackend {
    /** The kind of file this OS writes ([StoreKind.OS_ITEM] on macOS and Linux, [StoreKind.DPAPI] on Windows). */
    val kind: StoreKind

    /** Whether [header] names [identity]. A foreign header is "key lost" before any OS call (plan 4.4 rules). */
    fun identityMatches(header: HeaderBody, identity: StoreIdentity): Boolean

    /** The key of [header] for [identity], a [Verdict.NotFound], or the typed failure. Off the lock. */
    suspend fun fetch(header: HeaderBody, identity: StoreIdentity, operation: StorageOperation, key: String?): Verdict<ByteArray>

    /** Before anything is touched by a create: a refusal that must not quarantine or write (locked collection without UI, no session). */
    suspend fun preflight(operation: StorageOperation, key: String?): StorageError?

    /** A new data key and its header for [identity] (DPAPI protects the key here). */
    suspend fun newKey(identity: StoreIdentity, operation: StorageOperation, key: String?, newDataKey: () -> ByteArray): Verdict<NewKey>

    /** Puts [dataKey] into the OS item at [identity]: delete (not-found is fine), then add. Windows has no item. */
    suspend fun install(identity: StoreIdentity, dataKey: ByteArray, operation: StorageOperation, key: String?): StorageError?
}

private fun <T> failed(outcome: NativeOutcome<*>, operation: StorageOperation, key: String?): Verdict<T> =
    Verdict.Failed(ErrorMapping.nativeOutcome(outcome, operation, key))

private fun wipeKey(raw: RawResult<ByteArray?>) {
    if (raw is RawResult.Success) raw.value?.fill(0)
}

/** macOS: the login keychain, one generic password per store identity (plan 4.5). */
internal class MacBackend(
    private val calls: MacKeychainCalls,
    /** The item label the app chose; `null` = the identity's service. */
    private val label: String?,
    private val caller: NativeCaller,
) : OsKeyBackend {
    override val kind: StoreKind = StoreKind.OS_ITEM

    override fun identityMatches(header: HeaderBody, identity: StoreIdentity): Boolean =
        header is HeaderBody.OsItem && header.osTag == SecureFileFormat.OS_TAG_MACOS &&
            header.fingerprint.contentEquals(identity.headerFingerprint)

    override suspend fun fetch(header: HeaderBody, identity: StoreIdentity, operation: StorageOperation, key: String?): Verdict<ByteArray> {
        val outcome: NativeOutcome<RawResult<ByteArray?>> = caller.call(discard = ::wipeKey) {
            calls.copyGenericPassword(identity.service, identity.macAccount, caller.allowUi)
        }
        val raw: RawResult<ByteArray?> = (outcome as? NativeOutcome.Completed)?.value ?: return failed(outcome, operation, key)
        return when (raw) {
            is RawResult.Failure -> ErrorMapping.macCopy(raw.status.code, caller.allowUi, operation, key)
            is RawResult.Success -> {
                val data: ByteArray = raw.value ?: return Verdict.NotFound
                // An item of the wrong size cannot be this store's key: the same as an item that fails the key-check.
                if (data.size == SecureFileFormat.DATA_KEY_BYTES) Verdict.Ok(data) else {
                    data.fill(0)
                    Verdict.NotFound
                }
            }
        }
    }

    override suspend fun preflight(operation: StorageOperation, key: String?): StorageError? = null

    override suspend fun newKey(identity: StoreIdentity, operation: StorageOperation, key: String?, newDataKey: () -> ByteArray): Verdict<NewKey> =
        Verdict.Ok(NewKey(HeaderBody.OsItem(identity.headerFingerprint, SecureFileFormat.OS_TAG_MACOS), newDataKey()))

    override suspend fun install(identity: StoreIdentity, dataKey: ByteArray, operation: StorageOperation, key: String?): StorageError? {
        val itemLabel: String = label ?: identity.service
        val deleted: NativeOutcome<Int> = caller.call { calls.deleteGenericPassword(identity.service, identity.macAccount, caller.allowUi) }
        val deleteStatus: Int = (deleted as? NativeOutcome.Completed)?.value ?: return ErrorMapping.nativeOutcome(deleted, operation, key)
        (ErrorMapping.macDelete(deleteStatus, operation, key) as? Verdict.Failed)?.let { return it.error }
        // A copy owned by the native call: a call that outlives its caller's time limit may still be reading it, so only the
        // native thread wipes it, after the call returns.
        val secret: ByteArray = dataKey.copyOf()
        val added: NativeOutcome<Int> = caller.call {
            try {
                calls.addGenericPassword(identity.service, identity.macAccount, itemLabel, secret, caller.allowUi)
            } finally {
                secret.fill(0)
            }
        }
        val addStatus: Int = (added as? NativeOutcome.Completed)?.value ?: return ErrorMapping.nativeOutcome(added, operation, key)
        return (ErrorMapping.macAdd(addStatus, operation, key) as? Verdict.Failed)?.error
    }
}

/** Windows: DPAPI wraps the data key and the blob lives in the file header (plan 4.6). There is no OS item. */
internal class WindowsBackend(
    private val calls: DpapiCalls,
    private val caller: NativeCaller,
) : OsKeyBackend {
    override val kind: StoreKind = StoreKind.DPAPI

    // The entropy binds a blob to the store name, not to the directory: a file moved to another name fails to unprotect.
    override fun identityMatches(header: HeaderBody, identity: StoreIdentity): Boolean = header is HeaderBody.Dpapi

    override suspend fun fetch(header: HeaderBody, identity: StoreIdentity, operation: StorageOperation, key: String?): Verdict<ByteArray> {
        val blob: ByteArray = (header as HeaderBody.Dpapi).blob
        val outcome: NativeOutcome<RawResult<ByteArray>> = caller.callLimited(discard = ::wipeSuccess) {
            calls.unprotect(blob, identity.windowsEntropy)
        }
        val raw: RawResult<ByteArray> = (outcome as? NativeOutcome.Completed)?.value ?: return failed(outcome, operation, key)
        return when (raw) {
            is RawResult.Success ->
                if (raw.value.size == SecureFileFormat.DATA_KEY_BYTES) Verdict.Ok(raw.value) else {
                    raw.value.fill(0)
                    Verdict.NotFound
                }
            is RawResult.Failure -> when (val trip: ErrorMapping.RoundTrip = roundTrip(identity, operation, key)) {
                // DPAPI works for this user and still cannot unwrap this blob: the key is lost.
                ErrorMapping.RoundTrip.Worked -> Verdict.NotFound
                is ErrorMapping.RoundTrip.Failed -> Verdict.Failed(ErrorMapping.windowsUnprotectFailure(raw.status.code, trip))
            }
        }
    }

    /** The 1-byte throwaway protect and unprotect of 4.6 (S11): tells a broken DPAPI from a blob that is not ours. */
    suspend fun roundTrip(identity: StoreIdentity, operation: StorageOperation, key: String?): ErrorMapping.RoundTrip {
        val probe: ByteArray = byteArrayOf(ROUND_TRIP_BYTE)
        val protectedOutcome: NativeOutcome<RawResult<ByteArray>> = caller.callLimited { calls.protect(probe, identity.windowsEntropy) }
        val protectedRaw: RawResult<ByteArray> = (protectedOutcome as? NativeOutcome.Completed)?.value
            ?: return ErrorMapping.RoundTrip.Failed(CODE_NATIVE_FAILURE)
        val blob: ByteArray = when (protectedRaw) {
            is RawResult.Failure -> return ErrorMapping.RoundTrip.Failed(protectedRaw.status.code)
            is RawResult.Success -> protectedRaw.value
        }
        val backOutcome: NativeOutcome<RawResult<ByteArray>> =
            caller.callLimited(discard = ::wipeSuccess) { calls.unprotect(blob, identity.windowsEntropy) }
        val backRaw: RawResult<ByteArray> = (backOutcome as? NativeOutcome.Completed)?.value
            ?: return ErrorMapping.RoundTrip.Failed(CODE_NATIVE_FAILURE)
        return when (backRaw) {
            is RawResult.Failure -> ErrorMapping.RoundTrip.Failed(backRaw.status.code)
            is RawResult.Success -> {
                backRaw.value.fill(0)
                ErrorMapping.RoundTrip.Worked
            }
        }
    }

    override suspend fun preflight(operation: StorageOperation, key: String?): StorageError? = null

    override suspend fun newKey(identity: StoreIdentity, operation: StorageOperation, key: String?, newDataKey: () -> ByteArray): Verdict<NewKey> {
        val dataKey: ByteArray = newDataKey()
        val plain: ByteArray = dataKey.copyOf() // owned by the native call (see MacBackend.install)
        val outcome: NativeOutcome<RawResult<ByteArray>> = caller.callLimited {
            try {
                calls.protect(plain, identity.windowsEntropy)
            } finally {
                plain.fill(0)
            }
        }
        val raw: RawResult<ByteArray>? = (outcome as? NativeOutcome.Completed)?.value
        if (raw == null) {
            dataKey.fill(0)
            return failed(outcome, operation, key)
        }
        return when (raw) {
            is RawResult.Failure -> {
                dataKey.fill(0)
                Verdict.Failed(ErrorMapping.windowsProtectFailure(raw.status.code, operation, key))
            }
            is RawResult.Success ->
                if (raw.value.isEmpty() || raw.value.size > SecureFileFormat.MAX_DPAPI_BLOB_BYTES) {
                    dataKey.fill(0)
                    Verdict.Failed(StorageError.OperationFailed(operation, key = key, cause = IllegalStateException("DPAPI returned a blob of an unusable size")))
                } else {
                    Verdict.Ok(NewKey(HeaderBody.Dpapi(raw.value), dataKey))
                }
        }
    }

    override suspend fun install(identity: StoreIdentity, dataKey: ByteArray, operation: StorageOperation, key: String?): StorageError? = null

    private fun wipeSuccess(raw: RawResult<ByteArray>) {
        if (raw is RawResult.Success) raw.value.fill(0)
    }

    private companion object {
        const val ROUND_TRIP_BYTE: Byte = 0x5A
        /** A round trip whose native call timed out or threw: DPAPI is not usable right now (not a store verdict). */
        const val CODE_NATIVE_FAILURE: Int = -1
    }
}

/** Linux: libsecret, an item with two attributes holding the base64 data key (plan 4.7). */
internal class LinuxBackend(
    private val calls: SecretServiceCalls,
    /** The item label the app chose; `null` = the identity's service. */
    private val label: String?,
    private val caller: NativeCaller,
    private val environment: SecureStoreEnvironment,
) : OsKeyBackend {
    override val kind: StoreKind = StoreKind.OS_ITEM

    override fun identityMatches(header: HeaderBody, identity: StoreIdentity): Boolean =
        header is HeaderBody.OsItem && header.osTag == SecureFileFormat.OS_TAG_LINUX &&
            header.fingerprint.contentEquals(identity.headerFingerprint)

    private fun noSession(): StorageError? =
        if (environment.hasSessionBus()) null else StorageError.Unsupported(UnsupportedReason.NO_USER_SESSION)

    override suspend fun fetch(header: HeaderBody, identity: StoreIdentity, operation: StorageOperation, key: String?): Verdict<ByteArray> {
        noSession()?.let { return Verdict.Failed(it) }
        val outcome: NativeOutcome<RawResult<SecretLookup>> = caller.call(discard = ::wipeLookup) {
            calls.lookup(identity.linuxAttributes, caller.allowUi)
        }
        val raw: RawResult<SecretLookup> = (outcome as? NativeOutcome.Completed)?.value ?: return failed(outcome, operation, key)
        return when (raw) {
            is RawResult.Failure -> Verdict.Failed(ErrorMapping.linuxStatus(raw.status, operation, key))
            is RawResult.Success -> when (val found: SecretLookup = raw.value) {
                SecretLookup.NotFound -> Verdict.NotFound
                SecretLookup.LockedMatch -> Verdict.Failed(ErrorMapping.linuxLockedMatch(caller.allowUi))
                is SecretLookup.Found -> decode(found.secret)
            }
        }
    }

    /** The stored secret is base64 text; anything else, or a wrong size, cannot be this store's key (the key-check fails: lost). */
    private fun decode(secret: ByteArray): Verdict<ByteArray> {
        try {
            val key: ByteArray = try {
                Base64.getDecoder().decode(secret)
            } catch (_: IllegalArgumentException) {
                return Verdict.NotFound
            }
            if (key.size == SecureFileFormat.DATA_KEY_BYTES) return Verdict.Ok(key)
            key.fill(0)
            return Verdict.NotFound
        } finally {
            secret.fill(0)
        }
    }

    override suspend fun preflight(operation: StorageOperation, key: String?): StorageError? {
        noSession()?.let { return it }
        if (caller.allowUi) return null
        // Without UI, storing into a locked collection (or creating one) would prompt: refuse before touching the file.
        val outcome: NativeOutcome<RawResult<Boolean>> = caller.callLimited { calls.defaultCollectionLocked() }
        val raw: RawResult<Boolean> = (outcome as? NativeOutcome.Completed)?.value ?: return ErrorMapping.nativeOutcome(outcome, operation, key)
        return when (raw) {
            is RawResult.Failure -> ErrorMapping.linuxStatus(raw.status, operation, key)
            is RawResult.Success -> if (raw.value) ErrorMapping.linuxCollectionLockedWithoutUi() else null
        }
    }

    override suspend fun newKey(identity: StoreIdentity, operation: StorageOperation, key: String?, newDataKey: () -> ByteArray): Verdict<NewKey> =
        Verdict.Ok(NewKey(HeaderBody.OsItem(identity.headerFingerprint, SecureFileFormat.OS_TAG_LINUX), newDataKey()))

    override suspend fun install(identity: StoreIdentity, dataKey: ByteArray, operation: StorageOperation, key: String?): StorageError? {
        val itemLabel: String = label ?: identity.service
        val cleared: NativeOutcome<SecretStatus> = caller.call { calls.clear(identity.linuxAttributes) }
        val clearStatus: SecretStatus = (cleared as? NativeOutcome.Completed)?.value ?: return ErrorMapping.nativeOutcome(cleared, operation, key)
        if (!clearStatus.isSuccess) return ErrorMapping.linuxStatus(clearStatus, operation, key)
        val secret: ByteArray = Base64.getEncoder().encode(dataKey) // owned by the native call (see MacBackend.install)
        val stored: NativeOutcome<SecretStatus> = caller.call {
            try {
                calls.store(itemLabel, identity.linuxAttributes, secret)
            } finally {
                secret.fill(0)
            }
        }
        val status: SecretStatus = (stored as? NativeOutcome.Completed)?.value ?: return ErrorMapping.nativeOutcome(stored, operation, key)
        return if (status.isSuccess) null else ErrorMapping.linuxStatus(status, operation, key)
    }

    private fun wipeLookup(raw: RawResult<SecretLookup>) {
        if (raw is RawResult.Success) (raw.value as? SecretLookup.Found)?.secret?.fill(0)
    }
}
