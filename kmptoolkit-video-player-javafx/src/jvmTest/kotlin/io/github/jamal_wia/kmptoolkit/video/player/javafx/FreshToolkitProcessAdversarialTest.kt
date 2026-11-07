package io.github.jamal_wia.kmptoolkit.video.player.javafx

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Starts the real JavaFX toolkit from scratch in a child JVM (the test JVM's toolkit is long
 * running by the time these tests are reached): many callers at once, a host that started the
 * toolkit first or concurrently, and a toolkit that exited before the first check. Bounded: the
 * child is killed after 90 s.
 */
class FreshToolkitProcessAdversarialTest {

    private fun runChild(scenario: String): Map<String, String> {
        val java: String = File(System.getProperty("java.home"), "bin/java").path
        val process: Process = ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"),
            "io.github.jamal_wia.kmptoolkit.video.player.javafx.FreshToolkitMain", scenario,
        ).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }.apply { isDaemon = true; start() }
        val finished: Boolean = process.waitFor(90L, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(5_000L)
        assertTrue(finished, "child JVM hung for scenario $scenario:\n$output")
        return output.lines().filter { it.startsWith("RESULT ") }.associate {
            val pair: List<String> = it.removePrefix("RESULT ").split("=", limit = 2)
            pair[0] to pair.getOrElse(1) { "" }
        }.also { assertTrue(it.isNotEmpty(), "no RESULT from scenario $scenario:\n$output") }
    }

    @Test
    fun `sixteen threads asking at once on a fresh toolkit all get true`() {
        assumeJavaFxMedia()
        val result: Map<String, String> = runChild("many")
        assertEquals("0", result["hung"], "$result")
        assertEquals("{true=16}", result["answers"], "$result")
    }

    @Test
    fun `a host that started the toolkit first keeps its implicit exit policy and the engine sees it running`() {
        assumeJavaFxMedia()
        val result: Map<String, String> = runChild("host-first")
        assertEquals("{true=8}", result["answers"], "$result")
        assertEquals("true", result["implicitExit"], "the host's implicit exit policy was overwritten: $result")
    }

    @Test
    fun `a host starting the toolkit while eight threads ask never hangs and everyone sees it running`() {
        assumeJavaFxMedia()
        val result: Map<String, String> = runChild("host-concurrent")
        assertEquals("0", result["hung"], "$result")
        assertEquals("{true=8}", result["answers"], "$result")
    }

    @Test
    fun `eight threads asking after the toolkit exited are all told false at once`() {
        assumeJavaFxMedia()
        val result: Map<String, String> = runChild("exited")
        assertEquals("0", result["hung"], "$result")
        assertEquals("{false=8}", result["answers"], "$result")
        assertTrue((result["elapsedMs"] ?: "999999").toLong() < 5_000L, "slow answer after exit: $result")
    }
}
