package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.DesktopSecureStorageOptions
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher

/** What a fetch brought for [snapshot]: the key, or `null` for a definitive "not found at the own identity". */
internal class OsCarry(val snapshot: Snapshot, private val key: ByteArray?) : Carry {
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
 */
internal class OsKeyStoreEngine(
    guard: EncryptedFileGuard,
    dispatcher: CoroutineDispatcher,
    cipher: EnvelopeCipher,
    private val name: String,
    private val directory: File,
    private val options: DesktopSecureStorageOptions,
    private val source: BindingSource,
    private val executor: NativeExecutor,
    private val cache: OsKeyCache,
    private val environment: SecureStoreEnvironment,
) : EncryptedStoreEngine(guard, dispatcher, cipher) {

    private sealed interface Resolved {
        class Ready(val backend: OsKeyBackend, val identity: StoreIdentity) : Resolved
        class Unsupported(val error: StorageError) : Resolved
    }

    /** Resolved once, on the first operation, off the caller's thread (the selector and the canonical path do blocking work). */
    private val resolved: Resolved by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { resolveBinding() }

    private fun resolveBinding(): Resolved {
        val selection: BindingSelection = source.select()
        if (selection is BindingSelection.Unsupported) return Resolved.Unsupported(selection.error)
        selection as BindingSelection.Selected
        val identity: StoreIdentity = StoreIdentity.of(name, directory)
        val caller = NativeCaller(executor, options.allowUserInteraction)
        val label: String = options.itemLabel ?: identity.service
        val backend: OsKeyBackend = when (selection.os) {
            DesktopOs.MACOS -> MacBackend(selection.binding.macOs(), identity, label, caller)
            DesktopOs.WINDOWS -> WindowsBackend(selection.binding.windows(), identity, caller)
            DesktopOs.LINUX -> LinuxBackend(selection.binding.linux(), identity, label, caller, environment)
        }
        return Resolved.Ready(backend, identity)
    }

    private val ready: Resolved.Ready get() = resolved as Resolved.Ready

    override val kinds: Set<StoreKind> get() = setOf(ready.backend.kind)

    override val clearNeedsKey: Boolean = false

    override fun unavailableReason(): StorageError? = (resolved as? Resolved.Unsupported)?.error

    override fun roundsExhausted(): StorageError = StorageError.Unavailable()

    override fun resolve(parsed: FileState.Parsed, carry: Carry?): Resolution {
        val backend: OsKeyBackend = ready.backend
        val identity: StoreIdentity = ready.identity
        val file: ParsedStoreFile = parsed.file
        // A header that names another identity is a lost key; no OS call is made for it and no cached key is used.
        if (!backend.identityMatches(file.header)) return Resolution.Lost
        val cached: ByteArray? = cache.get(identity)
        if (cached != null) {
            if (cipher.verifyKeyCheck(cached, file.headerPrefix, file.keyCheck)) return Resolution.Have(cached)
            cache.invalidate(identity, cached)
        }
        val fetched: OsCarry? = carry as? OsCarry
        if (fetched != null && fetched.snapshot.matches(parsed)) {
            val key: ByteArray = fetched.fetchedKey ?: return Resolution.Lost
            if (!cipher.verifyKeyCheck(key, file.headerPrefix, file.keyCheck)) return Resolution.Lost
            cache.put(identity, key)
            return Resolution.Have(checkNotNull(cache.get(identity)) { "the key cache lost an entry under the store lock" })
        }
        return Resolution.Need(Request.Existing(Snapshot.of(parsed)))
    }

    override suspend fun prepare(request: Request, op: Op, scope: OpScope): Prepared {
        val snapshot: Snapshot = (request as Request.Existing).snapshot
        return when (val verdict: Verdict<ByteArray> = ready.backend.fetch(snapshot.header, op.operation, op.key)) {
            is Verdict.Ok -> Prepared.Ready(OsCarry(snapshot, verdict.value))
            Verdict.NotFound -> Prepared.Ready(OsCarry(snapshot, null))
            is Verdict.Failed -> Prepared.Failed(verdict.error)
        }
    }

    override suspend fun createPlan(op: Op, carry: Carry?): CreatePlan {
        val backend: OsKeyBackend = ready.backend
        val identity: StoreIdentity = ready.identity
        backend.preflight(op.operation, op.key)?.let { return CreatePlan.Failed(it) }
        val material: NewKey = when (val verdict: Verdict<NewKey> = backend.newKey(op.operation, op.key, cipher::newDataKey)) {
            is Verdict.Ok -> verdict.value
            is Verdict.Failed -> return CreatePlan.Failed(verdict.error)
            Verdict.NotFound -> error("creating a key has no not-found")
        }
        val keyCheck: ByteArray = cipher.sealKeyCheck(material.dataKey, SecureFileFormat.headerPrefix(material.header))
        return CreatePlan.Ready(
            header = material.header,
            keyCheck = keyCheck,
            dataKey = material.dataKey,
            install = { backend.install(material.dataKey, op.operation, op.key) },
            commit = { cache.put(identity, material.dataKey) },
            dispose = { material.dataKey.fill(0) },
        )
    }
}
