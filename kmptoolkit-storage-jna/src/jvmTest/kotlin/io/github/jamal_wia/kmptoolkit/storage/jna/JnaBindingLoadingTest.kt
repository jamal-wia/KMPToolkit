package io.github.jamal_wia.kmptoolkit.storage.jna

import com.sun.jna.Library
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.NativeKeyStoreBinding
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Assume

/**
 * How `kmptoolkit-storage` finds and loads this artifact (plan 3.5 discovery, 4.2, T9) and what the artifact ships: the
 * fixed class name, a constructor that touches nothing, a binding that loads its libraries on the first call and never
 * earlier, and the ProGuard rules that keep what is found by name.
 */
class JnaBindingLoadingTest {

    private val fixedName = "io.github.jamal_wia.kmptoolkit.storage.jna.JnaNativeKeyStoreBinding"

    @Test
    fun `the class the selector looks for by name exists, has a public no-argument constructor and implements the seam`() {
        val selectorName: String = Class.forName("io.github.jamal_wia.kmptoolkit.storage.internal.desktop.SelectorEnvironment")
            .getField("JNA_CLASS").get(null) as String
        assertEquals(fixedName, selectorName, "the name kmptoolkit-storage loads and the class this artifact ships must be the same string")
        val type: Class<*> = Class.forName(fixedName)
        assertTrue(java.lang.reflect.Modifier.isPublic(type.modifiers))
        val created: Any = type.getDeclaredConstructor().newInstance()
        assertIs<NativeKeyStoreBinding>(created)
    }

    @Test
    fun `nothing is loaded by constructing the binding or asking for its call objects, and each library loads once on the first call`() {
        val macLoads = AtomicInteger()
        val windowsLoads = AtomicInteger()
        val linuxLoads = AtomicInteger()
        val fakeMac = FakeMacLibraries()
        val binding = JnaNativeKeyStoreBinding(
            macPlatform = { macLoads.incrementAndGet(); fakeMac.platform },
            windowsPlatform = { windowsLoads.incrementAndGet(); FakeWindowsLibraries().platform },
            linuxPlatform = { linuxLoads.incrementAndGet(); LinuxPlatform.SymbolMissing },
            scratch = ScratchMemory.Default,
        )
        val mac = binding.macOs()
        binding.windows()
        binding.linux()
        assertSame(mac, binding.macOs(), "the call objects are created once")
        assertEquals(listOf(0, 0, 0), listOf(macLoads.get(), windowsLoads.get(), linuxLoads.get()), "no library is touched before a call")

        assertIs<io.github.jamal_wia.kmptoolkit.storage.internal.desktop.RawResult<*>>(mac.copyGenericPassword("s", "a", true))
        mac.deleteGenericPassword("s", "a", allowUi = true)
        assertEquals(listOf(1, 0, 0), listOf(macLoads.get(), windowsLoads.get(), linuxLoads.get()), "only the used OS loads, and only once")
    }

    @Test
    fun `a library that fails to load surfaces from the call as a link error and is tried again by the next call`() {
        val attempts = AtomicInteger()
        val fake = FakeMacLibraries()
        val binding = JnaNativeKeyStoreBinding(
            macPlatform = {
                if (attempts.incrementAndGet() == 1) throw UnsatisfiedLinkError("jnidispatch from a noexec directory")
                fake.platform
            },
            windowsPlatform = { error("not used") },
            linuxPlatform = { error("not used") },
            scratch = ScratchMemory.Default,
        )
        val mac = binding.macOs()
        assertFailsWith<UnsatisfiedLinkError> { mac.probe(true) }
        assertEquals(0, mac.probe(true), "the second call loads again and works (the executor keeps the first failure for the process)")
        assertEquals(2, attempts.get())
    }

    @Test
    fun `the shipped ProGuard rules keep the binding, every JNA interface of the package and JNA itself`() {
        val rules: String = checkNotNull(javaClass.classLoader.getResourceAsStream("META-INF/proguard/kmptoolkit-storage-jna.pro")) {
            "META-INF/proguard/kmptoolkit-storage-jna.pro is not on the classpath"
        }.bufferedReader().use { it.readText() }
        val lines: List<String> = rules.lines().map { it.trim() }.filterNot { it.startsWith("#") || it.isEmpty() }
        assertTrue(lines.any { it.startsWith("-keep class $fixedName ") && it.contains("<init>") }, "the class loaded by name keeps its constructor")
        assertTrue(lines.any { it.startsWith("-keep class com.sun.jna.** ") })
        val libraryRule = "-keep interface io.github.jamal_wia.kmptoolkit.storage.jna.** extends com.sun.jna.Library"
        assertTrue(lines.any { it.startsWith(libraryRule) }, "the JNA interfaces are bound by method name and must not be renamed")
        val callbackRule = "-keep interface io.github.jamal_wia.kmptoolkit.storage.jna.** extends com.sun.jna.Callback"
        assertTrue(lines.any { it.startsWith(callbackRule) }, "a native callback interface is bound to its method by reflection and must not be renamed")
        assertTrue(com.sun.jna.Callback::class.java.isAssignableFrom(GAsyncReadyCallback::class.java), "GAsyncReadyCallback matches the callback rule only if it extends Callback")
        assertTrue(lines.any { line: String -> line.startsWith("-keepattributes") && line.removePrefix("-keepattributes").split(',').map(String::trim).contains("Exceptions") }, "JNA reads the throws clause (the Exceptions attribute) to capture GetLastError")
        for (name: String in listOf("CoreFoundationApi", "SecurityApi", "Crypt32Api", "Kernel32Api", "GLibApi", "GObjectApi", "GioApi", "SecretApi")) {
            val type: Class<*> = Class.forName("io.github.jamal_wia.kmptoolkit.storage.jna.$name")
            assertTrue(Library::class.java.isAssignableFrom(type), "$name matches the rule only if it extends Library")
        }
    }

