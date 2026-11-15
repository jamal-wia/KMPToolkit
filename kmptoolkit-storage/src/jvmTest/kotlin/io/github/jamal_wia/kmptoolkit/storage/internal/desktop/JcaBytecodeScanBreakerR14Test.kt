package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.URI
import java.nio.file.FileSystem
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.io.path.deleteRecursively
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Group sto-desktop, breaker round 14 (decisions D-23), attack on the JAR read that round 15 made the PRIMARY bytecode scan
 * ([ClassFileScan.shippedClassBytes]) and, once more, on the class-file parser both layers share. The contract (D-P12, the
 * [ClassFileScan] KDoc): the scan reads "EVERY `.class` entry of that one file, whatever the entry's path", it fails and never passes
 * when a class could be invisible to it (a nested archive is refused "instead of counted as scanned"), and "only the `SecureRandom`
 * type ... may appear in other classes' signatures": a descriptor that mentions a forbidden type is a violation.
 *
 * - The class extension in another case (`x/Hidden.CLASS`): Gradle packs such a file from the resources verbatim, the read skips it
 *   because it compares `.class` case-sensitively (while the nested-archive rule compares `.jar`/`.JAR` case-insensitively), and
 *   it is a class a JVM loads: observed on macOS (APFS, case-insensitive), a `URLClassLoader` over a directory that holds
 *   `x/Hidden.CLASS` loads `x.Hidden` and runs its `MessageDigest` call; the same holds on Windows (NTFS). The jvm jar is unpacked to
 *   such a directory by every exploded deployment and by `jvmTest` itself (it runs from the class and resource directories). The
 *   test accepts either fix: the entry is READ (its use is found) or the scan FAILS.
 * - Descriptors the scan cannot parse: [ClassFileScan.violations] reads a `Utf8` entry as a descriptor only when it holds `(` or is
 *   `L...;`, and takes the type of `L...;` up to the first `;`. An ARRAY field descriptor (`[Ljava/util/Random;`) is neither, a
 *   generic `Signature` (`Ljava/util/List<Ljavax/crypto/Cipher;>;`) yields the bogus type `java/util/List<Ljavax/crypto/Cipher`,
 *   and a class signature with a bounded type parameter (`<T:Ljava/util/Random;>Ljava/lang/Object;`) starts with `<`. A Kotlin class
 *   is flagged all the same, by luck: its `kotlin.Metadata` string table names each type again as `Lname;` (pinned green by the
 *   Kotlin fixtures below). A JAVA class has no such table, and javac output of `src/jvmMain/java` ships in the jvm jar (round 12):
 *   the pools javac 21 writes for those declarations (observed with `javap -v`) name the type nowhere else, so the scan passes.
 * - The parser against every constant kind (long and double taking two slots, modified UTF-8 with a supplementary character and an
 *   encoded NUL, MethodHandle, MethodType, Dynamic, InvokeDynamic, Module, Package) placed BEFORE the entries that must be flagged, a
 *   malformed `Utf8` (standard four-byte UTF-8, which modified UTF-8 forbids) that must fail and never pass, and every class of the
 *   running JDK's `java.base` (module-info included), each of which must parse to its own name.
 *
 * Mutation by reading: comparing the class extension case-insensitively in `shippedClassBytes` (or refusing an entry that ends in
 * `.class` in any other case) turns the first test green; reading every `Utf8` entry for every `L<name>` that ends in `;` or `<`
 * (any position, array dimensions and generic arguments included) turns the second (the Java shapes) green; a wrong skip length for any constant kind,
 * or a long/double that takes one slot, turns the third red.
 */
class JcaBytecodeScanBreakerR14Test {

    private val use: String = "java/security/MessageDigest"

    @Test
    fun `a class entry whose extension is spelled in another case is read by the jar scan or the scan fails`() {
        withDirectory { directory: Path ->
            val clean: ByteArray = classBytes("a/A") { pool: Pool -> pool.classOf("java/nio/file/Files") }
            val hidden: ByteArray = classBytes("x/Hidden") { pool: Pool -> pool.classOf(use) }
            val control: Path = jar(directory, "control.jar", "a/A.class" to clean, "x/Hidden.class" to hidden)
            assertEquals(
                listOf("x/Hidden names the class $use"),
                ClassFileScan.shippedClassBytes(control).map(ClassFileScan::parse).flatMap(ClassFileScan::violations),
                "control: the entry is flagged under its usual name",
            )
            for (name: String in listOf("x/Hidden.CLASS", "x/Hidden.Class", "x/Hidden.cLaSs")) {
                val shipped: Path = jar(directory, "case-${name.substringAfterLast('.')}.jar", "a/A.class" to clean, name to hidden)
                val outcome: Result<List<String>> = runCatching { ClassFileScan.shippedClassBytes(shipped).map(ClassFileScan::parse).flatMap(ClassFileScan::violations) }
                assertTrue(
                    outcome.isFailure || outcome.getOrThrow().contains("x/Hidden names the class $use"),
                    "the jar entry $name is a class file (a JVM loads it as x.Hidden from a case-insensitive directory, macOS and Windows), " +
                        "but the jar scan skipped it and passed: its JCA use is invisible (found: $outcome)",
                )
            }
        }
    }

