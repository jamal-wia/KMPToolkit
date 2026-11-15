package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Group sto-desktop, round 11 (breaker A9-m2): the JCA boundary of decisions D-P12 is enforced on the COMPILED classes of the
 * storage module's jvm target, not only on source text (`JcaBoundaryScanTest` stays as the second layer). In every class but `Jca`
 * (and its nested classes), the constant pool must name no `java.security`, `javax.crypto`, `java.util.Base64` or
 * `kotlin.io.encoding` class, hold no string that spells those packages, and mention no such type in a descriptor except
 * `SecureRandom`, the type of the random source that crosses the boundary (see [ClassFileScan]). Baseline zero.
 *
 * Why it cannot be passed by a lexical trick: quoting, back-quotes, spaced names, aliases of aliases, constructor references and a
 * call on an instance obtained from the boundary all compile to a class reference in the pool. The scan needs no tool, no new
 * dependency and no path layout: the classes are found from the code source of `Jca` itself, and the test FAILS (never passes) when it
 * finds too few classes, no boundary class, no class of the common source set, or a test class (a wrong location).
 *
 * Controls and mutations: the real tree is checked for size and for its landmarks; the boundary itself is flagged by the same scan
 * (so it sees JCA code); fixtures compiled with the tests (`JcaScanFixtures.kt`) are flagged or left alone as named; class bytes built
 * by hand prove the parser (two-slot constants, array names, unknown tags, truncation); an empty directory and a jar with one
 * class are listed or refused as specified. A `SecureRandom()` added to any jvmMain class turns the real-tree test red.
 */
class JcaBytecodeScanTest {

    private val boundaryName: String = internalName(Jca::class.java)

    /** The class's own name without its package: a JVM internal class name, not a file path. */
    private fun simpleName(facts: ClassFileScan.Facts): String =
        // path-hygiene-ignore: PH3 a JVM internal class name, not a file path
        facts.name.substringAfterLast('/')

    private fun internalName(type: Class<*>): String = type.name.replace('.', '/')

    private fun codeSource(type: Class<*>): URL =
        type.protectionDomain?.codeSource?.location ?: error("no code source for ${type.name}: the scan cannot find the compiled classes (it must not pass silently)")

    /** The facts of the compiled main classes, after the controls that make an empty or wrong location a failure. */
    private fun mainClasses(location: URL): List<ClassFileScan.Facts> {
        val all: List<ClassFileScan.Facts> = ClassFileScan.classBytes(location).map(ClassFileScan::parse)
        check(all.size > MIN_CLASSES) { "only ${all.size} classes under $location: the scan must see the module's compiled main classes" }
        check(all.any { facts: ClassFileScan.Facts -> facts.name == boundaryName }) { "the boundary class $boundaryName is not under $location" }
        check(all.any { facts: ClassFileScan.Facts -> facts.name == COMMON_MAIN_CLASS }) { "$COMMON_MAIN_CLASS (commonMain) is not under $location: the common source set is not scanned" }
        check(all.none { facts: ClassFileScan.Facts -> facts.name == internalName(JcaBytecodeScanTest::class.java) }) { "test classes are under $location: this is not the main output" }
        return all
    }

    @Test
    fun `no compiled class but the boundary touches the JCA`() {
        val all: List<ClassFileScan.Facts> = mainClasses(codeSource(Jca::class.java))
        val boundary: List<ClassFileScan.Facts> = all.filter { facts: ClassFileScan.Facts -> ClassFileScan.isBoundary(facts.name) }
        assertTrue(boundary.isNotEmpty(), "control: the boundary class is in the scanned set")
        assertTrue(boundary.any { facts: ClassFileScan.Facts -> ClassFileScan.violations(facts).isNotEmpty() }, "control: the same scan flags the boundary, so it sees JCA code")
        val offenders: List<String> = all.filterNot { facts: ClassFileScan.Facts -> ClassFileScan.isBoundary(facts.name) }.flatMap(ClassFileScan::violations)
        assertEquals(emptyList(), offenders, "a compiled class that touches the JCA outside Jca: route it through the boundary (D-P12)")
    }

