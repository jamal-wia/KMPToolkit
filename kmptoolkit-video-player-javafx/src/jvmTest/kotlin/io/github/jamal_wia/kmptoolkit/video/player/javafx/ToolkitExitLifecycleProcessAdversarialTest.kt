package io.github.jamal_wia.kmptoolkit.video.player.javafx

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A host that owns the toolkit calls `Platform.exit()` while the engine is in use, in a child JVM
 * (the test JVM's own toolkit must stay alive). Bounded: the child is killed after 120 s.
 */
class ToolkitExitLifecycleProcessAdversarialTest {

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
    fun `the exit is noticed by the very next call, not after a delay`() {
        assumeJavaFxMedia()
        val result: Map<String, String> = runChild("immediate")
        assertTrue(result["crashed"] == null, "$result")
        assertTrue(result["answers"]!!.startsWith("[false, false"), "the runtime still said true right after Platform.exit(): $result")
        assertEquals("RuntimeUnavailable", result["load"], "$result")
        assertTrue(result.getValue("loadMs").toLong() < 1_500L, "a load right after Platform.exit() waited: $result")
    }

    @Test
    fun `an engine that was playing when the host exited the toolkit stays callable and cannot be reloaded`() {
        assumeJavaFxMedia()
        val result: Map<String, String> = runChild("playing")
        assertTrue(result["crashed"] == null, "$result")
        assertEquals("ok", result["firstLoad"], "$result")
        assertTrue(result.getValue("callsMs").toLong() < 1_000L, "calls after the exit were slow: $result")
        assertEquals("RuntimeUnavailable", result["reload"], "$result")
        assertTrue(result.getValue("reloadMs").toLong() < 1_000L, "a reload after the exit waited: $result")
        assertEquals("[false]", result["availableAfter"], "$result")
        assertTrue(result.getValue("manyMs").toLong() < 3_000L, "300 availability probes were slow: $result")
        assertTrue(result.getValue("twentyLoadsMs").substringBefore(' ').toLong() < 5_000L, "twenty failing loads were slow: $result")
        assertTrue(result.getValue("threadGrowth").toInt() <= 5, "threads grew after the exit: $result")
    }

    @Test
    fun `loads racing the exit all end within their ready timeout and nothing hangs`() {
        assumeJavaFxMedia()
        repeat(2) {
            val result: Map<String, String> = runChild("during-load")
            assertTrue(result["crashed"] == null, "$result")
            assertEquals("0", result["hung"], "$result")
            assertTrue(result.getValue("totalMs").toLong() < 20_000L, "loads racing the exit took too long: $result")
        }
    }

    @Test
    fun `exiting the toolkit from inside the completion callback and releasing there does not hang or throw`() {
        assumeJavaFxMedia()
        val result: Map<String, String> = runChild("callback")
        assertTrue(result["crashed"] == null, "$result")
        assertEquals("ok", result["firstLoad"], "$result")
        assertEquals("true", result["completedInTime"], "$result")
        assertEquals("RuntimeUnavailable", result["reload"], "$result")
        assertTrue(result.getValue("reloadMs").toLong() < 1_000L, "a reload after the exit waited: $result")
    }
}
