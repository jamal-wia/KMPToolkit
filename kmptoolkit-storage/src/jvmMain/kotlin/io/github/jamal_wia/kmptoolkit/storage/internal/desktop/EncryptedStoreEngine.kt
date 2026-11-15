package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.files.ExistingFile
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.unpairedSurrogateFailure
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher

/** One operation of a store, as the engine runs it. */
internal sealed class Op(val operation: StorageOperation, val key: String?) {
    class Get(key: String) : Op(StorageOperation.GET, key)
    class Put(key: String, val value: String) : Op(StorageOperation.PUT, key)
    class Remove(key: String) : Op(StorageOperation.REMOVE, key)
    data object Clear : Op(StorageOperation.CLEAR, null)
    data object Ensure : Op(StorageOperation.ENSURE_KEY, null)
}

/** The bytes of a header and key-check, as seen by a round: what a fetched key was fetched for. */
internal class Snapshot(
    val header: HeaderBody,
    val headerPrefix: ByteArray,
    val keyCheck: ByteArray,
    /** The store identity the round derived (OS-key stores): a key fetched for another identity is never used. `null` for app-key stores. */
    val identity: StoreIdentity? = null,
) {
    fun matches(parsed: FileState.Parsed): Boolean =
        parsed.file.headerPrefix.contentEquals(headerPrefix) && parsed.file.keyCheck.contentEquals(keyCheck)

    internal companion object {
        fun of(parsed: FileState.Parsed, identity: StoreIdentity? = null): Snapshot =
            Snapshot(parsed.file.header, parsed.file.headerPrefix, parsed.file.keyCheck, identity)
    }
}

/** What a round needs from outside the lock before it can decide. */
internal sealed interface Request {
    /** The key of an existing file, as of [snapshot]. */
    class Existing(val snapshot: Snapshot) : Request

    /** The material for a NEW file (app-key stores: a fresh salt and the key-encryption key derived with it). */
    data object Fresh : Request
}

/** What off-lock work produced; wiped by the engine when the operation ends or the next round replaces it. */
internal interface Carry {
    fun wipe()
}

/** Per-operation state that outlives rounds (app-key stores keep the provider's secret here: one provider call per operation). */
internal interface OpScope {
    fun wipe()

    data object None : OpScope {
        override fun wipe() = Unit
    }
}

internal sealed interface Prepared {
    class Ready(val carry: Carry) : Prepared
    class Failed(val error: StorageError) : Prepared
}

/** How a store's key is known to a round, under the lock. */
internal sealed interface Resolution {
    /** The verified data key. Borrowed: valid for this round only, never kept and never wiped by the caller. */
    class Have(val dataKey: ByteArray) : Resolution

    /** The key is lost (plan 4.4): only OS-key stores can say so. */
    data object Lost : Resolution

    /** Off-lock work is needed first. */
    class Need(val request: Request) : Resolution

    class Fail(val error: StorageError) : Resolution
}

/** The plan of creating a new key and its empty file, built under the lock. */
internal sealed interface CreatePlan {
    class Need(val request: Request) : CreatePlan

    class Failed(val error: StorageError) : CreatePlan

    class Ready(
        val header: HeaderBody,
        val keyCheck: ByteArray,
        val dataKey: ByteArray,
        /** Puts the key into the OS (`null` = done or the error). Runs inside the locks, after the empty file exists. */
        val install: suspend () -> StorageError?,
        /** Adopts the new key into the cache once the create is committed. */
        val commit: () -> Unit,
        /** Wipes everything the plan holds. Always called. */
        val dispose: () -> Unit,
    ) : CreatePlan
}

/**
 * The operation table of plan 4.4 and the Q5 rows, once, for both encrypted desktop stores. The engine decides under both
 * locks what is at the store path ([FileState]) and what the key situation is ([Resolution]); everything slow or
 * interactive (a native call that fetches the key, a key provider, PBKDF2) happens OFF the locks in [prepare], and the
 * next round re-reads the file and checks that what was prepared is still the file it was prepared for. A round may need
 * at most [MAX_ROUNDS] of these trips.
 *
 * Subclasses supply how a key is resolved ([resolve]), fetched ([prepare]) and created ([createPlan]); the table
 * is not theirs to change.
 */