    @Test
    fun `a forbidden type in an array descriptor or a generic signature of a Java class outside the boundary is flagged`() {
        val control: List<String> = ClassFileScan.violations(ClassFileScan.parse(javacShaped("j/Plain", "Ljava/util/Random;")))
        assertTrue(
            control.any { line: String -> "descriptor" in line && "java/util/Random" in line },
            "control: a plain field of type java.util.Random is flagged by its descriptor ($control)",
        )
        // The constant pools javac 21 writes for `java.util.Random[] r`, `java.util.List<javax.crypto.Cipher> c`,
        // `java.util.List<? extends java.util.Random> w` and `class J<T extends java.util.Random>` (observed with javap -v): the forbidden
        // type is in these Utf8 entries and in no CONSTANT_Class. A Java class has no Kotlin metadata to name it a second time.
        val shapes: List<Pair<ByteArray, String>> = listOf(
            javacShaped("j/ArrayField", "[Ljava/util/Random;") to "java/util/Random",
            javacShaped("j/GenericField", "Ljava/util/List;", fieldSignature = "Ljava/util/List<Ljavax/crypto/Cipher;>;") to "javax/crypto/Cipher",
            javacShaped("j/WildcardField", "Ljava/util/List;", fieldSignature = "Ljava/util/List<+Ljava/util/Random;>;") to "java/util/Random",
            javacShaped("j/BoundedType", "I", classSignature = "<T:Ljava/util/Random;>Ljava/lang/Object;") to "java/util/Random",
        )
        val missed: List<String> = shapes.mapNotNull { (bytes: ByteArray, forbidden: String) ->
            val facts: ClassFileScan.Facts = ClassFileScan.parse(bytes)
            val found: List<String> = ClassFileScan.violations(facts)
            if (found.any { line: String -> forbidden in line }) null else "${facts.name} names $forbidden (descriptors ${facts.descriptors}, found $found)"
        }
        assertEquals(
            emptyList(),
            missed,
            "a Java class outside the boundary (javac output of src/jvmMain/java ships in the jvm jar) names a forbidden type in its " +
                "signature, as an array or a generic argument or bound, which D-P12 allows only for java/security/SecureRandom, and the scan does not flag it",
        )
    }

    @Test
    fun `the same signatures in a Kotlin class are flagged`() {
        // Green pin: a Kotlin class names each such type a second time, as `Lname;` in the string table of its kotlin.Metadata,
        // which the descriptor rule reads. It must stay flagged whatever the fix of the Java case.
        val expected: List<Pair<Class<*>, String>> = listOf(
            R14FixturePlainField::class.java to "java/util/Random",
            R14FixtureArrayField::class.java to "java/util/Random",
            R14FixtureGenericField::class.java to "javax/crypto/Cipher",
            R14FixtureWildcardField::class.java to "java/util/Random",
            R14FixtureBoundedType::class.java to "java/util/Random",
        )
        val missed: List<String> = expected.mapNotNull { (type: Class<*>, forbidden: String) ->
            val facts: ClassFileScan.Facts = factsOf(type)
            val found: List<String> = ClassFileScan.violations(facts)
            if (found.any { line: String -> forbidden in line }) null else "${type.simpleName} (descriptors ${facts.descriptors}, found $found)"
        }
        assertEquals(emptyList(), missed, "a Kotlin class outside the boundary names a forbidden type in its signature and the scan does not flag it")
    }

