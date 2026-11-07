package io.github.jamal_wia.kmptoolkit.video.player.javafx

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The host's own last window closes (implicit exit), in a child JVM; scenarios `implicit` and `implicit-immediate`
 * of ExitLifecycleMain (the test JVM's own toolkit must stay alive). Bounded: the child is killed after 120 s.
 */
class ToolkitImplicitExitAdversarialTest {

    private fun runChild(scenario: String): Map<String, String> {
        val java: String = File(System.getProperty("java.home"), "bin/java").path
        val process: Process = ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"),
            "io.github.jamal_wia.kmptoolkit.video.player.javafx.ExitLifecycleMain", scenario,
        ).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread { process.inputStream.bufferedReader().forEachLine { output.appendLine(it) } }.apply { isDaemon = true; start() }
        val finished: Boolean = process.waitFor(120L, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(5_000L)
        assertTrue(finished, "child JVM hung for scenario $scenario:\n$output")
        return output.lines().filter { it.startsWith("RESULT ") }.associate {
            val pair: List<String> = it.removePrefix("RESULT ").split("=", limit = 2)
            pair[0] to pair.getOrElse(1) { "" }
        }.also { assertTrue(it.isNotEmpty(), "no RESULT from scenario $scenario:\n$output") }
    }

    @Test
    fun `the host closing its last window takes the toolkit down and the engine fails fast afterwards`() {
        assumeJavaFxMedia()
        val result: Map<String, String> = runChild("implicit")
        assertTrue(result["crashed"] == null, "$result")
        assertEquals("ok", result["firstLoad"], "$result")
        assertEquals("false", result["availableAfter"], "$result")
        assertEquals("RuntimeUnavailable", result["reload"], "$result")
        assertTrue(result.getValue("callsMs").toLong() < 1_000L && result.getValue("reloadMs").toLong() < 1_000L, "slow after the exit: $result")
    }

    @Test
    fun `a load made right after the last window closed fails without waiting for the ready timeout`() {
        assumeJavaFxMedia()
        val result: Map<String, String> = runChild("implicit-immediate")
        assertTrue(result["crashed"] == null, "$result")
        assertEquals("ok", result["firstLoad"], "$result")
        assertEquals("RuntimeUnavailable", result["reload"], "$result")
        assertTrue(result.getValue("reloadMs").toLong() < 3_500L, "slow after the exit: $result")
    }
}