internal abstract class EncryptedStoreEngine(
    protected val guard: EncryptedFileGuard,
    protected val dispatcher: CoroutineDispatcher,
    protected val cipher: EnvelopeCipher,
) {
    /** The kinds of file this store type may hold; any other kind is unparsable for it. */
    protected abstract val kinds: Set<StoreKind>

    /** The kind of file this store creates when it has none (the bound key's kind): known without any key material. */
    protected abstract fun newFileKind(): StoreKind

    /** Whether `clear()` must verify the key first (app-key stores) or never touches it (OS-key stores). */
    protected abstract val clearNeedsKey: Boolean

    /** A typed reason no operation can run at all (no binding), checked before any lock; `null` when the store can run. */
    protected abstract fun unavailableReason(): StorageError?

    /**
     * Under the lock: what is known about the key for [parsed] (the file at [store]), given what off-lock work brought
     * ([carry]). Never suspends; may throw an `IOException` (the real path of the file could not be resolved), which the
     * guard answers as `OperationFailed` for that call.
     */
    protected abstract fun resolve(store: EncryptedFileGuard.StoreFile, parsed: FileState.Parsed, carry: Carry?): Resolution

    /** Off the lock: fetches or derives what [request] asks for. */
    protected abstract suspend fun prepare(request: Request, op: Op, scope: OpScope): Prepared

    /** Under the lock: builds the creation of a new key for the file at [store], or says what it needs first. */
    protected abstract suspend fun createPlan(store: EncryptedFileGuard.StoreFile, op: Op, carry: Carry?): CreatePlan

    protected open fun newScope(): OpScope = OpScope.None

    /** The answer when [MAX_ROUNDS] trips were not enough (the file kept changing under us). */
    protected abstract fun roundsExhausted(): StorageError

    private sealed interface Step {
        class Done(val result: StorageResult<String?>) : Step
        class Need(val request: Request) : Step
        data object Again : Step
    }

    /** Runs [op]. A `put` or `remove` answers `Success(null)`; only `get` carries a value. */
    suspend fun execute(op: Op): StorageResult<String?> = withStoreContext(dispatcher) {
        if (op is Op.Put) {
            unpairedSurrogateFailure(op.key!!, op.value)?.let { return@withStoreContext it }
            // Counted, not encoded: a key of 90 million characters must be refused without allocating its encoded copies, like a value.
            if (op.key.strictUtf8Length() > SecureFileFormat.MAX_ENTRY_KEY_BYTES) {
                return@withStoreContext StorageResult.Failure(
                    StorageError.InvalidValue(op.key, IllegalArgumentException("The key is longer than 65535 UTF-8 bytes")),
                )
            }
            // Bound before any lock and before anything is encoded: an entry of this size alone passes the limit, so no file
            // holding it can be read back. Counted, not encoded: a value of 90 million three-byte characters never allocates.
            if (SecureFileFormat.entrySize(op.key, op.value.strictUtf8Length()) > guard.maxStoreBytes) {
                return@withStoreContext StorageResult.Failure(tooLarge(op.key))
            }
        }
        unavailableReason()?.let { return@withStoreContext StorageResult.Failure(it) }
        // A store whose directory does not exist holds nothing: reading, removing or clearing it creates no directory and no lock file.
        if ((op is Op.Get || op is Op.Remove || op is Op.Clear) && guard.directoryMissing()) {
            return@withStoreContext StorageResult.Success(null)
        }
        val scope: OpScope = newScope()
        var carry: Carry? = null
        try {
            repeat(MAX_ROUNDS) {
                when (val locked: StorageResult<Step> = guard.locked(op.operation, op.key) { store: EncryptedFileGuard.StoreFile -> round(store, op, carry) }) {
                    is StorageResult.Failure -> return@withStoreContext locked
                    is StorageResult.Success -> when (val step: Step = locked.value) {
                        is Step.Done -> return@withStoreContext step.result
                        Step.Again -> Unit
                        is Step.Need -> {
                            carry?.wipe()
                            carry = null
                            when (val prepared: Prepared = prepare(step.request, op, scope)) {
                                is Prepared.Ready -> carry = prepared.carry
                                is Prepared.Failed -> return@withStoreContext StorageResult.Failure(prepared.error)
                            }
                        }
                    }
                }
            }
            StorageResult.Failure(roundsExhausted())
        } finally {
            carry?.wipe()
            scope.wipe()
        }
    }

    private suspend fun round(store: EncryptedFileGuard.StoreFile, op: Op, carry: Carry?): Step = when (val state: FileState = store.classify(kinds)) {
        FileState.Absent -> absent(store, op, carry)
        is FileState.Unparsable -> unparsable(store, op, state, carry)
        is FileState.Parsed -> parsed(store, op, state, carry)
    }

    private fun done(result: StorageResult<String?>): Step = Step.Done(result)

    private fun ok(value: String? = null): Step = Step.Done(StorageResult.Success(value))

    private fun fail(error: StorageError): Step = Step.Done(StorageResult.Failure(error))

    private suspend fun absent(store: EncryptedFileGuard.StoreFile, op: Op, carry: Carry?): Step = when (op) {
        is Op.Get, is Op.Remove -> ok()
        Op.Clear -> {
            store.sweepTemps()
            ok()
        }
        // A link whose target does not exist reads as no file, but nothing can be created at its name: the create would find the name
        // taken, read that as "another process won" and go round again for nothing (a key drawn, a native call made, four rounds), then
        // answer a retry-later error for a fault that never heals. It is a permanent fault of the path, answered at once, before any
        // key material or native call, exactly like the lock path.
        is Op.Put, Op.Ensure ->
            if (store.isLinkWithoutTarget()) {
                fail(StorageError.OperationFailed(op.operation, op.key, cause = IOException("The store file is a symbolic link whose target does not exist")))
            } else {
                create(store, op, CreateMode.Fresh, carry)
            }
    }

    private suspend fun unparsable(store: EncryptedFileGuard.StoreFile, op: Op, state: FileState.Unparsable, carry: Carry?): Step = when (op) {
        Op.Clear -> {
            // The documented way out: the unusable (or newer) file is moved aside, never deleted; nothing is created.
            store.quarantine()
            store.sweepTemps()
            ok()
        }
        // A file of a newer library is never replaced by a put: only clear() may touch it.
        is Op.Put -> if (state.newerVersion) fail(undecryptable(null)) else create(store, op, CreateMode.AfterQuarantine, carry)
        is Op.Get, is Op.Remove, Op.Ensure -> fail(undecryptable(null))
    }

    private suspend fun parsed(store: EncryptedFileGuard.StoreFile, op: Op, state: FileState.Parsed, carry: Carry?): Step {
        if (op is Op.Clear && !clearNeedsKey) return clearFile(store, state)
        return when (val resolution: Resolution = resolve(store, state, carry)) {
            is Resolution.Need -> Step.Need(resolution.request)
            is Resolution.Fail -> fail(resolution.error)
            is Resolution.Have -> if (op is Op.Clear) clearFile(store, state) else withKey(store, op, state, resolution.dataKey)
            Resolution.Lost -> lost(store, op, state, carry)
        }
    }

    private fun withKey(store: EncryptedFileGuard.StoreFile, op: Op, state: FileState.Parsed, dataKey: ByteArray): Step {
        val file: ParsedStoreFile = state.file
        return when (op) {
            is Op.Get -> {
                val entry: EncryptedEntry = file.entries.firstOrNull { it.key == op.key } ?: return ok()
                val plain: ByteArray = cipher.openEntry(dataKey, file.kind, entry) ?: return fail(undecryptable(op.key))
                try {
                    plain.decodeStrictUtf8()?.let { ok(it) } ?: fail(undecryptable(op.key))
                } finally {
                    plain.fill(0)
                }
            }
            is Op.Put -> {
                val others: List<EncryptedEntry> = file.entries.filter { it.key != op.key }
                // Measured before the value is encoded and sealed, and refused before anything is written: a file over the
                // read limit would be unparsable, and the next put would quarantine it.
                val size: Long = SecureFileFormat.encodedSize(file.header, others) + SecureFileFormat.entrySize(op.key!!, op.value.strictUtf8Length())
                if (size > guard.maxStoreBytes) return fail(tooLarge(op.key))
                writeEntries(store, file, others + sealValue(dataKey, file.kind, op.key, op.value))
                ok()
            }
            is Op.Remove -> {
                if (file.entries.none { it.key == op.key }) return ok()
                writeEntries(store, file, file.entries.filter { it.key != op.key })
                ok()
            }
            Op.Ensure -> ok()
            Op.Clear -> clearFile(store, state)
        }
    }

    /** Key lost (OS-key stores only), the rows of plan 4.4. */
    private suspend fun lost(store: EncryptedFileGuard.StoreFile, op: Op, state: FileState.Parsed, carry: Carry?): Step {
        val file: ParsedStoreFile = state.file
        return when (op) {
            is Op.Get -> if (file.entries.any { it.key == op.key }) fail(undecryptable(null)) else ok()
            is Op.Remove -> {
                // Drops the entry without any key: the header, the key-check and the other entries are copied byte for byte.
                if (file.entries.none { it.key == op.key }) return ok()
                writeEntries(store, file, file.entries.filter { it.key != op.key })
                ok()
            }
            is Op.Put ->
                if (file.entries.isEmpty()) create(store, op, CreateMode.ReplaceEmpty(state.bytes), carry)
                else create(store, op, CreateMode.AfterQuarantine, carry)
            Op.Ensure ->
                if (file.entries.isEmpty()) create(store, op, CreateMode.ReplaceEmpty(state.bytes), carry)
                else fail(undecryptable(null))
            Op.Clear -> clearFile(store, state)
        }
    }

    /** An empty sealed file with the header and key-check kept; temporary leftovers are swept. No key is needed or used. */
    private fun clearFile(store: EncryptedFileGuard.StoreFile, state: FileState.Parsed): Step {
        val file: ParsedStoreFile = state.file
        if (file.entries.isNotEmpty()) writeEntries(store, file, emptyList())
        store.sweepTemps()
        return ok()
    }

    private fun writeEntries(store: EncryptedFileGuard.StoreFile, file: ParsedStoreFile, entries: List<EncryptedEntry>) {
        store.write(SecureFileFormat.encode(file.header, file.keyCheck, entries), ExistingFile.Replace)
    }

    private suspend fun create(store: EncryptedFileGuard.StoreFile, op: Op, mode: CreateMode, carry: Carry?): Step {
        // An entry that cannot fit even the smallest file of this kind is refused before any key material is asked for (a
        // preflight, DPAPI, a key derivation), so the answer is InvalidValue whatever the key store would have said. The exact
        // header is known only after createPlan (a DPAPI blob has a length of its own), so the exact check below still follows.
        if (op is Op.Put &&
            SecureFileFormat.minimumFileBytes(newFileKind()) + SecureFileFormat.entrySize(op.key!!, op.value.strictUtf8Length()) > guard.maxStoreBytes
        ) {
            return fail(tooLarge(op.key))
        }
        val plan: CreatePlan.Ready = when (val built: CreatePlan = createPlan(store, op, carry)) {
            is CreatePlan.Need -> return Step.Need(built.request)
            is CreatePlan.Failed -> return fail(built.error)
            is CreatePlan.Ready -> built
        }
        try {
            // Measured BEFORE the value is sealed and before anything is created: a value that cannot fit a store file creates
            // no key and no file, and is never encoded.
            if (op is Op.Put) {
                val size: Long = SecureFileFormat.encodedSize(plan.header, emptyList()) + SecureFileFormat.entrySize(op.key!!, op.value.strictUtf8Length())
                if (size > guard.maxStoreBytes) return fail(tooLarge(op.key))
            }
            val sealed: EncryptedEntry? = if (op is Op.Put) sealValue(plan.dataKey, plan.header.kind, op.key!!, op.value) else null
            val emptyFile: ByteArray = SecureFileFormat.encode(plan.header, plan.keyCheck, emptyList())
            return when (val result: CreateResult = store.createFile(mode, emptyFile, plan.install)) {
                CreateResult.Raced -> Step.Again
                // A failed rollback is not the original failure: the store is not as it was (see createFile), so say so.
                is CreateResult.Failed -> fail(result.rollbackFailure?.let { StorageError.OperationFailed(op.operation, op.key, cause = it) } ?: result.error)
                CreateResult.Created -> {
                    plan.commit()
                    if (sealed != null) store.write(SecureFileFormat.encode(plan.header, plan.keyCheck, listOf(sealed)), ExistingFile.Replace)
                    ok()
                }
            }
        } finally {
            plan.dispose()
        }
    }

    private fun sealValue(dataKey: ByteArray, kind: StoreKind, key: String, value: String): EncryptedEntry {
        val plain: ByteArray = value.toStrictUtf8()
        try {
            return cipher.sealEntry(dataKey, kind, key, plain)
        } finally {
            plain.fill(0)
        }
    }

    private fun undecryptable(key: String?): StorageError = StorageError.Undecryptable(key)

    /** A put whose encoded file would pass the read limit (plan 4.11): nothing was written. */
    private fun tooLarge(key: String?): StorageError =
        StorageError.InvalidValue(key.orEmpty(), IllegalArgumentException("The store would exceed ${guard.maxStoreBytes} bytes"))

    internal companion object {
        /** Trips to the outside (fetch, derive) one operation may make while the file keeps changing under it. */
        const val MAX_ROUNDS: Int = 4
    }
}
