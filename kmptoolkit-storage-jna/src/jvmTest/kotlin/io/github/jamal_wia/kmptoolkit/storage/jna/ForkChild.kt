package io.github.jamal_wia.kmptoolkit.storage.jna

import io.github.jamal_wia.kmptoolkit.storage.StorageConfig
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.createKeyValueStorage
import java.io.File
import kotlinx.coroutines.runBlocking

/**
 * The main class of the forked JVMs of [ForkedJvmTest]: it reaches the selector of `kmptoolkit-storage` by reflection
 * (the selector is `internal` there) and prints one verdict line per fact, which the parent test reads. It must never
 * name a JNA type or the binding class statically: it also runs on a classpath that does not contain them.
 */
internal object ForkChild {
    private const val ENVIRONMENT: String = "io.github.jamal_wia.kmptoolkit.storage.internal.desktop.SelectorEnvironment"
    private const val SELECTOR: String = "io.github.jamal_wia.kmptoolkit.storage.internal.desktop.BindingSelector"

    @JvmStatic
    fun main(arguments: Array<String>) {
        val mode: String = arguments[0]
        println("RUNTIME=${Runtime.version().feature()}")
        when (mode) {
            "selection" -> println("SELECTION=${select()}")
            "selection-and-plain-store" -> {
                println("SELECTION=${select()}")
                println("PLAIN=${plainStore(File(arguments[1]))}")
            }
            "loader" -> println("LOADED=${loadByFixedName()}")
            else -> error("unknown mode $mode")
        }
    }

    /** The verdict of the real selector over the real process environment, as its own `toString`. */
    private fun select(): String {
        val environmentType: Class<*> = Class.forName(ENVIRONMENT)
        val companion: Any = environmentType.getField("Companion").get(null)
        val environment: Any = companion.javaClass.getMethod("system").invoke(companion)
        val selector: Any = Class.forName(SELECTOR).getConstructor(environmentType).newInstance(environment)
        return selector.javaClass.getMethod("select").invoke(selector).toString()
    }

    /** What the selector's own by-name loader answers for the fixed JNA class name: a class name, or `null`. */
    private fun loadByFixedName(): String {
        val environmentType: Class<*> = Class.forName(ENVIRONMENT)
        val companion: Any = environmentType.getField("Companion").get(null)
        val name: String = environmentType.getField("JNA_CLASS").get(null) as String
        val loaded: Any? = companion.javaClass.getMethod("loadByName", String::class.java).invoke(companion, name)
        return loaded?.javaClass?.name ?: "null"
    }

    private fun plainStore(directory: File): String = runBlocking {
        val store = createKeyValueStorage(directory, StorageConfig("fork-child"))
        val put: StorageResult<Unit> = store.put("k", "v")
        val got: StorageResult<String?> = store.get("k")
        if (put is StorageResult.Success && got == StorageResult.Success("v")) "ok" else "failed: $put $got"
    }
}
