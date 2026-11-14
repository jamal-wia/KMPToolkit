package io.github.jamal_wia.kmptoolkit.video.player.javafx

import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The documented consequence of "the toolkit stays up" (`docs/kmptoolkit-video-player-javafx/05-platform-notes.md`, "Threading and the JavaFX
 * toolkit"; D2 r16 ruling r16-X1): `release()` frees the player, not the toolkit, so a program whose `main` simply returns after the release
 * does not end on its own (OpenJFX's own non-daemon `JavaFX-KeepAlive` thread, plus the implicit exit the engine turned off), and a program that
 * calls `Platform.exit()` does. Two child JVMs (the mechanism of [ToolkitExitLifecycleProcessAdversarialTest]) run
 * [ToolkitKeepAliveAfterReleaseMain]. Every wait is a bounded wait on the child's own output or on its exit; the child that must stay alive is
 * killed by the test. No file is created, so no temp directory is needed.
 */
class ToolkitKeepAliveAfterReleaseProcessTest {

    @BeforeTest
    fun setUp() {
        assumeJavaFxMedia()
    }

    /** A child JVM whose output lines are handed over as they arrive, so the test waits on events, never on the clock. */
    private class Child(scenario: String) {
        private val lines = LinkedBlockingQueue<String>()
        private val all: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        private val process: Process
        private val reader: Thread

        init {
            val java: String = File(System.getProperty("java.home"), "bin/java").path
            process = ProcessBuilder(
                listOf(java) + childJvmCrashGuardArgs() + listOf(
                    "-cp", System.getProperty("java.class.path"),
                    "io.github.jamal_wia.kmptoolkit.video.player.javafx.ToolkitKeepAliveAfterReleaseMain", scenario,
                ),
            ).redirectErrorStream(true).start()
            reader = Thread {
                process.inputStream.bufferedReader().forEachLine { line: String -> all += line; lines.put(line) }
            }.apply { isDaemon = true; start() }
        }

        /** The first not yet taken line that starts with [prefix], or null when none arrives within [seconds]. */
        fun awaitLine(prefix: String, seconds: Long): String? {
            // timing-hygiene-ignore: TH2 bound hang deadline only, the wait ends on the child's own line
            val deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
            while (true) {
                // timing-hygiene-ignore: TH2 bound hang deadline only, the wait ends on the child's own line
                val remaining: Long = deadline - System.nanoTime()
                if (remaining <= 0L) return null
                val line: String = lines.poll(remaining, TimeUnit.NANOSECONDS) ?: return null
                if (line.startsWith(prefix)) return line
            }
        }

        fun isAlive(): Boolean = process.isAlive

        /** True when the child ended within [seconds]. */
        fun awaitExit(seconds: Long): Boolean = process.waitFor(seconds, TimeUnit.SECONDS)

        fun output(): String = synchronized(all) { all.joinToString("\n") }

        fun kill() {
            process.destroyForcibly()
            process.waitFor(10L, TimeUnit.SECONDS)
            reader.join(5_000L)
        }
    }

    @Test
    fun aProgramThatCallsPlatformExitAfterTheReleaseEndsOnItsOwn() {
        val child = Child("exit")
        try {
            assertNotNull(child.awaitLine("RESULT released=true", 90L), "the child did not release its player:\n${child.output()}")
            // The clock starts when the child has released and is about to call Platform.exit(): not at the JVM start, which depends on the load.
            assertTrue(child.awaitExit(10L), "the child JVM did not end within 10 s of Platform.exit():\n${child.output()}")
        } finally {
            child.kill()
        }
    }

    @Test
    fun aProgramWhoseMainReturnsAfterTheReleaseStaysAliveBecauseOfOpenJfxsKeepAliveThread() {
        val child = Child("return")
        try {
            val report: String? = child.awaitLine("RESULT nonDaemonAfter6s=", 120L)
            assertNotNull(report, "the child did not report 6 s after main returned:\n${child.output()}")
            // The report comes from a daemon watchdog of the child: the process still running now is the JVM that did not end on its own.
            assertTrue(child.isAlive(), "the JVM ended although JavaFX keeps its non-daemon thread (the documented behaviour changed):\n${child.output()}")
            assertTrue("JavaFX-KeepAlive" in report, "the child names no non-daemon JavaFX-KeepAlive thread: $report\n${child.output()}")
            assertFalse(report.contains("kmptoolkit"), "a thread of this library keeps the JVM alive: $report")
        } finally {
            child.kill()
        }
    }
}
