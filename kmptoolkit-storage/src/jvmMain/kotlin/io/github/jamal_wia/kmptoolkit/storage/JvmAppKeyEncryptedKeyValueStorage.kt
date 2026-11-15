package io.github.jamal_wia.kmptoolkit.storage

import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.AppKeyStoreEngine
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.CrossProcessLock
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.EncryptedFileGuard
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.EnvelopeCipher
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.Op
import java.io.File
import java.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** The identifier of the app-key store file `<name>.kmptoolkit.appkeystorage.bin` (plan 4.3); never equal to the other two. */
internal fun appKeyStoreId(name: String): String = "$name.kmptoolkit.appkeystorage"

/**
 * The desktop store whose data key is wrapped by a key the app provides ([AppEncryptionKey]): no operating-system call,
 * no binding, so it works on any OS and on JDK 21 without the JNA artifact. See [AppKeyEncryptedKeyValueStorage] for the
 * contract and [AppKeyStoreEngine] for how a key is verified and cached.
 */
internal class JvmAppKeyEncryptedKeyValueStorage(
    name: String,
    directory: File,
    key: AppEncryptionKey,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    crossProcessLock: CrossProcessLock = CrossProcessLock(),
    timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
    clock: () -> Instant = Instant::now,
    files: FileAccess = createFileAccess(),
    cipher: EnvelopeCipher = EnvelopeCipher(),
) : AppKeyEncryptedKeyValueStorage {

    private val engine: AppKeyStoreEngine = AppKeyStoreEngine(
        guard = EncryptedFileGuard(
            file = File(directory, "${appKeyStoreId(name)}.bin"),
            files = files,
            crossProcessLock = crossProcessLock,
            timeSource = timeSource,
            clock = clock,
        ),
        dispatcher = dispatcher,
        cipher = cipher,
        initialKey = key,
    )

    override val protection: SecureStorageProtection get() = engine.protection

    override suspend fun get(key: String): StorageResult<String?> = engine.execute(Op.Get(key))

    override suspend fun put(key: String, value: String): StorageResult<Unit> = engine.execute(Op.Put(key, value)).asUnit()

    override suspend fun remove(key: String): StorageResult<Unit> = engine.execute(Op.Remove(key)).asUnit()

    override suspend fun clear(): StorageResult<Unit> = engine.execute(Op.Clear).asUnit()

    override suspend fun ensureKey(): StorageResult<Unit> = engine.execute(Op.Ensure).asUnit()

    override suspend fun changeKey(newKey: AppEncryptionKey): StorageResult<Unit> = engine.changeKey(newKey)
}