    @Test
    fun `every constant kind is skipped by its exact size and a malformed modified UTF-8 entry fails the parse`() {
        val odd: ByteArray = classBytes("odd/Odd") { pool: Pool ->
            val text: Int = pool.utf8("\uD83D\uDE00 nul:\u0000 e:\u00E9")
            pool.long(Long.MIN_VALUE)
            pool.double(Double.NaN)
            pool.raw(3) { writeInt(-1) } // Integer
            pool.raw(4) { writeFloat(Float.NEGATIVE_INFINITY) } // Float
            pool.raw(12) { writeShort(text); writeShort(text) } // NameAndType
            pool.raw(9) { writeShort(1); writeShort(text) } // Fieldref
            pool.raw(10) { writeShort(1); writeShort(text) } // Methodref
            pool.raw(11) { writeShort(1); writeShort(text) } // InterfaceMethodref
            pool.raw(15) { writeByte(6); writeShort(text) } // MethodHandle: kind (1 byte) + reference (2 bytes)
            pool.raw(16) { writeShort(text) } // MethodType
            pool.raw(17) { writeShort(0); writeShort(text) } // Dynamic
            pool.raw(18) { writeShort(0); writeShort(text) } // InvokeDynamic
            pool.raw(19) { writeShort(text) } // Module
            pool.raw(20) { writeShort(text) } // Package
            pool.long(0x0707070707070707L) // a long whose bytes read like Class tags if the slot count were wrong
            pool.classOf("javax/crypto/Cipher")
            pool.string("java.security.KeyStore")
        }
        val facts: ClassFileScan.Facts = ClassFileScan.parse(odd)
        assertEquals("odd/Odd", facts.name, "the class name survives every constant kind before it")
        assertEquals(
            listOf("odd/Odd holds the string \"java.security.KeyStore\"", "odd/Odd names the class javax/crypto/Cipher"),
            ClassFileScan.violations(facts).sorted(),
            "the entries after every constant kind are read where they are",
        )
        // Standard UTF-8 for a supplementary character (four bytes, F0 9F 98 80) is not modified UTF-8: the class is malformed.
        val malformed: ByteArray = classBytes("bad/Bad") { pool: Pool ->
            pool.raw(1) { writeShort(4); write(byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x98.toByte(), 0x80.toByte())) }
            pool.classOf(use)
        }
        val outcome: Result<ClassFileScan.Facts> = runCatching { ClassFileScan.parse(malformed) }
        assertTrue(outcome.isFailure, "a Utf8 entry in standard four-byte UTF-8 is no class file, so the parse must fail and never pass (got ${outcome.getOrNull()?.name})")
    }

    @Test
    fun `every class of the running JDK's java base module parses to its own name`() {
        val jrt: FileSystem = FileSystems.getFileSystem(URI.create("jrt:/"))
        val base: Path = jrt.rootDirectories.first().resolve("modules").resolve("java.base")
        val classes: List<Path> = Files.walk(base).use { stream -> stream.filter { file: Path -> file.isRegularFile() && file.fileName.toString().endsWith(".class") }.toList() }
        assertTrue(classes.size > MIN_JDK_CLASSES, "control: the JDK's java.base holds its classes (${classes.size})")
        val wrong: List<String> = classes.mapNotNull { file: Path ->
            val segments: List<String> = base.relativize(file).map { segment: Path -> segment.toString() }
            val expected: String = segments.dropLast(1).plus(segments.last().removeSuffix(".class")).joinToString(INTERNAL_SEPARATOR)
            val parsed: Result<ClassFileScan.Facts> = runCatching { ClassFileScan.parse(file.readBytes()) }
            when {
                parsed.isFailure -> "$expected: ${parsed.exceptionOrNull()}"
                parsed.getOrThrow().name != expected -> "$expected parsed as ${parsed.getOrThrow().name}"
                else -> null
            }
        }
        assertEquals(emptyList(), wrong.take(REPORTED), "classes of java.base the parser misreads (${wrong.size} in all)")
        val moduleInfo: ClassFileScan.Facts = ClassFileScan.parse(base.resolve("module-info.class").readBytes())
        assertEquals("module-info", moduleInfo.name, "control: module-info (Module and Package constants) parses")
    }

    private fun factsOf(type: Class<*>): ClassFileScan.Facts {
        val resource: String = INTERNAL_SEPARATOR + type.name.replace('.', '/') + ".class"
        val bytes: ByteArray = type.getResourceAsStream(resource)?.use { stream -> stream.readBytes() }
            ?: error("the class file of ${type.name} was not found: the test cannot run (it must not pass silently)")
        return ClassFileScan.parse(bytes)
    }

    private fun withDirectory(block: (Path) -> Unit) {
        val directory: Path = Files.createTempDirectory("kmptoolkit-classscan-r14")
        try {
            block(directory)
        } finally {
            @OptIn(kotlin.io.path.ExperimentalPathApi::class)
            directory.deleteRecursively()
        }
    }

    private fun jar(directory: Path, name: String, vararg entries: Pair<String, ByteArray>): Path {
        val file: Path = directory.resolve(name)
        JarOutputStream(Files.newOutputStream(file)).use { out: JarOutputStream ->
            for ((entryName: String, content: ByteArray) in entries) {
                out.putNextEntry(JarEntry(entryName))
                out.write(content)
                out.closeEntry()
            }
        }
        return file
    }

    /** A constant-pool writer: each call writes one entry and answers its index (a long or a double takes two). */
    private class Pool(private val data: DataOutputStream) {
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

        /** One entry of [tag] whose body [body] writes as it is. */
        fun raw(tag: Int, body: DataOutputStream.() -> Unit): Int {
            data.writeByte(tag)
            data.body()
            return next++
        }
    }

    /** A class file of [thisName] with the constants [fill] writes, no super class, no member and no attribute. */
    private fun classBytes(thisName: String, fill: (Pool) -> Unit): ByteArray {
        val poolBytes = ByteArrayOutputStream()
        val pool = Pool(DataOutputStream(poolBytes))
        val thisClass: Int = pool.classOf(thisName)
        fill(pool)
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(0xCAFEBABE.toInt())
            writeShort(0)
            writeShort(CLASS_VERSION)
            writeShort(pool.next)
            write(poolBytes.toByteArray())
            writeShort(ACC_PUBLIC)
            writeShort(thisClass)
            writeShort(0) // super class
            writeShort(0) // interfaces
            writeShort(0) // fields
            writeShort(0) // methods
            writeShort(0) // attributes
            flush()
        }
        return out.toByteArray()
    }

    /**
     * A class file shaped like javac's output for one field of [fieldDescriptor] (with a `Signature` attribute [fieldSignature] when the
     * field type is generic) and, when [classSignature] is given, a class `Signature` attribute: super class `java/lang/Object`, no method.
     */
    private fun javacShaped(thisName: String, fieldDescriptor: String, fieldSignature: String? = null, classSignature: String? = null): ByteArray {
        val poolBytes = ByteArrayOutputStream()
        val pool = Pool(DataOutputStream(poolBytes))
        val superClass: Int = pool.classOf("java/lang/Object")
        val thisClass: Int = pool.classOf(thisName)
        val fieldName: Int = pool.utf8("f")
        val descriptor: Int = pool.utf8(fieldDescriptor)
        val signatureName: Int = pool.utf8("Signature")
        val fieldSignatureIndex: Int? = fieldSignature?.let { text: String -> pool.utf8(text) }
        val classSignatureIndex: Int? = classSignature?.let { text: String -> pool.utf8(text) }
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(0xCAFEBABE.toInt())
            writeShort(0)
            writeShort(CLASS_VERSION)
            writeShort(pool.next)
            write(poolBytes.toByteArray())
            writeShort(ACC_PUBLIC)
            writeShort(thisClass)
            writeShort(superClass)
            writeShort(0) // interfaces
            writeShort(1) // fields
            writeShort(ACC_PUBLIC)
            writeShort(fieldName)
            writeShort(descriptor)
            writeAttribute(signatureName, fieldSignatureIndex)
            writeShort(0) // methods
            writeAttribute(signatureName, classSignatureIndex)
            flush()
        }
        return out.toByteArray()
    }

    /** An attributes table: none, or one `Signature` attribute pointing at [value]. */
    private fun DataOutputStream.writeAttribute(name: Int, value: Int?) {
        if (value == null) {
            writeShort(0)
        } else {
            writeShort(1)
            writeShort(name)
            writeInt(2)
            writeShort(value)
        }
    }

    private companion object {
        const val CLASS_VERSION: Int = 61
        const val ACC_PUBLIC: Int = 0x0001
        const val MIN_JDK_CLASSES: Int = 3000
        const val REPORTED: Int = 20

        /** The separator of a JVM internal name, which is always `/`, never a file-system separator. */
        const val INTERNAL_SEPARATOR: String = "/"
    }
}

/** Control: a field whose plain descriptor names a forbidden supertype of `SecureRandom`. Never run. */
internal class R14FixturePlainField {
    @JvmField
    var random: java.util.Random? = null
}

/** A field whose ARRAY descriptor (`[Ljava/util/Random;`) names a forbidden type, and nothing else does. Never run. */
internal class R14FixtureArrayField {
    @JvmField
    var randoms: Array<java.util.Random>? = null
}

/** A field whose generic `Signature` (`Ljava/util/List<Ljavax/crypto/Cipher;>;`) names a forbidden type. Never run. */
internal class R14FixtureGenericField {
    @JvmField
    var ciphers: List<javax.crypto.Cipher>? = null
}

/** A field whose generic `Signature` names a forbidden type as a wildcard bound (`Ljava/util/List<+Ljava/util/Random;>;`). Never run. */
internal class R14FixtureWildcardField {
    @JvmField
    var randoms: MutableList<out java.util.Random>? = null
}

/** A class whose type parameter is bounded by a forbidden type (`<T:Ljava/util/Random;>Ljava/lang/Object;`). Never run. */
internal class R14FixtureBoundedType<T : java.util.Random>
