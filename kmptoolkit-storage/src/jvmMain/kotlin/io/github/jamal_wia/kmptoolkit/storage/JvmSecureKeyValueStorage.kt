package io.github.jamal_wia.kmptoolkit.storage

import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.BindingSelector
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.BindingSource
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.CrossProcessLock
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.EncryptedFileGuard
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.EnvelopeCipher
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.NativeExecutor
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.Op
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.OsKeyCache
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.OsKeyStoreEngine
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.SecureStoreEnvironment
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.StoreIdentity
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.StorePathResolver
import java.io.File
import java.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * The desktop [SecureKeyValueStorage]: the data key lives in the running OS's key store (macOS file keychain, Windows
 * DPAPI, Linux Secret Service), the data in an encrypted file (format v1 of plan 4.3) written as a whole, atomically,
 * owner-only. Never touches the OS before the first call and never falls back to anything weaker: a problem is a typed
 * [StorageError].
 *
 * [protection] is `OS_KEY_STORE_USER_SCOPED`, known without I/O and reported even when every call then answers
 * [StorageError.Unsupported] (no binding on this runtime or OS).
 *
 * The seams are `internal`: tests install the fake binding through [source], nothing public selects a binding.
 */
internal class JvmSecureKeyValueStorage(
    name: String,
    directory: File,
    options: DesktopSecureStorageOptions,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    source: BindingSource = BindingSelector.process,
    executor: NativeExecutor = NativeExecutor.shared,
    cache: OsKeyCache = OsKeyCache.shared,
    environment: SecureStoreEnvironment = SecureStoreEnvironment(),
    crossProcessLock: CrossProcessLock = CrossProcessLock(),
    timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    clock: () -> Instant = Instant::now,
    files: FileAccess = createFileAccess(),
    cipher: EnvelopeCipher = EnvelopeCipher(),
    pathResolver: StorePathResolver = StorePathResolver.real,
    maxStoreBytes: Long = EncryptedFileGuard.MAX_STORE_BYTES,
) : SecureKeyValueStorage {

    private val engine: OsKeyStoreEngine = OsKeyStoreEngine(
        guard = EncryptedFileGuard(
            file = File(directory, secureStoreId(name) + StoreIdentity.SECURE_FILE_SUFFIX),
            files = files,
            crossProcessLock = crossProcessLock,
            timeSource = timeSource,
            clock = clock,
            resolver = pathResolver,
            maxStoreBytes = maxStoreBytes,
        ),
        dispatcher = dispatcher,
        cipher = cipher,
        options = options,
        source = source,
        executor = executor,
        cache = cache,
        environment = environment,
    )

    override val protection: SecureStorageProtection = SecureStorageProtection.OS_KEY_STORE_USER_SCOPED

    override suspend fun get(key: String): StorageResult<String?> = engine.execute(Op.Get(key))

    override suspend fun put(key: String, value: String): StorageResult<Unit> = engine.execute(Op.Put(key, value)).asUnit()

    override suspend fun remove(key: String): StorageResult<Unit> = engine.execute(Op.Remove(key)).asUnit()

    override suspend fun clear(): StorageResult<Unit> = engine.execute(Op.Clear).asUnit()

    override suspend fun ensureKey(): StorageResult<Unit> = engine.execute(Op.Ensure).asUnit()
}

/** Drops the (always absent) value of an operation that returns nothing. */
internal fun StorageResult<String?>.asUnit(): StorageResult<Unit> = when (this) {
    is StorageResult.Success -> StorageResult.Success(Unit)
    is StorageResult.Failure -> this
}
