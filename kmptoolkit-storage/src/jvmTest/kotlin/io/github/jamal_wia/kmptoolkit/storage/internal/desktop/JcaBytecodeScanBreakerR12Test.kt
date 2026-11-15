package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Group sto-desktop, breaker round 12 (decisions D-23), attack on the round-13 texts and on [ClassFileScan.mainOutputs]: D-P12 says
 * the desktop code is "the classes compiled from commonMain and jvmMain into the jvm target" and that the primary check "scans the
 * constant pools of every compiled output directory of that code"; `mainOutputs` promises "every compiled-output location of the
 * module's jvm main code that exists"; the `JcaBytecodeScanTest` KDoc says "a `SecureRandom()` added to any jvmMain class turns the
 * real-tree test red". The only Java blind spot the texts list is Java sources of `jvmFfm`.
 *
 * The attack: since Kotlin 2.1.20 a `jvm()` target compiles Java sources without `withJava()`. With Kotlin 2.4.21 and Gradle 9.8.2
 * (this build), a file `src/jvmMain/java/<package>/X.java` is compiled by javac into `build/classes/java/jvmMain` and packed into the
 * jvm jar, beside `build/classes/kotlin/jvm/main`, which is the code source of `Jca` and the only directory (with its `ffm` sibling)
 * that `mainOutputs` lists. Observed on this tip with a throwaway Java class that calls `MessageDigest` (never committed): the class is
 * in the jar and both scans stay green for it. This test rebuilds that layout in a directory it creates and deletes (the real tree has
 * no Java source today, so the real directory does not exist): a class with a JCA reference in the javac output must be among what the
 * scan reads.
 *
 * Controls: the code source of `Jca` really ends in `classes/kotlin/jvm/main` (so the sibling layout below is the real one); the class
 * placed in the javac output is flagged when its directory is read directly. Mutation by reading: `mainOutputs` adding the javac output
 * of `jvmMain` (`build/classes/java/jvmMain`, found from the code source like the `ffm` sibling) turns this test green; removing the
 * `ffm` sibling from `mainOutputs` does not affect it.
 */
class JcaBytecodeScanBreakerR12Test {

    private fun codeSource(type: Class<*>): URL =
        type.protectionDomain?.codeSource?.location ?: error("no code source for ${type.name}: the scan cannot find the compiled classes")

    @Test
    fun `a class that javac compiled from jvmMain java sources into the jvm jar is read by the scan`() {
        val realMain: Path = Paths.get(codeSource(Jca::class.java).toURI())
        val realTail: List<String> = (realMain.nameCount - 4 until realMain.nameCount).map { index: Int -> realMain.getName(index).toString() }
        assertEquals(listOf("classes", "kotlin", "jvm", "main"), realTail, "control: Jca is loaded from Gradle's Kotlin output of the jvm main compilation ($realMain)")
        val root: Path = Files.createTempDirectory("kmptoolkit-classscan-r12")
        try {
            val classes: Path = Files.createDirectories(root.resolve("build").resolve("classes"))
            val kotlinMain: Path = Files.createDirectories(classes.resolve("kotlin").resolve("jvm").resolve("main"))
            kotlinMain.resolve("A.class").writeBytes(classFile("scratch/A", "java/nio/file/Files"))
            val javaMain: Path = Files.createDirectories(classes.resolve("java").resolve("jvmMain").resolve("scratch"))
            javaMain.resolve("JavaUse.class").writeBytes(classFile("scratch/JavaUse", "java/security/MessageDigest"))
            val direct: List<String> = ClassFileScan.classBytes(classes.resolve("java").resolve("jvmMain").toUri().toURL())
                .map(ClassFileScan::parse).flatMap(ClassFileScan::violations)
            assertEquals(listOf("scratch/JavaUse names the class java/security/MessageDigest"), direct, "control: the javac class is flagged when its directory is read")
            val scanned: List<String> = ClassFileScan.classBytes(ClassFileScan.mainOutputs(kotlinMain.toUri().toURL()))
                .map(ClassFileScan::parse).flatMap(ClassFileScan::violations)
            assertTrue(
                scanned.contains("scratch/JavaUse names the class java/security/MessageDigest"),
                "a Java class of src/jvmMain/java is compiled into build/classes/java/jvmMain and shipped in the jvm jar, but the scan reads only " +
                    "${ClassFileScan.mainOutputs(kotlinMain.toUri().toURL())}: its JCA use is invisible (found: $scanned)",
            )
        } finally {
            @OptIn(kotlin.io.path.ExperimentalPathApi::class)
            root.deleteRecursively()
        }
    }

    /** A minimal class file: [thisName] with one `CONSTANT_Class` reference to [reference], no members, no attributes. */
    private fun classFile(thisName: String, reference: String): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(0xCAFEBABE.toInt())
            writeShort(0)
            writeShort(CLASS_VERSION)
            writeShort(5)
            writeByte(TAG_UTF8)
            writeUTF(thisName)
            writeByte(TAG_CLASS)
            writeShort(1)
            writeByte(TAG_UTF8)
            writeUTF(reference)
            writeByte(TAG_CLASS)
            writeShort(3)
            writeShort(ACC_PUBLIC_SUPER)
            writeShort(2)
            repeat(5) { writeShort(0) }
        }
        return out.toByteArray()
    }

    private companion object {
        const val CLASS_VERSION: Int = 65
        const val TAG_UTF8: Int = 1
        const val TAG_CLASS: Int = 7
        const val ACC_PUBLIC_SUPER: Int = 0x21
    }
}
