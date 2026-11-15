package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.deleteRecursively
import kotlin.io.path.isDirectory
import kotlin.io.path.writeBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Group sto-desktop, round 11 (breaker A9-m2): the JCA boundary of decisions D-P12 is enforced on the COMPILED classes of the
 * storage module's jvm target, not only on source text (`JcaBoundaryScanTest` stays as the second layer). In every class but `Jca`
 * (exempt by its exact name and by nothing else: no name shape, no `SourceFile`, and no class nested in it, because the compiled
 * boundary has none and a class whose name starts with `Jca$` is flagged for that name), the constant pool must name no `java.security`, `javax.crypto`, `java.util.Base64` or
 * `kotlin.io.encoding` class, hold no string that spells those packages, and mention no such type in a descriptor except
 * `SecureRandom`, the type of the random source that crosses the boundary. Round 12 adds the supertypes through which that source
 * can be driven without naming it (`java.util.Random`, `java.util.random.*`, `kotlin.random.PlatformRandomKt`), by class name and by
 * descriptor (see [ClassFileScan], which also states what the scan cannot see). Baseline zero.
 *
 * Why it cannot be passed by a lexical trick: quoting, back-quotes, spaced names, aliases of aliases, constructor references, a
 * call on an instance obtained from the boundary and the same call after an implicit upcast all compile to a class reference in the pool. The scan needs no tool, no new
 * dependency and no path layout: the classes are found from the code source of `Jca` itself, together with every other jvm main output under the same `classes` tree (found by walking: the `ffm` compilation of source set `jvmFfm` where a branch has one, the javac output of Java sources), and the test FAILS (never passes) when it
 * finds too few classes, no boundary class or more than one (a same-named class of another location would be exempt), no class of the common source set, or a test class (a wrong location).
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

    /** The facts of the compiled main classes of every output location, after the controls that make an empty or wrong location a failure. */
    private fun mainClasses(locations: List<URL>): List<ClassFileScan.Facts> {
        val all: List<ClassFileScan.Facts> = ClassFileScan.classBytes(locations).map(ClassFileScan::parse)
        check(all.size > MIN_CLASSES) { "only ${all.size} classes under $locations: the scan must see the module's compiled main classes" }
        val boundaries: Int = all.count { facts: ClassFileScan.Facts -> facts.name == boundaryName }
        check(boundaries >= 1) { "the boundary class $boundaryName is not under $locations" }
        // The boundary is exempt by its name, in every location. A second class of that name (in the ffm output, say) would be exempt
        // too, and only the jar's duplicate-entry failure would stop it: the scan itself must not depend on that.
        check(boundaries == 1) { "the boundary class $boundaryName is under $locations $boundaries times: a second class of that name would be exempt, so it is a failure" }
        check(all.any { facts: ClassFileScan.Facts -> facts.name == COMMON_MAIN_CLASS }) { "$COMMON_MAIN_CLASS (commonMain) is not under $locations: the common source set is not scanned" }
        check(all.none { facts: ClassFileScan.Facts -> facts.name == internalName(JcaBytecodeScanTest::class.java) }) { "test classes are under $locations: this is not the main output" }
        return all
    }

    @Test
    fun `no compiled class but the boundary touches the JCA`() {
        val all: List<ClassFileScan.Facts> = mainClasses(ClassFileScan.mainOutputs(codeSource(Jca::class.java)))
        val boundary: List<ClassFileScan.Facts> = all.filter(ClassFileScan::isBoundaryClass)
        assertTrue(boundary.isNotEmpty(), "control: the boundary class is in the scanned set")
        assertTrue(boundary.any { facts: ClassFileScan.Facts -> ClassFileScan.violations(facts).isNotEmpty() }, "control: the same scan flags the boundary, so it sees JCA code")
        val offenders: List<String> = all.filterNot(ClassFileScan::isBoundaryClass).flatMap(ClassFileScan::violations)
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
    fun `the supertypes through which the random source can be driven are flagged by class name and by descriptor and look-alikes are not`() {
        val flagged: List<String> = listOf(
            "java/util/Random", "[Ljava/util/Random;", "java/util/random/RandomGenerator", "java/util/random/RandomGenerator\$SplittableGenerator",
            "java/util/random/RandomGeneratorFactory", "kotlin/random/PlatformRandomKt",
        )
        for (name: String in flagged) {
            val facts: ClassFileScan.Facts = ClassFileScan.parse(SyntheticClass.build("a/B") { pool: SyntheticClass.Pool -> pool.classOf(name) })
            assertEquals(1, ClassFileScan.violations(facts).size, "$name must be flagged")
        }
        for (name: String in listOf("java/util/RandomAccess", "java/util/Randomizer", "java/util/randomx/Thing", "kotlin/random/Random", "kotlin/random/Random\$Default", "kotlin/random/PlatformRandomKtx")) {
            val facts: ClassFileScan.Facts = ClassFileScan.parse(SyntheticClass.build("a/B") { pool: SyntheticClass.Pool -> pool.classOf(name) })
            assertEquals(emptyList(), ClassFileScan.violations(facts), "$name must not be flagged")
        }
        fun factsWithUtf8(text: String): ClassFileScan.Facts = ClassFileScan.parse(SyntheticClass.build("a/B") { pool: SyntheticClass.Pool -> pool.utf8(text) })
        assertEquals(1, ClassFileScan.violations(factsWithUtf8("(Ljava/util/Random;)V")).size, "a function that takes a java.util.Random in another module is called through this descriptor")
        assertEquals(1, ClassFileScan.violations(factsWithUtf8("(I)Ljava/util/random/RandomGenerator;")).size)
        assertEquals(emptyList(), ClassFileScan.violations(factsWithUtf8("(Lkotlin/random/Random;)V")), "Kotlin's own Random is no JCA type")
    }

    @Test
    fun `a call on the boundary's random source after an upcast is flagged in each of the three shapes`() {
        for (name: String in listOf("JcaA10UpcastFixtureRandom", "JcaA10UpcastFixtureGenerator", "JcaA10UpcastFixtureKotlin")) {
            val facts: ClassFileScan.Facts = fixtureFacts(name)
            assertTrue(ClassFileScan.violations(facts).isNotEmpty(), "$name drives the random source through a supertype: the owner of its call is flagged")
        }
        val kotlin: ClassFileScan.Facts = fixtureFacts("JcaA10UpcastFixtureKotlin")
        assertTrue(kotlin.classRefs.contains("kotlin/random/PlatformRandomKt"), "control: asKotlinRandom is called on the facade class the scan names")
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
    fun `the boundary is exempt by its exact name only and a class named like one nested in it is flagged`() {
        assertTrue(ClassFileScan.isBoundary(boundaryName), "control: the real boundary")
        assertTrue(!ClassFileScan.isBoundary("$boundaryName\$provider\$1"), "a name is exact: a name that only starts like the boundary is not it")
        assertTrue(!ClassFileScan.isBoundary("io/github/x/desktop/Jca"), "a class Jca of another package")
        assertTrue(!ClassFileScan.isBoundary("${boundaryName}Failure"), "JcaFailure holds no JCA reference and is scanned like every other class")
        fun synthetic(name: String, sourceFile: String?): ClassFileScan.Facts =
            ClassFileScan.parse(SyntheticClass.build(name, sourceFile = sourceFile, withMembers = true) { pool: SyntheticClass.Pool -> pool.classOf("x/Y") })
        assertTrue(ClassFileScan.isBoundaryClass(synthetic(boundaryName, "Jca.kt")), "the boundary")
        assertEquals(emptyList(), ClassFileScan.violations(synthetic(boundaryName, "Jca.kt")), "the boundary's name is no violation of the name rule")
        for (sourceFile: String? in listOf("Jca.kt", "Helper.kt", null)) {
            for (name: String in listOf("$boundaryName\$provider\$1", "$boundaryName\$Helper", "$boundaryName\$B11Facade")) {
                val facts: ClassFileScan.Facts = synthetic(name, sourceFile)
                assertTrue(!ClassFileScan.isBoundaryClass(facts), "$name compiled from $sourceFile is not the boundary: neither the name nor the file name makes a nested class")
                assertTrue(ClassFileScan.isNamedLikeNested(name), "control: $name reads like a class nested in the boundary")
                assertTrue(
                    ClassFileScan.violations(facts).any { line: String -> "named like a class nested in the boundary" in line },
                    "$name compiled from $sourceFile has no JCA reference and must still be flagged for its name",
                )
            }
        }
        assertTrue(!ClassFileScan.isBoundaryClass(synthetic("io/github/x/desktop/Jca\$1", "Jca.kt")), "another package's Jca.kt")
        assertEquals(emptyList(), ClassFileScan.violations(synthetic("io/github/x/desktop/Jca\$1", "Jca.kt")), "another package's class is no class nested in this boundary")
        assertTrue(!ClassFileScan.isBoundaryClass(synthetic("${boundaryName}Failure", "Jca.kt")), "a look-alike name in the boundary's file")
        assertEquals(emptyList(), ClassFileScan.violations(synthetic("${boundaryName}Failure", "Jca.kt")), "JcaFailure is an ordinary class: no `\$`, no name violation")
    }

    @Test
    fun `the source file of a class is read past its interfaces, fields, methods and attributes`() {
        val facts: ClassFileScan.Facts = ClassFileScan.parse(SyntheticClass.build("a/B", sourceFile = "Some.kt", withMembers = true) { pool: SyntheticClass.Pool -> pool.long(1L) })
        assertEquals("Some.kt", facts.sourceFile, "a parser that misses one skipped table reads another attribute's bytes")
        assertEquals(null, ClassFileScan.parse(SyntheticClass.build("a/B", withMembers = true) { pool: SyntheticClass.Pool -> pool.long(1L) }).sourceFile)
        assertEquals(null, ClassFileScan.parse(SyntheticClass.build("a/B") { pool: SyntheticClass.Pool -> pool.long(1L) }).sourceFile)
    }

    @Test
    fun `the compiled boundary is exempt and a fixture that only looks nested is flagged for its name`() {
        val all: List<ClassFileScan.Facts> = ClassFileScan.classBytes(codeSource(Jca::class.java)).map(ClassFileScan::parse)
        val real: ClassFileScan.Facts = all.single { facts: ClassFileScan.Facts -> facts.name == boundaryName }
        assertTrue(ClassFileScan.isBoundaryClass(real))
        assertEquals(
            emptyList(),
            all.map { facts: ClassFileScan.Facts -> facts.name }.filter(ClassFileScan::isNamedLikeNested),
            "the compiled boundary has no class nested in it (Kotlin 2: lambdas are invokedynamic, `provider` is inline): a new one forces a deliberate rule",
        )
        val lookAlike: ClassFileScan.Facts = fixtureFacts("Jca\$Round12NotNested")
        assertTrue(lookAlike.name.startsWith("$boundaryName\$"), "control: its binary name looks like a class nested in the boundary")
        assertTrue(!ClassFileScan.isBoundaryClass(lookAlike), "it is a top-level class compiled from ${lookAlike.sourceFile}")
        assertTrue(ClassFileScan.violations(lookAlike).any { line: String -> "names the class java/security/MessageDigest" in line }, "control: it touches the JCA, so the real-tree scan must not let it through")
        assertTrue(ClassFileScan.violations(lookAlike).any { line: String -> "named like a class nested in the boundary" in line }, "and its name is flagged on its own")
    }

    @Test
    fun `classes forged from a second file called Jca kt are flagged for their names whatever they touch`() {
        val forged: List<ClassFileScan.Facts> = ClassFileScan.classBytes(codeSource(JcaBytecodeScanTest::class.java)).map(ClassFileScan::parse)
            .filter { facts: ClassFileScan.Facts -> ClassFileScan.isNamedLikeNested(facts.name) && facts.sourceFile == "Jca.kt" }
        assertEquals(
            setOf("$boundaryName\$B11Facade", "$boundaryName\$B11Forged"),
            forged.map { facts: ClassFileScan.Facts -> facts.name }.toSet(),
            "control: both forged shapes of the round-11 breaker (a back-quoted class, a @file:JvmName facade) are compiled with the tests",
        )
        for (facts: ClassFileScan.Facts in forged) {
            assertTrue(!ClassFileScan.isBoundaryClass(facts), "${facts.name} is not exempt")
            assertEquals(1, ClassFileScan.violations(facts).count { line: String -> "named like a class nested in the boundary" in line }, "${facts.name} is flagged for its name")
        }
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
            assertFailsWith<IllegalStateException>("an empty location must fail, not pass") { mainClasses(listOf(directory.toUri().toURL())) }
            directory.resolve("A.class").writeBytes(SyntheticClass.build("a/A") { pool: SyntheticClass.Pool -> pool.classOf("x/Y") })
            assertEquals(1, ClassFileScan.classBytes(directory.toUri().toURL()).size, "control: one class listed")
            assertFailsWith<IllegalStateException>("a location with too few classes and no boundary must fail") { mainClasses(listOf(directory.toUri().toURL())) }
            assertFailsWith<IllegalStateException>("a file that is neither a directory nor a jar must fail") {
                ClassFileScan.classBytes(directory.resolve("A.class").toUri().toURL())
            }
        } finally {
            @OptIn(kotlin.io.path.ExperimentalPathApi::class)
            directory.deleteRecursively()
        }
    }

    @Test
    fun `the real classes tree is walked and only jvm outputs that are shipped are listed`() {
        val realMain: Path = Paths.get(codeSource(Jca::class.java).toURI())
        val real: List<URL> = ClassFileScan.mainOutputs(codeSource(Jca::class.java))
        assertEquals(realMain.toUri().toURL(), real.first(), "control: the output the boundary was loaded from is always scanned")
        assertEquals(real.distinct(), real, "no output is listed twice")
        val testOutput: Path = Paths.get(codeSource(JcaBytecodeScanTest::class.java).toURI())
        assertTrue(testOutput.isDirectory() && testOutput != realMain, "control: this test class is compiled into an output of its own ($testOutput)")
        assertTrue(real.none { output: URL -> Paths.get(output.toURI()) == testOutput }, "the test output is not the main code: $real")
        val classes: Path = generateSequence(realMain.parent) { directory: Path -> directory.parent }.first { directory: Path -> directory.fileName?.toString() == "classes" }
        for (output: URL in real) {
            val relative: String = classes.relativize(Paths.get(output.toURI())).toString().lowercase()
            assertTrue("jvm" in relative && "test" !in relative && "check" !in relative, "$output is not a shipped jvm output of the classes tree")
        }
    }

    @Test
    fun `every shipped jvm output under the classes tree is found whatever its depth, name or language and nothing else is`() {
        val root: Path = Files.createTempDirectory("kmptoolkit-classscan")
        try {
            val classes: Path = Files.createDirectories(root.resolve("build").resolve("classes"))
            fun put(output: String, name: String, reference: String = "java/nio/file/Files"): Path {
                // path-hygiene-ignore: PH3 a logical output spec and a JVM internal class name of this test, not native paths
                val file: Path = Paths.get(classes.toString(), *output.split('/').toTypedArray(), *"$name.class".split('/').toTypedArray())
                Files.createDirectories(file.parent)
                file.writeBytes(SyntheticClass.build(name) { pool: SyntheticClass.Pool -> pool.classOf(reference) })
                return classes.resolve(output)
            }
            fun outputs(main: Path): List<Path> = ClassFileScan.mainOutputs(main.toUri().toURL()).map { url: URL -> Paths.get(url.toURI()) }
            val main: Path = put("kotlin/jvm/main", "a/A")
            // Not shipped: tests, the check-only compilation, the Android outputs of the same module.
            put("kotlin/jvm/test", "a/T")
            put("kotlin/jvm/ffmApiCheck", "a/Check")
            put("java/jvmTest", "a/JavaTest")
            put("kotlin/android/main", "a/Android")
            put("kotlin/androidHostTest/main", "a/AndroidTest")
            assertEquals(listOf(main), outputs(main), "test, check-only and Android outputs are not main code")
            // Shipped: the ffm compilation, the javac output of jvmMain (a package of two levels), a source set that does not exist yet
            // (a name and a depth no rule knows) and a class of the default package.
            val ffm: Path = put("kotlin/jvm/ffm", "a/B", "javax/crypto/Cipher")
            val java: Path = put("java/jvmMain", "scratch/deep/JavaUse", "java/security/MessageDigest")
            val future: Path = put("kotlin/jvm/brandNewSet/level", "x/Y/Z", "java/util/Base64")
            val bare: Path = put("kotlin/jvmGen", "Bare", "javax/crypto/Mac")
            val found: List<Path> = outputs(main)
            assertEquals(main, found.first(), "the code source comes first")
            assertEquals(setOf(main, ffm, java, future, bare), found.toSet(), "every shipped jvm output is found by walking, the rest is not")
            assertEquals(found.size, found.toSet().size, "none twice")
            val violations: List<String> = ClassFileScan.classBytes(found.map { path: Path -> path.toUri().toURL() }).map(ClassFileScan::parse).flatMap(ClassFileScan::violations).sorted()
            assertEquals(
                listOf(
                    "Bare names the class javax/crypto/Mac",
                    "a/B names the class javax/crypto/Cipher",
                    "scratch/deep/JavaUse names the class java/security/MessageDigest",
                    "x/Y/Z names the class java/util/Base64",
                ),
                violations,
                "a JCA use in each of the outputs is found",
            )
            // The same sources with the test output as the starting point: the test output itself is what was asked for, so it is listed.
            assertEquals(listOf(classes.resolve("kotlin/jvm/test")), outputs(classes.resolve("kotlin/jvm/test")).take(1), "the code source is listed whatever its name")
        } finally {
            @OptIn(kotlin.io.path.ExperimentalPathApi::class)
            root.deleteRecursively()
        }
    }

    @Test
    fun `a code source that cannot be walked fails the scan and never passes it`() {
        val root: Path = Files.createTempDirectory("kmptoolkit-classscan")
        try {
            val main: Path = Files.createDirectory(root.resolve("main"))
            main.resolve("A.class").writeBytes(SyntheticClass.build("a/A") { pool: SyntheticClass.Pool -> pool.classOf("java/nio/file/Files") })
            assertFailsWith<IllegalStateException>("a directory outside any classes tree: the other outputs cannot be found") { ClassFileScan.mainOutputs(main.toUri().toURL()) }
            assertFailsWith<IllegalStateException>("a location that does not exist must fail, not pass") { ClassFileScan.mainOutputs(root.resolve("absent").toUri().toURL()) }
            val classes: Path = Files.createDirectories(root.resolve("classes").resolve("kotlin").resolve("jvm"))
            val other: Path = Files.createDirectories(classes.resolve("other"))
            other.resolve("O.class").writeBytes(SyntheticClass.build("o/O") { pool: SyntheticClass.Pool -> pool.classOf("java/nio/file/Files") })
            val misplaced: Path = Files.createDirectories(classes.resolve("main").resolve("wrong"))
            misplaced.resolve("A.class").writeBytes(SyntheticClass.build("a/A") { pool: SyntheticClass.Pool -> pool.classOf("javax/crypto/Cipher") })
            assertEquals(
                listOf(other, misplaced),
                ClassFileScan.mainOutputs(other.toUri().toURL()).map { url: URL -> Paths.get(url.toURI()) },
                "a class whose directories do not follow its package is still read: its own directory is taken as an output, never dropped",
            )
            val jar: Path = root.resolve("classes.jar")
            JarOutputStream(Files.newOutputStream(jar)).use { out: JarOutputStream -> out.putNextEntry(JarEntry("a/B.class")); out.write(SyntheticClass.build("a/B") { pool: SyntheticClass.Pool -> pool.classOf("x/Y") }); out.closeEntry() }
            assertEquals(listOf(jar.toUri().toURL()), ClassFileScan.mainOutputs(jar.toUri().toURL()), "a jar carries every class and is scanned as it is")
        } finally {
            @OptIn(kotlin.io.path.ExperimentalPathApi::class)
            root.deleteRecursively()
        }
    }

    @Test
    fun `a second class with the name of the boundary in another output location fails the scan`() {
        val root: Path = Files.createTempDirectory("kmptoolkit-classscan")
        try {
            val main: Path = Files.createDirectory(root.resolve("main"))
            repeat(MIN_CLASSES + 1) { number: Int ->
                main.resolve("C$number.class").writeBytes(SyntheticClass.build("a/C$number") { pool: SyntheticClass.Pool -> pool.classOf("x/Y") })
            }
            main.resolve("Boundary.class").writeBytes(SyntheticClass.build(boundaryName) { pool: SyntheticClass.Pool -> pool.classOf("java/security/MessageDigest") })
            main.resolve("Common.class").writeBytes(SyntheticClass.build(COMMON_MAIN_CLASS) { pool: SyntheticClass.Pool -> pool.classOf("x/Y") })
            assertEquals(MIN_CLASSES + 3, mainClasses(listOf(main.toUri().toURL())).size, "control: one boundary in one location passes the controls")
            val ffm: Path = Files.createDirectory(root.resolve("ffm"))
            ffm.resolve("Boundary.class").writeBytes(SyntheticClass.build(boundaryName) { pool: SyntheticClass.Pool -> pool.classOf("javax/crypto/Cipher") })
            val failure: IllegalStateException = assertFailsWith<IllegalStateException>("a boundary-named class in a second location would be exempt: the scan fails instead") {
                mainClasses(listOf(main.toUri().toURL(), ffm.toUri().toURL()))
            }
            assertTrue("times" in failure.message.orEmpty(), "the failure says why: ${failure.message}")
        } finally {
            @OptIn(kotlin.io.path.ExperimentalPathApi::class)
            root.deleteRecursively()
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

        /**
         * [sourceFile] adds a `SourceFile` attribute; [withMembers] adds an interface, a field and a method that carry attributes
         * (the tables a parser has to skip to reach the attributes of the class).
         */
        fun build(thisName: String, sourceFile: String? = null, withMembers: Boolean = false, fill: (Pool) -> Unit): ByteArray {
            val pool = ByteArrayOutputStream()
            val builder = Pool(DataOutputStream(pool))
            val thisClass: Int = builder.classOf(thisName)
            fill(builder)
            val memberName: Int = builder.utf8("member")
            val memberAttribute: Int = builder.utf8("Whatever")
            val sourceFileName: Int = builder.utf8("SourceFile")
            val sourceFileText: Int = sourceFile?.let { text: String -> builder.utf8(text) } ?: 0
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
                if (withMembers) {
                    writeShort(1)
                    writeShort(thisClass)
                    // one field, then one method: access, name, descriptor, then an attribute of five bytes and one of none
                    repeat(2) {
                        writeShort(1)
                        writeShort(1)
                        writeShort(memberName)
                        writeShort(memberName)
                        writeShort(2)
                        writeShort(memberAttribute)
                        writeInt(5)
                        write(ByteArray(5) { 0x7f })
                        writeShort(memberAttribute)
                        writeInt(0)
                    }
                } else {
                    writeShort(0)
                    writeShort(0)
                    writeShort(0)
                }
                if (sourceFile == null) {
                    writeShort(0)
                } else {
                    writeShort(2)
                    writeShort(memberAttribute)
                    writeInt(3)
                    write(ByteArray(3))
                    writeShort(sourceFileName)
                    writeInt(2)
                    writeShort(sourceFileText)
                }
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
