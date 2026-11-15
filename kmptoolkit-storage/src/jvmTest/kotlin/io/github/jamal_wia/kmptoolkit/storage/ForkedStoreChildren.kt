package io.github.jamal_wia.kmptoolkit.storage

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.CrossProcessLock
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.LockAcquisition
import java.io.File
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