    @Test
    fun `the scan flags every way of reaching the JCA that a fixture takes and leaves the clean fixtures alone`() {
        val fixtures: List<ClassFileScan.Facts> = ClassFileScan.classBytes(codeSource(JcaBytecodeScanTest::class.java)).map(ClassFileScan::parse)
            .filter { facts: ClassFileScan.Facts -> simpleName(facts).startsWith("JcaScanFixture") }
        fun violationsOf(prefix: String): List<String> =
            fixtures.filter { facts: ClassFileScan.Facts -> simpleName(facts).startsWith(prefix) }.flatMap(ClassFileScan::violations)
        val flagged: List<String> = listOf(
            "JcaScanFixtureFlaggedDirect", "JcaScanFixtureFlaggedInstanceCall", "JcaScanFixtureFlaggedConstructorReference",
            "JcaScanFixtureFlaggedSecondAlias", "JcaScanFixtureFlaggedSpaced", "JcaScanFixtureFlaggedJdkBase64",
            "JcaScanFixtureFlaggedKotlinBase64", "JcaScanFixtureFlaggedReflection",
        )
        for (fixture: String in flagged) {
            assertTrue(fixtures.any { facts: ClassFileScan.Facts -> simpleName(facts).startsWith(fixture) }, "control: $fixture is compiled with the tests")
            assertTrue(violationsOf(fixture).isNotEmpty(), "$fixture reaches the JCA and the scan of its class files did not flag it")
        }
        for (fixture: String in listOf("JcaScanFixtureCleanBoundaryUse", "JcaScanFixtureCleanOtherJdk")) {
            assertTrue(fixtures.any { facts: ClassFileScan.Facts -> simpleName(facts).startsWith(fixture) }, "control: $fixture is compiled with the tests")
            assertEquals(emptyList(), violationsOf(fixture), "$fixture does not touch the JCA and must not be flagged")
        }
    }

    @Test
    fun `a call on an instance of the boundary's random source is flagged by the owner of the call`() {
        val facts: ClassFileScan.Facts = fixtureFacts("JcaScanFixtureFlaggedInstanceCall")
        assertTrue(facts.classRefs.contains("java/security/SecureRandom"), "control: the call's owner is the JDK type (the source never names it)")
        assertTrue(ClassFileScan.violations(facts).any { line: String -> "names the class java/security/SecureRandom" in line }, "the owner of the call is a class reference")
    }

    private fun fixtureFacts(name: String): ClassFileScan.Facts =
        ClassFileScan.classBytes(codeSource(JcaBytecodeScanTest::class.java)).map(ClassFileScan::parse)
            .single { facts: ClassFileScan.Facts -> simpleName(facts) == name }

    // ---- the parser, on class files built by hand ----

    @Test
    fun `a class entry that follows a long constant is read at the right index`() {
        val bytes: ByteArray = SyntheticClass.build("a/B") { pool: SyntheticClass.Pool ->
            pool.long(7L)
            pool.double(1.5)
            pool.classOf("java/security/MessageDigest")
        }
        val facts: ClassFileScan.Facts = ClassFileScan.parse(bytes)
        assertEquals("a/B", facts.name)
        assertEquals(setOf("a/B", "java/security/MessageDigest"), facts.classRefs, "a parser that forgets a long takes two slots misreads every later index")
        assertEquals(listOf("a/B names the class java/security/MessageDigest"), ClassFileScan.violations(facts))
    }

    @Test
    fun `array names, nested classes and the rest of the forbidden prefixes are flagged and look-alikes are not`() {
        val flagged: List<String> = listOf(
            "[Ljavax/crypto/Cipher;", "[[Ljava/security/KeyStore;", "javax/crypto/spec/SecretKeySpec", "java/util/Base64", "java/util/Base64\$Encoder",
            "kotlin/io/encoding/Base64", "kotlin/io/encoding/Base64\$Default",
        )
        for (name: String in flagged) {
            val facts: ClassFileScan.Facts = ClassFileScan.parse(SyntheticClass.build("a/B") { pool: SyntheticClass.Pool -> pool.classOf(name) })
            assertEquals(1, ClassFileScan.violations(facts).size, "$name must be flagged")
        }
        for (name: String in listOf("java/nio/file/Files", "java/util/Base64Foo", "java/util/Base", "kotlin/text/Charsets", "java/securityx/Thing", "[Ljava/lang/String;")) {
            val facts: ClassFileScan.Facts = ClassFileScan.parse(SyntheticClass.build("a/B") { pool: SyntheticClass.Pool -> pool.classOf(name) })
            assertEquals(emptyList(), ClassFileScan.violations(facts), "$name must not be flagged")
        }
    }

