package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.DesktopSecureStorageOptions
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import kotlinx.coroutines.CoroutineDispatcher

/**
 * What a fetch brought for [snapshot]: the key; `null` with [unconfirmed] `null` for a definitive "not found at the own
 * identity"; or `null` with [unconfirmed] set for a not-found that is not definitive (macOS, non-interactive, conservative
 * rule): the engine decides what that means from the file of the round that uses it.
 */
internal class OsCarry(val snapshot: Snapshot, private val key: ByteArray?, val unconfirmed: StorageError? = null) : Carry {
    /** The fetched key, owned by this carry until [wipe]. */
    val fetchedKey: ByteArray? get() = key

    override fun wipe() {
        key?.fill(0)
    }
}

/**
 * The key lifecycle of plan 4.4 for the OS-key stores (kinds 1 and 2): the per-identity key cache with a key-check
 * verification on every use, the "key lost" rule (a definitive not-found, a failing key-check, or a foreign header; never a
 * locked, denied, hung or missing store), and key creation inside the locks with a full rollback.
 *
 * - **Slow or interactive work is off the lock.** A key that is not cached is fetched by [prepare] with no lock held (a
 *   keychain prompt or a hung native call never holds the store locks); the next round re-reads the file and uses the key
 *   only if the header and key-check are the ones it was fetched for.
 * - **Creation is the exception (plan R7).** The decision, the empty file, the item and the cache entry happen inside
 *   the locks, so an item is replaced only by the process that holds them. With `allowUserInteraction = true` a macOS
 *   dialog during creation can therefore hold the locks; other callers then answer `Unavailable` after 10 s (documented).
 * - **The key cache is the process-wide [OsKeyCache]**, borrowed only under the store's locks, never trusted without its
 *   key-check.
 * - **The identity is derived per locked round from the real path of the store file** ([RealStorePath], no case folding,
 *   never memoised, never a fallback path), and a key fetched off the lock is used only if it was fetched for the
 *   identity of the round that uses it. A file system that cannot be asked fails that one call (`OperationFailed`).
 * - **A zero-entry file has nothing to lose.** A not-found that is not definitive (macOS, no dialog allowed, plan 4.5's
 *   conservative rule) is `Unavailable` for a file with entries, and "lost, zero entries" for an empty one, so the crash
 *   state of plan 4.4 repairs itself without UI; the create's delete and add still carry "no UI", so a locked keychain or
 *   a refused delete fails closed and rolls back. The same rule decides a key-check that a PROVIDER fails on (see [verifies]).
 */
