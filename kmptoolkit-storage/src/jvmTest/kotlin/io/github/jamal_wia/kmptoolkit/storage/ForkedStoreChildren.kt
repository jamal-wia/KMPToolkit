package io.github.jamal_wia.kmptoolkit.storage

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.CrossProcessLock
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.LockAcquisition
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking

/**
 * Child JVMs of `JvmKeyValueStorageForkedProcessesTest`: real second processes over one directory, so the lock under
 * test is the operating system's and not a stand-in. They talk to the parent over stdout lines and stdin only.
 */

/** Takes the lock of the file named by `args[0]`, says `HELD`, holds it until stdin yields a byte or closes, says `RELEASED`. */
object LockHolderChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val held: LockAcquisition = runBlocking { CrossProcessLock().acquire(File(args[0]), args[1]) }
        check(held is LockAcquisition.Held) { "the child could not take the lock: $held" }
        println("HELD")
        System.out.flush()
        System.`in`.read()
        held.close()
        println("RELEASED")
        System.out.flush()
    }
}

/** Tries once, without waiting, to take the lock of the file named by `args[0]`, and says `FREE` (and lets go) or `BUSY`. */
object LockProbeChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val outcome: LockAcquisition = runBlocking { CrossProcessLock().acquire(File(args[0]), args[1], budget = 1.milliseconds) }
        if (outcome is LockAcquisition.Held) {
            outcome.close()
            println("FREE")
        } else {
            println("BUSY")
        }
        System.out.flush()
    }
}

/** Puts `args[3]` keys named `p<args[2]>-<i>` into the plain store `args[1]` of directory `args[0]`, then says `DONE`. */
object StoreWriterChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val storage: KeyValueStorage = createKeyValueStorage(File(args[0]), StorageConfig(args[1]))
        val id: String = args[2]
        repeat(args[3].toInt()) { index: Int ->
            val result: StorageResult<Unit> = runBlocking { storage.put("p$id-$index", "v$index") }
            check(result is StorageResult.Success) { "put $index failed: $result" }
        }
        println("DONE")
        System.out.flush()
    }
}

/** Puts `args[4]` keys named `p<args[3]>-<i>` into the OS-key store `args[1]` of directory `args[0]` over a keychain made of the files of `args[2]`. */
object SecureStoreWriterChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val keychain = io.github.jamal_wia.kmptoolkit.storage.internal.desktop.DirectoryKeychainBinding(File(args[2]))
        val rig = io.github.jamal_wia.kmptoolkit.storage.internal.desktop.FixedBindingSource(
            io.github.jamal_wia.kmptoolkit.storage.internal.desktop.BindingSelection.Selected(
                keychain,
                io.github.jamal_wia.kmptoolkit.storage.internal.desktop.DesktopOs.MACOS,
                io.github.jamal_wia.kmptoolkit.storage.internal.desktop.BindingKind.FAKE,
            ),
        )
        val storage: SecureKeyValueStorage = JvmSecureKeyValueStorage(
            name = args[1],
            directory = File(args[0]),
            options = DesktopSecureStorageOptions(),
            source = rig,
            cache = io.github.jamal_wia.kmptoolkit.storage.internal.desktop.OsKeyCache(),
        )
        writeAll(storage, args[3], args[4].toInt())
    }
}

/** Puts `args[3]` keys named `p<args[2]>-<i>` into the raw-key app-key store `args[1]` of directory `args[0]`; the key is 32 bytes of 7. */
object AppKeyStoreWriterChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val storage: KeyValueStorage = createAppKeyEncryptedKeyValueStorage(
            File(args[0]), StorageConfig(args[1]), AppEncryptionKey.Raw { ByteArray(32) { 7 } },
        )
        writeAll(storage, args[2], args[3].toInt())
    }
}

private fun writeAll(storage: KeyValueStorage, id: String, count: Int) {
    repeat(count) { index: Int ->
        val result: StorageResult<Unit> = runBlocking { storage.put("p$id-$index", "v$index") }
        check(result is StorageResult.Success) { "put $index failed: $result" }
    }
    println("DONE")
    System.out.flush()
}
