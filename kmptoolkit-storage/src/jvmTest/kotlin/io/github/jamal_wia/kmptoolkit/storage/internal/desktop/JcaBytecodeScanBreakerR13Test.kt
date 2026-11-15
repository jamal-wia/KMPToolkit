package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume

/**
 * Group sto-desktop, breaker round 13 (decisions D-23), attack on the DISCOVERY of [ClassFileScan.mainOutputs] after round 14 replaced
 * the list of known output directories by a walk of Gradle's `classes` tree. The contract (D-P12, the `mainOutputs` KDoc): the
 * desktop code is every class the jvm jar ships, found by walking, so "a new source set or language cannot hide", and the scan
 * "fails, and never passes" when it cannot find what it must read. Each test builds a Gradle-shaped tree in a directory it creates
 * and deletes, places one class that touches the JCA where the jvm jar takes classes from (or next to such a place), and accepts
 * either outcome the contract allows: the use is FOUND, or the scan FAILS. Only a silent pass is red.
 *
 * - Processed resources: the jvm jar packs `main.output.allOutputs`, which is the class directories AND
 *   `build/processedResources/jvm/main` (observed on this tip: a `.class` placed in `src/jvmMain/resources` is in
 *   `kmptoolkit-storage-jvm-*.jar` and both scans stay green; on task/sto-ffm `jvmJar` adds `ffm.output.allOutputs`, the `ffm`
 *   resources included). That directory is not under `classes`, so the walk never sees it.
 * - A class file above an output root: the walk takes the root of a directory's classes from the first class it meets and then drops
 *   every root nested in another one. A class file whose root is an ANCESTOR of a real output (a default-package class directly in
 *   `classes`, or in `classes/java`) makes the real output a "nested" root, so it is dropped, and the ancestor is then classified by
 *   its own path (no `jvm` segment), so nothing below it is read.
 * - A symlinked build directory (a build moved to another disk): the control that the walk works through a link in the PATH above
 *   `classes`, which it must.
 *
 * Mutation by reading: `mainOutputs` also walking `build/processedResources/<jvm...>` (or refusing a class file there) turns the
 * first test green; attributing every class file to its OWN root (no "nested root is dropped" rule, no `SKIP_SIBLINGS` across roots)
 * turns the second green; scanning the entries of the built jvm jar (`kmptoolkit.test.targetJar`, already handed to `jvmTest` on
 * task/sto-ffm) instead of any discovery turns both green.
 */
class JcaBytecodeScanBreakerR13Test {

    private val use: String = "java/security/MessageDigest"

    @Test
    fun `a class file that the jvm jar packs from the processed resources is read by the scan or the scan fails`() {
        withTree("kmptoolkit-classscan-r13-res") { root: Path ->
            val build: Path = root.resolve("build")
            val main: Path = put(build, listOf("classes", "kotlin", "jvm", "main"), "scratch/A", "java/nio/file/Files")
            val resources: Path = put(build, listOf("processedResources", "jvm", "main"), "scratch/ResourceUse", use)
            assertEquals(listOf("scratch/ResourceUse names the class $use"), violationsIn(resources), "control: the class is flagged when its directory is read")
            val outcome: Result<List<String>> = scanned(main)
            assertTrue(
                outcome.isFailure || outcome.getOrThrow().contains("scratch/ResourceUse names the class $use"),
                "a class file under build/processedResources/jvm/main is packed into the jvm jar (main.output.allOutputs), but the scan " +
                    "read ${ClassFileScan.mainOutputs(main.toUri().toURL())} and passed: its JCA use is invisible (found: $outcome)",
            )
        }
    }

    @Test
    fun `a class file above an output root never hides that output`() {
        for (strayAt: List<String> in listOf(listOf("classes"), listOf("classes", "java"))) {
            withTree("kmptoolkit-classscan-r13-stray") { root: Path ->
                val build: Path = root.resolve("build")
                val main: Path = put(build, listOf("classes", "kotlin", "jvm", "main"), "scratch/A", "java/nio/file/Files")
                val javac: Path = put(build, listOf("classes", "java", "jvmMain"), "scratch/JavaUse", use)
                assertEquals(listOf("scratch/JavaUse names the class $use"), violationsIn(javac), "control: the javac class is flagged when its directory is read")
                val clean: Result<List<String>> = scanned(main)
                assertTrue(clean.getOrThrow().contains("scratch/JavaUse names the class $use"), "control: without the stray class the walk finds the javac output ($clean)")
                put(build, strayAt, "Stray", "java/nio/file/Files")
                val outcome: Result<List<String>> = scanned(main)
                assertTrue(
                    outcome.isFailure || outcome.getOrThrow().contains("scratch/JavaUse names the class $use"),
                    "one default-package class file in ${strayAt.joinToString("/")} makes classes/java/jvmMain a nested root that is dropped, " +
                        "and the scan passes: read ${ClassFileScan.mainOutputs(main.toUri().toURL())}, found $outcome",
                )
            }
        }
    }

    @Test
    fun `a build directory reached through a symbolic link is walked like a real one`() {
        withTree("kmptoolkit-classscan-r13-link") { root: Path ->
            val realBuild: Path = Files.createDirectories(root.resolve("disk").resolve("build"))
            put(realBuild, listOf("classes", "kotlin", "jvm", "main"), "scratch/A", "java/nio/file/Files")
            put(realBuild, listOf("classes", "java", "jvmMain"), "scratch/JavaUse", use)
            val project: Path = Files.createDirectories(root.resolve("project"))
            val linked: Path = try {
                Files.createSymbolicLink(project.resolve("build"), realBuild)
            } catch (refused: IOException) {
                Assume.assumeNoException("this file system or user cannot create a symbolic link", refused)
                return@withTree
            } catch (refused: UnsupportedOperationException) {
                Assume.assumeNoException("this file system cannot create a symbolic link", refused)
                return@withTree
            }
            val main: Path = linked.resolve("classes").resolve("kotlin").resolve("jvm").resolve("main")
            val outcome: Result<List<String>> = scanned(main)
            assertTrue(
                outcome.getOrThrow().contains("scratch/JavaUse names the class $use"),
                "the code source is spelled through a linked build directory: the walk must still find the javac output ($outcome)",
            )
        }
    }

    private fun scanned(main: Path): Result<List<String>> = runCatching {
        ClassFileScan.classBytes(ClassFileScan.mainOutputs(main.toUri().toURL())).map(ClassFileScan::parse).flatMap(ClassFileScan::violations)
    }

    private fun violationsIn(directory: Path): List<String> =
        ClassFileScan.classBytes(directory.toUri().toURL()).map(ClassFileScan::parse).flatMap(ClassFileScan::violations)

    /** Writes the class [internalName] under [base]/[output] at the path its package names; returns the output directory. */
    private fun put(base: Path, output: List<String>, internalName: String, reference: String): Path {
        val outputDirectory: Path = output.fold(base) { directory: Path, segment: String -> directory.resolve(segment) }
        // path-hygiene-ignore: PH3 a JVM internal class name, not a file path
        val segments: List<String> = internalName.split('/')
        val directory: Path = Files.createDirectories(segments.dropLast(1).fold(outputDirectory) { parent: Path, segment: String -> parent.resolve(segment) })
        directory.resolve(segments.last() + ".class").writeBytes(classFile(internalName, reference))
        return outputDirectory
    }

    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    private fun withTree(prefix: String, body: (Path) -> Unit) {
        val root: Path = Files.createTempDirectory(prefix)
        try {
            body(root)
        } finally {
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
