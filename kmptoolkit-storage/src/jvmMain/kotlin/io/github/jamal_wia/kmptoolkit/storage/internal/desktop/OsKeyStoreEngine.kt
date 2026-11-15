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
 *   key-check. A cached key whose check fails with a provider failure on a file WITH entries is either a provider failure or a key
 *   that another process replaced (under a PKCS#11 provider both look the same): the item is asked for its key (off the lock) only
 *   when the provider still works with the cached key, and the cache is replaced only by a fetched key that opens this file; a
 *   failing provider makes no native call, and an equal key, a missing item or a fetched key that does not open the file keeps the
 *   failure and the cache.
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
        StoreIdentity.of(store.real, normalizeUnicode = ready.os == DesktopOs.MACOS, configuredNameIsLink = store::configuredNameIsLink)

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
        val fetched: OsCarry? = (carry as? OsCarry)?.takeIf { it.snapshot.matches(parsed) && it.snapshot.identity?.cacheKey == identity.cacheKey }
        val cached: ByteArray? = cache.get(identity)
        if (cached != null) {
            try {
                if (verifies(cached, file)) return Resolution.Have(cached)
                cache.invalidate(identity, cached)
            } catch (failure: JcaFailure) {
                // A file WITH entries and a provider failure: a transient token error, or a cached key that another process replaced
                // (under a PKCS#11 provider a genuinely wrong key shows in the same way). Only a provider that still works with the
                // cached key can have meant "wrong key", so only then is the item asked what its key is now (off the lock); a provider
                // that fails for everything makes no native call at all, and the cache stays as it is.
                if (fetched == null) {
                    if (!providerWorksWith(cached, file)) throw failure
                    return Resolution.Need(Request.Existing(Snapshot.of(parsed, identity)))
                }
                // The item's key is proved BEFORE the cache is touched: the cached key's only verdict so far is "the provider failed",
                // which does not say it is wrong, so it is replaced only by a key that opens this very file. A key that equals the cached
                // one, an item that holds none, or a fetched key that does not open the file leaves the failure and the cache as they are.
                val current: ByteArray? = fetched.fetchedKey
                if (current == null || current.contentEquals(cached) || !verifies(current, file)) throw failure
                cache.put(identity, current)
                return Resolution.Have(checkNotNull(cache.get(identity)) { "the key cache lost an entry under the store lock" })
            }
        }
        // A key is used only for the header AND the identity it was fetched for.
        if (fetched != null) {
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
     * empty store then costs a key replacement, and another process that holds the replaced key recovers by the stale-key rule of
     * [resolve] (it asks the item only when [providerWorksWith] says the failure is not the provider's).
     */
    private fun verifies(key: ByteArray, file: ParsedStoreFile): Boolean =
        try {
            cipher.verifyKeyCheck(key, file.headerPrefix, file.keyCheck)
        } catch (failure: JcaFailure) {
            if (file.entries.isEmpty()) false else throw failure
        }

    /**
     * Whether the provider seals and opens with [key] at all, for fresh data of its own: no file, no native call, no store state.
     * `false` for any provider failure (including a random source that cannot draw a nonce): the caller then keeps the failure it has.
     */
    private fun providerWorksWith(key: ByteArray, file: ParsedStoreFile): Boolean =
        try {
            cipher.verifyKeyCheck(key, file.headerPrefix, cipher.sealKeyCheck(key, file.headerPrefix))
        } catch (_: JcaFailure) {
            false
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