    @Test
    fun `a descriptor may mention the random source and nothing else of the JCA and a string may not spell a package`() {
        fun factsWithUtf8(text: String): ClassFileScan.Facts = ClassFileScan.parse(SyntheticClass.build("a/B") { pool: SyntheticClass.Pool -> pool.utf8(text) })
        assertEquals(emptyList(), ClassFileScan.violations(factsWithUtf8("(Ljava/security/SecureRandom;I)[B")), "the random source in a signature")
        assertEquals(1, ClassFileScan.violations(factsWithUtf8("(Ljavax/crypto/Cipher;)V")).size, "another JCA type in a signature")
        assertEquals(1, ClassFileScan.violations(factsWithUtf8("(I[Ljava/security/MessageDigest;)V")).size, "an array of another JCA type")
        val withString: ClassFileScan.Facts = ClassFileScan.parse(SyntheticClass.build("a/B") { pool: SyntheticClass.Pool -> pool.string("java.security.SecureRandom") })
        assertEquals(1, ClassFileScan.violations(withString).size, "a class name written out as a string")
        val plainString: ClassFileScan.Facts = ClassFileScan.parse(SyntheticClass.build("a/B") { pool: SyntheticClass.Pool -> pool.string("a harmless text") })
        assertEquals(emptyList(), ClassFileScan.violations(plainString), "an ordinary string")
    }

    @Test
    fun `the boundary and its nested classes are recognised and nothing else is`() {
        assertTrue(ClassFileScan.isBoundary("io/github/x/desktop/Jca"))
        assertTrue(ClassFileScan.isBoundary("io/github/x/desktop/Jca\$provider\$1"))
        assertTrue(!ClassFileScan.isBoundary("io/github/x/desktop/JcaFailure"), "JcaFailure holds no JCA reference and is scanned like every other class")
        assertTrue(!ClassFileScan.isBoundary("io/github/x/desktop/JcaBoundary"))
        assertTrue(!ClassFileScan.isBoundary("io/github/x/other/Jca"))
    }

    @Test
    fun `bad magic, an unknown tag and a truncated file are refused`() {
        val good: ByteArray = SyntheticClass.build("a/B") { pool: SyntheticClass.Pool -> pool.classOf("x/Y") }
        assertFailsWith<ClassFileScan.MalformedClass> { ClassFileScan.parse(good.copyOf().also { bytes: ByteArray -> bytes[0] = 0 }) }
        assertFailsWith<ClassFileScan.MalformedClass> { ClassFileScan.parse(SyntheticClass.build("a/B") { pool: SyntheticClass.Pool -> pool.raw(2) }) }
        assertFailsWith<ClassFileScan.MalformedClass> { ClassFileScan.parse(good.copyOf(good.size - TAIL_AFTER_POOL + 2)) }  // cut inside the header that follows the pool
        assertFailsWith<ClassFileScan.MalformedClass> { ClassFileScan.parse(good.copyOf(12)) }
        assertFailsWith<ClassFileScan.MalformedClass> { ClassFileScan.parse(ByteArray(0)) }
    }

    // ---- locating the classes: a scan that finds nothing is a failure ----

