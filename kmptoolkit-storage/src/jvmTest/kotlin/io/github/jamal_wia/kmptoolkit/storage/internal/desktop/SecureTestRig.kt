package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import io.github.jamal_wia.kmptoolkit.storage.DesktopSecureStorageOptions
import io.github.jamal_wia.kmptoolkit.storage.JvmSecureKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.secureStoreId
import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.time.TimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Everything a test of the OS-key store needs: a fresh directory, the fake binding on the OS under test, an own native
 * executor and an own key cache (so nothing leaks between tests), an environment with a session bus, and a fixed clock.
 * [close] removes the directory and ends the executor thread.
 */
internal class SecureTestRig(
    val os: DesktopOs = DesktopOs.MACOS,
    val fake: FakeNativeKeyStoreBinding = FakeNativeKeyStoreBinding(),
    val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    val clock: () -> Instant = { FIXED_NOW },
    /** A virtual time source for lock-budget tests; `null` leaves the store's own (the real clock is the production default). */
    val timeSource: TimeSource.WithComparableMarks? = null,
) : AutoCloseable {
    val directory: File = Files.createTempDirectory("kmptoolkit-secure").toFile()
    val executor: NativeExecutor = NativeExecutor()
    val cache: OsKeyCache = OsKeyCache()
    var hasSessionBus: Boolean = true
    private val environment: SecureStoreEnvironment = SecureStoreEnvironment(
        getenv = { name: String -> if (name == "DBUS_SESSION_BUS_ADDRESS" && hasSessionBus) "unix:path=/run/user/1/bus" else null },
        exists = { false },
    )

    fun source(): BindingSource = FixedBindingSource(BindingSelection.Selected(fake, os, BindingKind.FAKE))

    fun store(
        name: String = NAME,
        dir: File = directory,
        options: DesktopSecureStorageOptions = DesktopSecureStorageOptions(),
        source: BindingSource = source(),
        cache: OsKeyCache = this.cache,
        executor: NativeExecutor = this.executor,
        lock: CrossProcessLock = CrossProcessLock(),
        files: FileAccess = createFileAccess(),
        resolver: StorePathResolver = StorePathResolver.real,
        maxStoreBytes: Long = EncryptedFileGuard.MAX_STORE_BYTES,
        cipher: EnvelopeCipher = EnvelopeCipher(),
    ): JvmSecureKeyValueStorage {
        val virtual: TimeSource.WithComparableMarks? = timeSource
        return if (virtual == null) {
            JvmSecureKeyValueStorage(
                name = name, directory = dir, options = options, dispatcher = dispatcher, source = source, executor = executor,
                cache = cache, environment = environment, crossProcessLock = lock, clock = clock, files = files,
                pathResolver = resolver, maxStoreBytes = maxStoreBytes, cipher = cipher,
            )
        } else {
            JvmSecureKeyValueStorage(
                name = name, directory = dir, options = options, dispatcher = dispatcher, source = source, executor = executor,
                cache = cache, environment = environment, crossProcessLock = lock, timeSource = virtual, clock = clock, files = files,
                pathResolver = resolver, maxStoreBytes = maxStoreBytes, cipher = cipher,
            )
        }
    }

    fun dataFile(name: String = NAME, dir: File = directory): File = File(dir, "${secureStoreId(name)}.bin")

    /** Every file in the store directory, by name. */
    fun fileNames(dir: File = directory): List<String> = dir.list().orEmpty().sorted()

    /** Every regular file of the directory, as bytes: for the canary scan. */
    fun allBytes(dir: File = directory): List<Pair<String, ByteArray>> =
        dir.listFiles().orEmpty().filter { it.isFile }.map { it.name to it.readBytes() }

    override fun close() {
        executor.close()
        directory.deleteRecursively()
    }

    companion object {
        const val NAME: String = "rig"
        val FIXED_NOW: Instant = Instant.parse("2026-11-15T12:00:00Z")
    }
}
