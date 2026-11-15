package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.jar.JarFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Plan T8 (plain-store users never load native code) and the JNA-free halves of T9 (binding selection), sto-ffm.
 *
 * - **Structural:** in the packaged jvm jar no class outside `internal/desktop/ffm` references `java.lang.foreign`, no class at all
 *   references `com.sun.jna`, and the FFM binding and the ProGuard rules ride in the jar.
 * - **Runtime, forked JVMs (21 and 25):** plain-store operations load no `java.lang.foreign.Linker`, nothing of
 *   `internal.desktop.ffm` and no `com.sun.jna.Native`; the selector serves FFM on 25, answers `UNSUPPORTED_RUNTIME` on 21 without
 *   the JNA artifact, and `NATIVE_ACCESS_UNAVAILABLE` on 25 under `--illegal-native-access=deny`.
 * - The JNA rows of T9 (25 with the artifact serves FFM; 21 with it serves JNA) belong to the group that creates that artifact.
 */
class DesktopBindingPackagingTest {

    private fun jar(): JarFile = JarFile(File(requireNotNull(System.getProperty("kmptoolkit.test.targetJar")) { "kmptoolkit.test.targetJar is set by the build" }))

    private fun javaOf(property: String): String {
        val home: String = requireNotNull(System.getProperty(property)) { "$property is set by the build" }
        return File(File(home, "bin"), "java").path
    }

    private fun contains(bytes: ByteArray, text: String): Boolean {
        val needle: ByteArray = text.toByteArray(Charsets.UTF_8)
        outer@ for (start: Int in 0..bytes.size - needle.size) {
            for (offset: Int in needle.indices) if (bytes[start + offset] != needle[offset]) continue@outer
            return true
        }
        return false
    }

    @Test
    fun `only the ffm package references java lang foreign and nothing references jna`() {
        jar().use { jar: JarFile ->
            val classes: List<String> = jar.entries().asSequence().map { it.name }.filter { it.endsWith(".class") }.toList()
            assertTrue(classes.any { it.contains("/internal/desktop/ffm/FfmNativeKeyStoreBinding") }, "the FFM binding must ride in the jvm jar")
            var foreignReferrers = 0
            for (name: String in classes) {
                val bytes: ByteArray = jar.getInputStream(jar.getEntry(name)).use { it.readBytes() }
                assertFalse(contains(bytes, "com/sun/jna"), "$name references JNA")
                if (contains(bytes, "java/lang/foreign")) {
                    foreignReferrers++
                    assertTrue(name.contains("/internal/desktop/ffm/"), "$name is outside the ffm package but references java.lang.foreign")
                }
            }
            assertTrue(foreignReferrers > 0, "control: the ffm classes do reference java.lang.foreign")
        }
    }

    @Test
    fun `the jar carries the proguard rules that keep the binding loaded by name`() {
        jar().use { jar: JarFile ->
            val entry = requireNotNull(jar.getEntry("META-INF/proguard/kmptoolkit-storage.pro")) { "ProGuard rules are missing from the jar" }
            val rules: String = jar.getInputStream(entry).use { String(it.readBytes(), Charsets.UTF_8) }
            assertTrue(rules.contains("-keep class io.github.jamal_wia.kmptoolkit.storage.internal.desktop.ffm.FfmNativeKeyStoreBinding"), rules)
            assertTrue(rules.contains("-dontwarn java.lang.foreign.**"), rules)
        }
    }

    private class Child(val exit: Int, val output: String, val classLog: String)

    private fun fork(java: String, jvmArgs: List<String>, args: List<String>): Child {
        val work: File = Files.createTempDirectory("kmptoolkit-storage-fork").toFile()
        try {
            val log = File(work, "classes.log")
            val command: List<String> = listOf(java) + jvmArgs + listOf(
                "-Xlog:class+load:file=${log.path}", "-Djna.tmpdir=${File(work, "jna").also { it.mkdirs() }.path}",
                "-cp", System.getProperty("java.class.path"), DesktopBindingProbeMain::class.java.name,
            ) + args.map { if (it == "<dir>") work.path else it }
            val process: Process = ProcessBuilder(command).redirectErrorStream(true).start()
            val output: String = process.inputStream.bufferedReader().readText()
            check(process.waitFor(120, TimeUnit.SECONDS)) { "the child JVM did not finish: $command" }
            assertEquals(0, File(work, "jna").list()?.size ?: 0, "jna.tmpdir must stay empty")
            return Child(process.exitValue(), output, if (log.exists()) log.readText() else "")
        } finally {
            work.deleteRecursively()
        }
    }

    @Test
    fun `plain store operations load no foreign linker no ffm class and no jna native on 21 and 25`() {
        for (property: String in listOf("kmptoolkit.test.javaHome21", "kmptoolkit.test.javaHome25")) {
            val child: Child = fork(javaOf(property), emptyList(), listOf("plain", "<dir>"))
            assertEquals(0, child.exit, "$property: ${child.output}")
            assertTrue(child.output.contains("PLAIN-OK"), "$property: ${child.output}")
            assertTrue(child.classLog.contains("io.github.jamal_wia.kmptoolkit.storage.JvmKeyValueStorage"), "control: the class-load log recorded the store ($property)")
            assertFalse(child.classLog.contains("java.lang.foreign.Linker"), "$property loaded java.lang.foreign.Linker")
            assertFalse(child.classLog.contains("internal.desktop.ffm"), "$property loaded an FFM class")
            assertFalse(child.classLog.contains("com.sun.jna.Native"), "$property loaded JNA")
        }
    }

    @Test
    fun `selection serves ffm on 25 and answers unsupported runtime on 21 without the jna artifact`() {
        val os: DesktopOs? = DesktopOs.fromOsName(System.getProperty("os.name").orEmpty())
        if (os == null) return
        val on25: Child = fork(javaOf("kmptoolkit.test.javaHome25"), listOf("--enable-native-access=ALL-UNNAMED"), listOf("select"))
        assertTrue(on25.output.contains("SELECTION=Selected($os, FFM)"), on25.output)
        val on21: Child = fork(javaOf("kmptoolkit.test.javaHome21"), emptyList(), listOf("select"))
        assertTrue(on21.output.contains("SELECTION=Unsupported(UNSUPPORTED_RUNTIME)"), on21.output)
    }

    @Test
    fun `a denied native access on 25 is NATIVE_ACCESS_UNAVAILABLE and not an exception`() {
        val os: DesktopOs? = DesktopOs.fromOsName(System.getProperty("os.name").orEmpty())
        if (os == null) return
        val denied: Child = fork(javaOf("kmptoolkit.test.javaHome25"), listOf("--illegal-native-access=deny"), listOf("select"))
        assertEquals(0, denied.exit, denied.output)
        assertTrue(denied.output.contains("SELECTION=Unsupported(NATIVE_ACCESS_UNAVAILABLE)"), denied.output)
    }
}