internal class OsKeyStoreEngine(
    guard: EncryptedFileGuard,
    dispatcher: CoroutineDispatcher,
    cipher: EnvelopeCipher,
    private val options: DesktopSecureStorageOptions,
    private val source: BindingSource,
    private val executor: NativeExecutor,
    private val cache: OsKeyCache,
    private val environment: SecureStoreEnvironment,
) : EncryptedStoreEngine(guard, dispatcher, cipher) {

    private sealed interface Resolved {
        class Ready(val backend: OsKeyBackend, val os: DesktopOs) : Resolved
        class Unsupported(val error: StorageError) : Resolved
    }

    /** Resolved once, on the first operation, off the caller's thread (the selector does blocking work). */
    private val resolved: Resolved by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { resolveBinding() }

    private fun resolveBinding(): Resolved {
        val selection: BindingSelection = source.select()
        if (selection is BindingSelection.Unsupported) return Resolved.Unsupported(selection.error)
        selection as BindingSelection.Selected
        val caller = NativeCaller(executor, options.allowUserInteraction)
        val backend: OsKeyBackend = when (selection.os) {
            DesktopOs.MACOS -> when (val accessed: Accessed<MacKeychainCalls> = accessCalls { selection.binding.macOs() }) {
                is Accessed.Ready -> MacBackend(accessed.calls, options.itemLabel, caller)
                is Accessed.Unusable -> return Resolved.Unsupported(accessed.error)
            }
            DesktopOs.WINDOWS -> when (val accessed: Accessed<DpapiCalls> = accessCalls { selection.binding.windows() }) {
                is Accessed.Ready -> WindowsBackend(accessed.calls, caller)
                is Accessed.Unusable -> return Resolved.Unsupported(accessed.error)
            }
            DesktopOs.LINUX -> when (val accessed: Accessed<SecretServiceCalls> = accessCalls { selection.binding.linux() }) {
                is Accessed.Ready -> LinuxBackend(accessed.calls, options.itemLabel, caller, environment)
                is Accessed.Unusable -> return Resolved.Unsupported(accessed.error)
            }
        }
        return Resolved.Ready(backend, selection.os)
    }

    private val ready: Resolved.Ready get() = resolved as Resolved.Ready

    /** The identity of the file of this round, from its real path. Never memoised: see the class KDoc. */
    private fun identityOf(store: EncryptedFileGuard.StoreFile): StoreIdentity =
        StoreIdentity.of(store.real, normalizeUnicode = ready.os == DesktopOs.MACOS)

    override val kinds: Set<StoreKind> get() = setOf(ready.backend.kind)

    override fun newFileKind(): StoreKind = ready.backend.kind

    override val clearNeedsKey: Boolean = false

    override fun unavailableReason(): StorageError? = (resolved as? Resolved.Unsupported)?.error

    override fun roundsExhausted(): StorageError = StorageError.Unavailable()

    override fun resolve(store: EncryptedFileGuard.StoreFile, parsed: FileState.Parsed, carry: Carry?): Resolution {
        val backend: OsKeyBackend = ready.backend
        val identity: StoreIdentity = identityOf(store)
        val file: ParsedStoreFile = parsed.file
        // A header that names another identity is a lost key; no OS call is made for it and no cached key is used.
        if (!backend.identityMatches(file.header, identity)) return Resolution.Lost
        val cached: ByteArray? = cache.get(identity)
        if (cached != null) {
            if (verifies(cached, file)) return Resolution.Have(cached)
            cache.invalidate(identity, cached)
        }
        val fetched: OsCarry? = carry as? OsCarry
        // A key is used only for the header AND the identity it was fetched for.
        if (fetched != null && fetched.snapshot.matches(parsed) && fetched.snapshot.identity?.cacheKey == identity.cacheKey) {
            val key: ByteArray = fetched.fetchedKey ?: return notFound(fetched.unconfirmed, file)
            if (!verifies(key, file)) return Resolution.Lost
            cache.put(identity, key)
            return Resolution.Have(checkNotNull(cache.get(identity)) { "the key cache lost an entry under the store lock" })
        }
        return Resolution.Need(Request.Existing(Snapshot.of(parsed, identity)))
    }

    /**
     * Whether [key] opens the key-check of [file]. A provider that FAILS on the check (a PKCS#11 token error, which the boundary
     * types as a [JcaFailure] and which under such a provider is also how a genuinely wrong key shows) is an `OperationFailed` for a
     * file with entries: the environment is never allowed to delete data. For a file with ZERO entries there is nothing to lose, so
     * the same failure reads as "this key does not open it", exactly like a not-found item (see [notFound]): that is what makes
     * `clear()` followed by `put` or `ensureKey()` a way out of a lost key under such a provider too, because `clear()` keeps the
     * header and the key-check and every later round would otherwise fail on them again for good. A transient token error on an
     * empty store then costs only a harmless key replacement.
     */
    private fun verifies(key: ByteArray, file: ParsedStoreFile): Boolean =
        try {
            cipher.verifyKeyCheck(key, file.headerPrefix, file.keyCheck)
        } catch (failure: JcaFailure) {
            if (file.entries.isEmpty()) false else throw failure
        }

    /** No item at the own identity: lost when it is definitive, or when the file has no entry to lose; otherwise [unconfirmed]. */
    private fun notFound(unconfirmed: StorageError?, file: ParsedStoreFile): Resolution =
        if (unconfirmed == null || file.entries.isEmpty()) Resolution.Lost else Resolution.Fail(unconfirmed)

    override suspend fun prepare(request: Request, op: Op, scope: OpScope): Prepared {
        val snapshot: Snapshot = (request as Request.Existing).snapshot
        val identity: StoreIdentity = checkNotNull(snapshot.identity) { "an OS-key round always names its identity" }
        return when (val verdict: Verdict<ByteArray> = ready.backend.fetch(snapshot.header, identity, op.operation, op.key)) {
            is Verdict.Ok -> Prepared.Ready(OsCarry(snapshot, verdict.value))
            Verdict.NotFound -> Prepared.Ready(OsCarry(snapshot, null))
            is Verdict.NotFoundUnconfirmed -> Prepared.Ready(OsCarry(snapshot, null, verdict.error))
            is Verdict.Failed -> Prepared.Failed(verdict.error)
        }
    }

    override suspend fun createPlan(store: EncryptedFileGuard.StoreFile, op: Op, carry: Carry?): CreatePlan {
        val backend: OsKeyBackend = ready.backend
        val identity: StoreIdentity = identityOf(store)
        backend.preflight(op.operation, op.key)?.let { return CreatePlan.Failed(it) }
        val material: NewKey = when (val verdict: Verdict<NewKey> = backend.newKey(identity, op.operation, op.key, cipher::newDataKey)) {
            is Verdict.Ok -> verdict.value
            is Verdict.Failed -> return CreatePlan.Failed(verdict.error)
            is Verdict.NotFoundUnconfirmed, Verdict.NotFound -> error("creating a key has no not-found")
        }
        val keyCheck: ByteArray = try {
            cipher.sealKeyCheck(material.dataKey, SecureFileFormat.headerPrefix(material.header))
        } catch (error: Throwable) {
            // The plan whose dispose wipes the key does not exist yet, so nobody else will: the key made for it ends with this failure.
            material.dataKey.fill(0)
            throw error
        }
        return CreatePlan.Ready(
            header = material.header,
            keyCheck = keyCheck,
            dataKey = material.dataKey,
            // A copy the install owns and wipes (also when its native call never runs): the plan keeps and wipes its own key.
            install = { backend.install(identity, material.dataKey.copyOf(), op.operation, op.key) },
            commit = { cache.put(identity, material.dataKey) },
            dispose = { material.dataKey.fill(0) },
        )
    }
}
