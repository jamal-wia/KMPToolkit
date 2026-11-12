package io.github.jamal_wia.kmptoolkit.video.player.javafx

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue
import kotlin.test.fail

/** What [runChildRetryingKnownCrash] saw in its last (successful or non-crashing) attempt. */
internal class ChildRun(val exit: Int, val output: String, val attempts: Int, val knownCrashes: List<String>) {

    /** The `RESULT key=value` lines of the output. */
    fun results(): Map<String, String> = output.lines().filter { it.startsWith("RESULT ") }.associate {
        val pair: List<String> = it.removePrefix("RESULT ").split("=", limit = 2)
        pair[0] to pair.getOrElse(1) { "" }
    }
}

/**
 * Starts `mainClass` in a child JVM (test classpath, crash flags of [childJvmCrashGuardArgs]) and returns
 * its output. The known environment crash (OpenJFX macOS display link race, `plans/javafx-native-crash-advice.md`)
 * is recognised by exit value 137 or 134 together with an `hs_err` file of that child whose problematic frame
 * is in CoreVideo; such an attempt is repeated, up to [maxAttempts] in all. Any other crash, a hang past
 * [timeoutS], or [maxAttempts] known crashes in a row fails the calling test. [hsErrDir] is where the
 * child writes its crash log (the build's `hs_err` directory by default).
 */
internal fun runChildRetryingKnownCrash(
    mainClass: String,
    args: List<String> = emptyList(),
    timeoutS: Long = 180L,
    maxAttempts: Int = 3,
    hsErrDir: String? = System.getProperty(HS_ERR_DIR_PROPERTY),
): ChildRun {
    val java: String = File(File(System.getProperty("java.home"), "bin"), "java").path
    val knownCrashes: MutableList<String> = mutableListOf()
    for (attempt: Int in 1..maxAttempts) {
        val process: Process = ProcessBuilder(
            listOf(java) + childJvmCrashGuardArgs(hsErrDir) + listOf("-cp", System.getProperty("java.class.path"), mainClass) + args,
        ).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }.apply { isDaemon = true; start() }
        val finished: Boolean = process.waitFor(timeoutS, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(5_000L)
        assertTrue(finished, "child JVM $mainClass hung (attempt $attempt):\n$output")
        val exit: Int = process.exitValue()
        if (exit != 137 && exit != 134) return ChildRun(exit, output.toString(), attempt, knownCrashes)
        val log = File(hsErrDir.orEmpty(), "hs_err_pid${process.pid()}.log")
        val frame: String = if (log.isFile) problematicFrame(log) else ""
        if ("CoreVideo" !in frame) {
            fail("child JVM $mainClass crashed with exit $exit and this is not the known CoreVideo crash (hs_err ${log.path}, frame '$frame'):\n$output")
        }
        knownCrashes += log.name
    }
    fail("child JVM $mainClass hit the known CoreVideo crash in $maxAttempts of $maxAttempts attempts: $knownCrashes")
}

private fun problematicFrame(log: File): String {
    val lines: List<String> = log.readLines()
    val at: Int = lines.indexOfFirst { it.startsWith("# Problematic frame:") }
    return if (at >= 0 && at + 1 < lines.size) lines[at + 1] else ""
}
