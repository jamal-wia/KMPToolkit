package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Group sto-desktop, round 16 (review round 38, m1): a class file whose extension is not spelled `.class` in lower case is still a
 * class (a class loader over a directory on a case-insensitive file system loads `x/Hidden.CLASS` as `x.Hidden`, and Gradle packs
 * such a resource verbatim), so EVERY reader of the bytecode scan compares the extension case-insensitively: the jar read
 * ([ClassFileScan.shippedClassBytes]), the walk of the output directories ([ClassFileScan.mainOutputs]) and the plain read of a
 * location ([ClassFileScan.classBytes]). The breaker class `JcaBytecodeScanBreakerR14Test` pins the jar read; this one pins the two
 * directory readers, the loud failure of a file that is named like a class and is none, and the name rule itself.
 *
 * Mutation by reading: restoring the case-sensitive `.class` test in the walk (`outputRoots`) turns the first test red, in
 * `classBytes(URL)` the second, in `shippedClassBytes` the third (and the breaker test); making [ClassFileScan.isClassFileName]
 * return `false` for a name in another case turns the last red.
 */
class JcaBytecodeScanClassExtensionRound16Test {

    private val flagged: Class<*> = JcaScanFixtureFlaggedDirect::class.java
    private val clean: Class<*> = JcaScanFixtureCleanOtherJdk::class.java
    private val expected: String = "${flagged.name.replace('.', '/')} names the class java/security/MessageDigest"

    @Test
    fun `the walk of the output directories reads a class file named in another case`() {
        withDirectory { root: Path ->
            val main: Path = root.resolve("build").resolve("classes").resolve("kotlin").resolve("jvm").resolve("main")
            put(main, clean, "class")
            // The resources of the jvm compilation are packed into the jar; a class among them under a spelling of its own.
            val resources: Path = root.resolve("build").resolve("processedResources").resolve("jvm").resolve("main")
            put(resources, flagged, "CLASS")
            val found: List<String> = ClassFileScan.classBytes(ClassFileScan.mainOutputs(main.toUri().toURL())).map(ClassFileScan::parse).flatMap(ClassFileScan::violations)
            assertTrue(expected in found, "a class under build/processedResources named X.CLASS is read by the walk layer (found $found)")
        }
    }

    @Test
    fun `a plain read of a directory reads a class file named in another case`() {
        for (extension: String in listOf("CLASS", "Class", "cLaSs")) {
            withDirectory { root: Path ->
                put(root, clean, "class")
                put(root, flagged, extension)
                val found: List<String> = ClassFileScan.classBytes(root.toUri().toURL()).map(ClassFileScan::parse).flatMap(ClassFileScan::violations)
                assertTrue(expected in found, "a file named X.$extension is a class file (found $found)")
            }
        }
    }

    @Test
    fun `a file that is named like a class and is not one fails the scan and is never skipped`() {
        withDirectory { root: Path ->
            val jar: Path = root.resolve("fake.jar")
            JarOutputStream(Files.newOutputStream(jar)).use { out: JarOutputStream ->
                out.putNextEntry(JarEntry("a/Clean.class"))
                out.write(bytesOf(clean))
                out.closeEntry()
                out.putNextEntry(JarEntry("x/Hidden.CLASS"))
                out.write("this is not a class file".toByteArray())
                out.closeEntry()
            }
            val read: List<ByteArray> = ClassFileScan.shippedClassBytes(jar)
            assertEquals(2, read.size, "the jar read returns the entry in the other case as well as the lower-case one")
            assertFailsWith<ClassFileScan.MalformedClass>("a body that is no class file fails the parse loudly") { read.forEach(ClassFileScan::parse) }

            // The same in a directory: the file is attributed to a root by its own bytes, which fails, and does not pass.
            val main: Path = root.resolve("build").resolve("classes").resolve("kotlin").resolve("jvm").resolve("main").createDirectories()
            root.resolve("build").resolve("processedResources").resolve("jvm").resolve("main").resolve("x").createDirectories()
                .resolve("Hidden.CLASS").writeBytes("this is not a class file".toByteArray())
            assertFailsWith<ClassFileScan.MalformedClass>("the walk layer fails on a file named like a class that is none") { ClassFileScan.mainOutputs(main.toUri().toURL()) }
        }
    }

    @Test
    fun `a name is a class file name in any case of the extension and in no other shape`() {
        for (name: String in listOf("A.class", "a/b/A.CLASS", "A.Class", "A.cLASS", ".class", "x.y.z.cLaSs")) {
            assertTrue(ClassFileScan.isClassFileName(name), "$name is a class file name")
        }
        for (name: String in listOf("A.classx", "A.clazz", "class", "A.class.txt", "A.jar", "A.CLAS", "A.kotlin_module", "")) {
            assertFalse(ClassFileScan.isClassFileName(name), "$name is not a class file name")
        }
    }

    private fun bytesOf(type: Class<*>): ByteArray {
        val resource: String = INTERNAL_SEPARATOR + type.name.replace('.', INTERNAL_SEPARATOR_CHAR) + ".class"
        return type.getResourceAsStream(resource)?.use { stream -> stream.readBytes() }
            ?: error("the class file of ${type.name} was not found: the test cannot run (it must not pass silently)")
    }

    /** Writes the class file of [type] below [base] on its package path, with the extension [extension]. */
    private fun put(base: Path, type: Class<*>, extension: String) {
        val segments: List<String> = type.name.split('.')
        val directory: Path = segments.dropLast(1).fold(base) { path: Path, segment: String -> path.resolve(segment) }.createDirectories()
        directory.resolve("${segments.last()}.$extension").writeBytes(bytesOf(type))
    }

    private fun withDirectory(block: (Path) -> Unit) {
        val directory: Path = Files.createTempDirectory("kmptoolkit-classscan-r16")
        try {
            block(directory)
        } finally {
            @OptIn(kotlin.io.path.ExperimentalPathApi::class)
            directory.deleteRecursively()
        }
    }

    private companion object {
        /** The separator of a JVM internal name and of a class loader resource, which is always `/`, never a file-system separator. */
        const val INTERNAL_SEPARATOR: String = "/"
        const val INTERNAL_SEPARATOR_CHAR: Char = '/'
    }
}