    @Test
    fun `an empty directory, a directory without the boundary and a missing location fail the scan`() {
        val directory: Path = Files.createTempDirectory("kmptoolkit-classscan")
        try {
            assertEquals(0, ClassFileScan.classBytes(directory.toUri().toURL()).size, "control: nothing listed")
            assertFailsWith<IllegalStateException>("an empty location must fail, not pass") { mainClasses(directory.toUri().toURL()) }
            directory.resolve("A.class").writeBytes(SyntheticClass.build("a/A") { pool: SyntheticClass.Pool -> pool.classOf("x/Y") })
            assertEquals(1, ClassFileScan.classBytes(directory.toUri().toURL()).size, "control: one class listed")
            assertFailsWith<IllegalStateException>("a location with too few classes and no boundary must fail") { mainClasses(directory.toUri().toURL()) }
            assertFailsWith<IllegalStateException>("a file that is neither a directory nor a jar must fail") {
                ClassFileScan.classBytes(directory.resolve("A.class").toUri().toURL())
            }
        } finally {
            @OptIn(kotlin.io.path.ExperimentalPathApi::class)
            directory.deleteRecursively()
        }
    }

    @Test
    fun `the classes of a jar are listed and parsed like those of a directory`() {
        val directory: Path = Files.createTempDirectory("kmptoolkit-classscan")
        try {
            val jar: Path = directory.resolve("classes.jar")
            JarOutputStream(Files.newOutputStream(jar)).use { out: JarOutputStream ->
                out.putNextEntry(JarEntry("a/B.class"))
                out.write(SyntheticClass.build("a/B") { pool: SyntheticClass.Pool -> pool.classOf("javax/crypto/Cipher") })
                out.closeEntry()
                out.putNextEntry(JarEntry("a/readme.txt"))
                out.write("not a class".toByteArray(Charsets.UTF_8))
                out.closeEntry()
            }
            val listed: List<ByteArray> = ClassFileScan.classBytes(jar.toUri().toURL())
            assertEquals(1, listed.size, "only the class entry is listed")
            assertEquals(listOf("a/B names the class javax/crypto/Cipher"), ClassFileScan.violations(ClassFileScan.parse(listed.single())))
        } finally {
            @OptIn(kotlin.io.path.ExperimentalPathApi::class)
            directory.deleteRecursively()
        }
    }

    /** Class files built by hand: a header, a pool the test fills, and nothing else (no fields, methods or attributes). */
    private object SyntheticClass {
        class Pool(private val data: DataOutputStream) {
            var next: Int = 1
                private set

            fun utf8(text: String): Int {
                data.writeByte(1)
                data.writeUTF(text)
                return next++
            }

            fun long(value: Long): Int {
                data.writeByte(5)
                data.writeLong(value)
                return next.also { next += 2 }
            }

            fun double(value: Double): Int {
                data.writeByte(6)
                data.writeDouble(value)
                return next.also { next += 2 }
            }

            fun classOf(name: String): Int {
                val nameIndex: Int = utf8(name)
                data.writeByte(7)
                data.writeShort(nameIndex)
                return next++
            }

            fun string(text: String): Int {
                val textIndex: Int = utf8(text)
                data.writeByte(8)
                data.writeShort(textIndex)
                return next++
            }

            /** An entry with a tag the format does not define. */
            fun raw(tag: Int): Int {
                data.writeByte(tag)
                return next++
            }
        }

        fun build(thisName: String, fill: (Pool) -> Unit): ByteArray {
            val pool = ByteArrayOutputStream()
            val builder = Pool(DataOutputStream(pool))
            val thisClass: Int = builder.classOf(thisName)
            fill(builder)
            val out = ByteArrayOutputStream()
            DataOutputStream(out).apply {
                writeInt(0xCAFEBABE.toInt())
                writeShort(0)
                writeShort(65)
                writeShort(builder.next)
                write(pool.toByteArray())
                writeShort(0x21)
                writeShort(thisClass)
                writeShort(0)
                writeShort(0)
                writeShort(0)
                writeShort(0)
                writeShort(0)
            }
            return out.toByteArray()
        }
    }

    private companion object {
        /** Far below the real count (hundreds); far above what a wrong location holds. */
        const val MIN_CLASSES: Int = 100

        /** The bytes after the pool: access flags, this, super and four counts, two bytes each. */
        const val TAIL_AFTER_POOL: Int = 14

        /** A class of the common source set, which compiles into the same jvm classes. */
        const val COMMON_MAIN_CLASS: String = "io/github/jamal_wia/kmptoolkit/storage/KeyValueStorage"
    }
}
