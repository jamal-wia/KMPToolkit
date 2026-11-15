package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.StorageConfig
import io.github.jamal_wia.kmptoolkit.storage.createKeyValueStorage
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * The program the forked JVMs of [DesktopBindingPackagingTest] run (plan T8, T9). `plain <dir>` does plain-store operations only;
 * `select` asks the process binding selector what it would serve and prints that on one line.
 */
object DesktopBindingProbeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "plain" -> runBlocking {
                val store = createKeyValueStorage(File(args[1]), StorageConfig(name = "probe"))
                check(store.put("k", "v") is io.github.jamal_wia.kmptoolkit.storage.StorageResult.Success)
                check(store.get("k") is io.github.jamal_wia.kmptoolkit.storage.StorageResult.Success)
                println("PLAIN-OK")
            }
            "select" -> println("SELECTION=" + BindingSelector.process.select())
            else -> error("unknown mode ${args.toList()}")
        }
    }
}
