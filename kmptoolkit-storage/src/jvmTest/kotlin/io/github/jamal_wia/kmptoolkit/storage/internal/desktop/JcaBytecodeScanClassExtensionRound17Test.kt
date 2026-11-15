package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Group sto-desktop, round 17 (observation O-1 of breaker round 15): a file system that folds case folds more than ASCII. On APFS
 * `x/Hidden.claſs` (U+017F, the long s) is the file `x/Hidden.class`, and a class loader over such a directory loads it, while
 * `lowercase()` keeps the long s. [ClassFileScan.isClassFileName] folds each of the last six characters one to one (upper case, then
 * lower case), so the long s is an `s`; `ß`, whose upper case is two characters, is not folded (`x.claß` is no class).
 *
 * Mutation by reading: `name.lowercase().endsWith(".class")` turns the name table and the jar read red; a fold through
 * `uppercase().lowercase()` of the whole name turns the `ß` row red.
 */
class JcaBytecodeScanClassExtensionRound17Test {

    @Test
    fun `a name whose extension folds to class under Unicode case folding is a class file name and a look-alike is not`() {
        for (name: String in listOf("x/Hidden.claſs", "x/Hidden.CLAſS", "x/Hidden.claſſ", "x/Hidden.CLASS", "x/Hidden.cLaSs")) {
            assertTrue(ClassFileScan.isClassFileName(name), "$name is a class file name (any case, the long s included)")
        }
        // Not a case of the extension: `ß` has no one-character upper case, a dotless i and a Kelvin sign are not in "class".
        for (name: String in listOf("x/Hidden.claß", "x/Hidden.claßs", "x/Hidden.clası", "x/Hidden.clazz", "x/Hidden.ſclass", "claſs")) {
            assertFalse(ClassFileScan.isClassFileName(name), "$name is not a class file name")
        }
    }

    @Test
    fun `an entry named with the long s is read by the jar read and fails the parse loudly when it is no class`() {
        val directory: Path = Files.createTempDirectory("kmptoolkit-classscan-r17")
        try {
            val jar: Path = directory.resolve("fake.jar")
            JarOutputStream(Files.newOutputStream(jar)).use { out: JarOutputStream ->
                out.putNextEntry(JarEntry("x/Hidden.claſs"))
                out.write("this is not a class file".toByteArray())
                out.closeEntry()
            }
            val read: List<ByteArray> = ClassFileScan.shippedClassBytes(jar)
            assertEquals(1, read.size, "the jar read returns the entry whose extension is a long-s spelling of class")
            assertFailsWith<ClassFileScan.MalformedClass>("a body that is no class file fails the parse loudly") { read.forEach(ClassFileScan::parse) }
        } finally {
            @OptIn(kotlin.io.path.ExperimentalPathApi::class)
            directory.deleteRecursively()
        }
    }
}