    @Test
    fun `T9 on JDK 21 with the artifact, the selector picks JNA, and picking it loads no JNA native code`() {
        Assume.assumeTrue("T9 describes the JDK 21 runtime", Runtime.version().feature() == 21)
        val output: String = fork(mode = "selection", withArtifact = true)
        assertTrue(Regex("SELECTION=Selected\\((MACOS|WINDOWS|LINUX), JNA\\)").containsMatchIn(output), "selector verdict missing in:\n$output")
        assertTrue(output.contains(fixedName.replace('.', '/')) || output.contains(fixedName), "control: the binding class was loaded in:\n${tail(output)}")
        assertFalse(output.contains("com.sun.jna.Native "), "selecting the binding must not load JNA's native library; loaded classes:\n${tail(output)}")
    }

    @Test
    fun `T9 on JDK 21 without the artifact, secure selection is UNSUPPORTED_RUNTIME and the plain store works`() {
        Assume.assumeTrue("T9 describes the JDK 21 runtime", Runtime.version().feature() == 21)
        val directory: File = Files.createTempDirectory("kmptoolkit-jna-t9").toFile()
        try {
            val output: String = fork(mode = "selection-and-plain-store", withArtifact = false, argument = directory.path)
            assertTrue(output.contains("SELECTION=Unsupported(UNSUPPORTED_RUNTIME)"), "selector verdict missing in:\n${tail(output)}")
            assertTrue(output.contains("PLAIN=ok"), "the plain store must work without the artifact:\n${tail(output)}")
            assertFalse(output.contains(fixedName), "control: the JNA binding class must not have been on the classpath:\n${tail(output)}")
            assertFalse(output.contains("com.sun.jna."), "no JNA class is loaded without the artifact:\n${tail(output)}")
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `the by-name loader returns the binding with the artifact and null without it`() {
        Assume.assumeTrue("T9 describes the JDK 21 runtime", Runtime.version().feature() == 21)
        assertTrue(fork(mode = "loader", withArtifact = true).contains("LOADED=$fixedName"))
        assertTrue(fork(mode = "loader", withArtifact = false).contains("LOADED=null"))
    }

    private fun tail(output: String): String = output.lines().filterNot { it.startsWith("[") && !it.contains("jna") && !it.contains("kmptoolkit") }.takeLast(40).joinToString("\n")

    /** Runs [ForkChild] in a new JVM of the same runtime, with or without this artifact and JNA on its classpath, and returns its output. */
    private fun fork(mode: String, withArtifact: Boolean, argument: String? = null): String {
        val entries: List<String> = System.getProperty("java.class.path").split(File.pathSeparator).filter { it.isNotEmpty() }
        val artifact: List<String> = entries.filter { holdsBinding(File(it)) || File(it).name.startsWith("jna-jpms") }
        assertTrue(artifact.size >= 2, "control: the artifact's classes and jna-jpms must be on this test's classpath, found $artifact")
        val classpath: String = (if (withArtifact) entries else entries - artifact.toSet()).joinToString(File.pathSeparator)
        val java: String = File(File(System.getProperty("java.home"), "bin"), "java").path
        val command: MutableList<String> = mutableListOf(java, "-Xlog:class+load:stdout", "-cp", classpath, ForkChild::class.java.name, mode)
        if (argument != null) command.add(argument)
        val process: Process = ProcessBuilder(command).redirectErrorStream(true).start()
        val captured = StringBuilder()
        val reader = Thread { process.inputStream.bufferedReader().forEachLine { synchronized(captured) { captured.appendLine(it) } } }
        reader.isDaemon = true
        reader.start()
        val finished: Boolean = process.waitFor(120, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        reader.join(TimeUnit.SECONDS.toMillis(10))
        val text: String = synchronized(captured) { captured.toString() }
        assertTrue(finished, "the forked JVM hung; output so far:\n${tail(text)}")
        assertEquals(0, process.exitValue(), "the forked JVM failed:\n${tail(text)}")
        assertNotNull(Regex("RUNTIME=21").find(text), "the fork ran on another runtime:\n${tail(text)}")
        return text
    }

    private fun holdsBinding(entry: File): Boolean {
        val path = "io/github/jamal_wia/kmptoolkit/storage/jna/JnaNativeKeyStoreBinding.class"
        return when {
            entry.isDirectory -> File(entry, path).exists()
            entry.isFile && entry.name.endsWith(".jar") -> ZipFile(entry).use { it.getEntry(path) != null }
            else -> false
        }
    }
}
