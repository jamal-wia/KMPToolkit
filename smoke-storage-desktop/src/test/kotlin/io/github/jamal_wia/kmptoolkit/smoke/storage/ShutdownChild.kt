package io.github.jamal_wia.kmptoolkit.smoke.storage

import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * The program [ShutdownTest] starts as a separate JVM and signals: it arms the real [ShutdownCleanup] with a removal that writes a marker
 * file, makes a file in the directory it was given, prints `ready` and blocks until it is stopped from outside. Arguments: the directory,
 * the marker file, how long the hook waits for the run (the run here never ends, so the hook cleans up itself after that wait). It never ends by itself, so the only way out is the signal under test.
 */
internal object ShutdownChild {
    @JvmStatic
    fun main(args: Array<String>) {
        val directory: File = File(args[0])
        val marker: File = File(args[1])
        val cleanup: ShutdownCleanup = ShutdownCleanup(directory, runWaitMillis = args[2].toLong())
        cleanup.arm(
            object : KeyItemRemoval {
                override fun remove(expectPresent: Boolean) {
                    marker.writeText("removed expectPresent=$expectPresent")
                }
            },
        )
        cleanup.install()
        File(directory, "left-by-the-run.txt").writeText("data")
        println("ready")
        System.out.flush()
        CountDownLatch(1).await()
    }
}
