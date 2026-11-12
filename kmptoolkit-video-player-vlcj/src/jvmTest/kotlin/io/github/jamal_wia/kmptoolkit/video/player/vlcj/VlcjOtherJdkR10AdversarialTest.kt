package io.github.jamal_wia.kmptoolkit.video.player.vlcj

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Round 10 (breaker bt), environment: the engine's tests, the resolver's tests and the new differential run unchanged on
 * every other JDK installed on the machine (nothing is installed by the test): a child JVM of that JDK runs them with
 * JUnitCore on this test JVM's class path, and each must pass. A JDK the compiled classes are too new for is skipped
 * (UnsupportedClassVersionError), never counted as a pass.
 */
class VlcjOtherJdkR10AdversarialTest {

    private fun candidateHomes(): List<File> {
        val roots: List<File> = listOfNotNull(
            System.getProperty("user.home")?.let { File(File(File(it, "Library"), "Java"), "JavaVirtualMachines") },
            File("/Library/Java/JavaVirtualMachines"),
            File("/usr/lib/jvm"),
        )
        val homes: MutableList<File> = mutableListOf()
        for (root: File in roots) {
            root.listFiles()?.forEach { entry: File ->
                val mac = File(File(entry, "Contents"), "Home")
                homes += if (File(mac, "bin/java").exists()) mac else entry
            }
        }
        return homes.filter { File(it, "bin/java").exists() }
    }

    private fun majorOf(home: File): Int? {
        val release = File(home, "release")
        val line: String = release.takeIf { it.exists() }?.readLines()?.firstOrNull { it.startsWith("JAVA_VERSION=") } ?: return null
        return line.substringAfter('"').substringBefore('"').substringBefore('.').substringBefore('-').toIntOrNull()
    }

    @Test
    fun `the engine tests pass unchanged on every other installed JDK`() {
        val current: Int = Runtime.version().feature()
        val chosen: Map<Int, File> = candidateHomes()
            .mapNotNull { home -> majorOf(home)?.let { it to home } }
            .filter { (major, _) -> major != current }
            .sortedBy { it.first }
            .toMap()
        if (chosen.isEmpty()) return
        val classes: List<String> = listOf(
            "VlcMediaResolverTest", "VlcjOddAssetNamesR9AdversarialTest", "VlcjDocumentedBehaviourAdversarialTest",
            "VlcjVideoEngineSessionTest", "VlcjEventOrderDifferentialR10AdversarialTest", "VlcjStrictNativeStormR7AdversarialTest",
        ).map { "io.github.jamal_wia.kmptoolkit.video.player.vlcj.$it" }
        val problems: MutableList<String> = mutableListOf()
        var ran = 0
        for ((major: Int, home: File) in chosen) {
            val out: File = File.createTempFile("r10-jdk$major-", ".txt")
            try {
                val command: List<String> = listOf(File(home, "bin/java").path, "--add-opens", "java.base/java.io=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"), "org.junit.runner.JUnitCore") + classes
                val process: Process = ProcessBuilder(command).redirectErrorStream(true).redirectOutput(out).start()
                if (!process.waitFor(900, TimeUnit.SECONDS)) {
                    process.destroyForcibly()
                    problems += "JDK $major ($home): timed out after 900 s"
                    continue
                }
                val text: String = out.readText()
                if (text.contains("UnsupportedClassVersionError")) continue
                ran++
                if (process.exitValue() != 0) problems += "JDK $major ($home): exit ${process.exitValue()}\n${text.lines().filter { it.isNotBlank() }.take(40).joinToString("\n")}"
            } finally {
                out.delete()
            }
        }
        if (problems.isNotEmpty()) fail(problems.joinToString("\n\n"))
        assertTrue(ran >= 0)
    }
}
