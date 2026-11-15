package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.jar.JarFile
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes

/**
 * The part of the JCA boundary scan (decisions D-P12) that reads COMPILED classes: what a class file's constant pool says about
 * the JDK crypto types a class touches, whatever its source looked like. A lexer over source text can be fooled by quoting,
 * back-quotes, spacing, aliases and constructor references; the constant pool cannot, because the compiler has already resolved all of
 * them to one `CONSTANT_Class` per type a class names (the owner of every method, field and constructor reference included).
 *
 * What is flagged, in a class that is not the boundary (the boundary is exempt by its EXACT name only: a class whose name starts with
 * `Jca$` is flagged for that name alone, whichever file it was compiled from, because a binary name and the `SourceFile` base name
 * are both chosen by the source):
 * - a `CONSTANT_Class` whose internal name (array and `L...;` wrapping stripped) is under `java/security/`, `javax/crypto/`,
 *   `kotlin/io/encoding/`, is `java/util/Base64` or one of its nested classes, or is one of the SUPERTYPES through which a
 *   `SecureRandom` can be driven without naming it: `java/util/Random`, anything under `java/util/random/` (`RandomGenerator`) and
 *   `kotlin/random/PlatformRandomKt` (`asKotlinRandom`, `asJavaRandom`). That covers a construction, a call, a field read, an
 *   `instanceof`, a cast, a call on an INSTANCE the boundary handed out (its owner is the JDK type), and the same call after an
 *   implicit upcast (`val plain: java.util.Random = random`, a parameter of type `RandomGenerator`, `random.asKotlinRandom()`),
 *   whose owner is the supertype. None of those supertypes is used by the module's main code today; a legitimate non-secure
 *   `Random` there would have to go through the boundary;
 * - a `CONSTANT_String` that spells one of the JCA packages with dots (`Class.forName("java.security.SecureRandom")` written out);
 * - a descriptor (a `Utf8` entry) that mentions one of those types other than `java/security/SecureRandom`, the one type that may
 *   cross the boundary (`JcaRandom`, how an injectable random source is accepted). Naming it in a signature is no call: a call on it
 *   is a `CONSTANT_Class`, which is flagged. A function of another module that takes a `java.util.Random` or a `RandomGenerator` is
 *   flagged through the descriptor of the call, so passing a `JcaRandom` to it is seen too.
 *
 * Not seen, stated: reflection by a computed string (`Class.forName("java.sec" + name)` built at run time; a constant expression is
 * folded by the compiler into one string and is flagged), `MethodHandles` and a `ServiceLoader` lookup by a computed name, and a
 * helper of ANOTHER module that reaches the JCA inside its own code (the scan reads this module's classes only), the `toString()` of
 * a provider's random engine that code outside the boundary reaches through `Object` (`"$random"`: the owner is `StringBuilder`, the
 * descriptor `Object`; negligible, and nothing in the module does it), and classes compiled from JAVA sources of `jvmFfm` (they land
 * in Gradle's Java output, not under the Kotlin `ffm` directory [mainOutputs] lists; the set has none). Compile-time constants are
 * no hole: an `Int` constant is inlined as a number and names no class. The source scan (`JcaBoundaryScanTest`) sees none of those
 * either.
 */
internal object ClassFileScan {

    /** Which JDK crypto types a class file touches, as read from its constant pool. */
    class Facts(
        val name: String,
        val classRefs: Set<String>,
        val strings: Set<String>,
        val descriptors: Set<String>,
        /** The class's `SourceFile` attribute (the BASE name of the file it was compiled from), `null` when it has none; a diagnostic, never a proof of identity. */
        val sourceFile: String? = null,
    )

    /** A class file that is not one: bad magic, an unknown constant tag, or a truncated pool. */
    class MalformedClass(message: String) : IllegalArgumentException(message)

    private val FORBIDDEN_PREFIXES: List<String> = listOf("java/security/", "javax/crypto/", "kotlin/io/encoding/", "java/util/random/")

    /** Types that are forbidden by their exact name and by the names of their nested classes (`Name$...`). */
    private val FORBIDDEN_NAMES: List<String> = listOf("java/util/Base64", "java/util/Random", "kotlin/random/PlatformRandomKt")
    private val DOTTED: List<String> = listOf("java.security", "javax.crypto", "java.util.Base64", "kotlin.io.encoding")
    private val DESCRIPTOR_TYPE: Regex = Regex("L([^;()\\[]+);")
    private const val ALLOWED_TYPE: String = "java/security/SecureRandom"

    /** The internal name of the boundary, `Jca`, taken from the class itself: a spelling of a path cannot drift away from the real one. */
    private val BOUNDARY: String = Jca::class.java.name.replace('.', '/')

    /**
     * Whether [className] is the boundary class itself: EXACTLY its internal name. Never a name shape: a class `Jca` of another
     * package, or a top-level class whose back-quoted name holds a `$` (`Jca$Helper`), is not the boundary. The name is matched in every
     * scanned location, so the scan test fails when it occurs more than once (a same-named class of the ffm output would be exempt).
     */
    fun isBoundary(className: String): Boolean = className == BOUNDARY

    /**
     * Whether [facts] describe the boundary: its exact name and nothing else. The boundary has no exempt nested class. Neither a
     * name that starts with `Jca$` nor the `SourceFile` attribute proves that a class is nested in it: a binary name is chosen by
     * the source (a back-quoted class name, `@file:JvmName("Jca\$X")`), and `SourceFile` holds only the BASE name of a file, so any
     * second file called `Jca.kt` of any directory or source set produces it. Such a class is scanned like every other one and
     * [violations] reports its name besides ([isNamedLikeNested]).
     */
    fun isBoundaryClass(facts: Facts): Boolean = isBoundary(facts.name)

    /**
     * Whether [className] reads like a class nested in the boundary (`Jca$...`: a lambda, a local or an anonymous class that the
     * compiler would make of `Jca`). The compiled boundary has none today (`Jca`, `JcaFailure` and `JcaKt` only: Kotlin 2 compiles a
     * lambda through `invokedynamic`, and `provider` is inline). If one is ever needed it must be confirmed by the boundary's OWN
     * class file (its `InnerClasses` table naming the class, which a second source file cannot change without editing the boundary)
     * and this rule extended on purpose, never by a name or a file name.
     */
    fun isNamedLikeNested(className: String): Boolean = className.startsWith("$BOUNDARY$")

    fun isForbiddenType(internalName: String): Boolean {
        val bare: String = if (internalName.startsWith("[")) internalName.trimStart('[').removePrefix("L").removeSuffix(";") else internalName
        return FORBIDDEN_PREFIXES.any { prefix: String -> bare.startsWith(prefix) } ||
            FORBIDDEN_NAMES.any { name: String -> bare == name || bare.startsWith("$name$") }
    }

    /** Parses the constant pool of [bytes]; throws [MalformedClass] for anything that is not a well-formed class file. */
    fun parse(bytes: ByteArray): Facts {
        try {
            val input = DataInputStream(ByteArrayInputStream(bytes))
            if (input.readInt() != MAGIC) throw MalformedClass("bad magic")
            input.readUnsignedShort()
            input.readUnsignedShort()
            val count: Int = input.readUnsignedShort()
            val utf8: Array<String?> = arrayOfNulls(count)
            // Entry index to the index of its Utf8 (a class's name, a string's text): resolved after the whole pool is read.
            val classEntries: MutableMap<Int, Int> = mutableMapOf()
            val stringEntries: MutableList<Int> = mutableListOf()
            var index = 1
            while (index < count) {
                when (val tag: Int = input.readUnsignedByte()) {
                    TAG_UTF8 -> utf8[index] = input.readUTF()
                    TAG_INTEGER, TAG_FLOAT -> input.skipFully(4)
                    // An eight-byte constant takes two slots of the pool.
                    TAG_LONG, TAG_DOUBLE -> {
                        input.skipFully(8)
                        index++
                    }
                    TAG_CLASS -> classEntries[index] = input.readUnsignedShort()
                    TAG_STRING -> stringEntries += input.readUnsignedShort()
                    TAG_METHOD_TYPE, TAG_MODULE, TAG_PACKAGE -> input.skipFully(2)
                    TAG_FIELD_REF, TAG_METHOD_REF, TAG_INTERFACE_METHOD_REF, TAG_NAME_AND_TYPE, TAG_DYNAMIC, TAG_INVOKE_DYNAMIC -> input.skipFully(4)
                    TAG_METHOD_HANDLE -> input.skipFully(3)
                    else -> throw MalformedClass("unknown constant tag $tag at entry $index")
                }
                index++
            }
            input.readUnsignedShort()
            val thisClass: Int = input.readUnsignedShort()
            fun text(entry: Int, what: String): String = utf8.getOrNull(entry) ?: throw MalformedClass("$what $entry is not a Utf8 entry")
            val thisName: String = text(classEntries[thisClass] ?: throw MalformedClass("this_class $thisClass is not a CONSTANT_Class entry"), "this_class name")
            // The rest of the class file, as far as the scan needs it: the super class, the interfaces, the fields and the methods
            // are skipped, the attributes of the class are read for `SourceFile` (the file the class was compiled from).
            input.readUnsignedShort()
            input.skipFully(input.readUnsignedShort() * 2)
            skipMembers(input)
            skipMembers(input)
            var sourceFile: String? = null
            repeat(input.readUnsignedShort()) {
                val attributeName: String = text(input.readUnsignedShort(), "attribute name")
                val length: Int = input.readInt()
                if (length < 0) throw MalformedClass("attribute $attributeName is longer than a class file can hold")
                if (attributeName == "SourceFile" && sourceFile == null) {
                    if (length != 2) throw MalformedClass("SourceFile attribute of $length bytes")
                    sourceFile = text(input.readUnsignedShort(), "SourceFile")
                } else {
                    input.skipFully(length)
                }
            }
            return Facts(
                name = thisName,
                classRefs = classEntries.values.map { nameIndex: Int -> text(nameIndex, "class name") }.toSet(),
                strings = stringEntries.map { stringIndex: Int -> text(stringIndex, "string") }.toSet(),
                descriptors = utf8.filterNotNull().filter { value: String -> '(' in value || (value.startsWith("L") && value.endsWith(";")) }.toSet(),
                sourceFile = sourceFile,
            )
        } catch (eof: EOFException) {
            throw MalformedClass("truncated class file")
        }
    }

    /** What a class file touches that the boundary rules forbid, one readable line per distinct finding. */
    fun violations(facts: Facts): List<String> {
        val found: MutableList<String> = mutableListOf()
        if (isNamedLikeNested(facts.name)) {
            found += "${facts.name} is named like a class nested in the boundary $BOUNDARY (compiled from ${facts.sourceFile}): the boundary has no " +
                "nested class and a name or a file name proves nothing; a class that is really needed there must be confirmed by the InnerClasses table of $BOUNDARY itself"
        }
        facts.classRefs.filter(::isForbiddenType).sorted().forEach { type: String -> found += "${facts.name} names the class $type" }
        facts.strings.filter { text: String -> DOTTED.any { name: String -> name in text } }.sorted().forEach { text: String -> found += "${facts.name} holds the string \"$text\"" }
        facts.descriptors.forEach { descriptor: String ->
            DESCRIPTOR_TYPE.findAll(descriptor).map { match: MatchResult -> match.groupValues[1] }
                .filter { type: String -> isForbiddenType(type) && type != ALLOWED_TYPE }
                .forEach { type: String -> found += "${facts.name} has a descriptor that mentions $type" }
        }
        return found.distinct()
    }

    /**
     * Name of the second compiled-output directory of the module's jvm main code, next to the one `Jca` was loaded from
     * (`build/classes/kotlin/jvm/ffm`, beside `.../main`): the `ffm` compilation of the `kmptoolkit.ffm` plugin, source set
     * `jvmFfm`, which ships in the same jvm jar. It exists only on a branch that has that source set; the check-only compilation
     * `ffmApiCheck` compiles the same sources again and is not listed. A jar needs no sibling: it carries every class.
     */
    private const val FFM_OUTPUT: String = "ffm"

    /**
     * Every compiled-output location of the module's jvm main code that exists: [codeSource] of the boundary (it must exist, or the
     * scan cannot run and fails) plus, when [codeSource] is a directory, its `ffm` sibling if there is one. A branch without the
     * `ffm` directory is not an error; a scan with no location at all is.
     */
    fun mainOutputs(codeSource: URL): List<URL> {
        val main: Path = Paths.get(codeSource.toURI())
        check(Files.exists(main)) { "the compiled output $codeSource does not exist: the scan cannot run (it must not pass silently)" }
        val ffm: Path? = if (main.isDirectory()) main.resolveSibling(FFM_OUTPUT).takeIf { sibling: Path -> sibling.isDirectory() } else null
        return listOfNotNull(main, ffm).map { output: Path -> output.toUri().toURL() }
    }

    /** The class files under every one of [locations] (see [classBytes]). */
    fun classBytes(locations: List<URL>): List<ByteArray> = locations.flatMap { location: URL -> classBytes(location) }

    /** The class files under [location]: a directory (walked) or a jar (its entries). Fails when [location] is neither. */
    fun classBytes(location: URL): List<ByteArray> {
        val path: Path = Paths.get(location.toURI())
        return when {
            path.isDirectory() -> Files.walk(path).use { stream -> stream.filter { file: Path -> file.isRegularFile() && file.extension == "class" }.map { file: Path -> file.readBytes() }.toList() }
            path.isRegularFile() && path.extension == "jar" -> JarFile(path.toFile()).use { jar: JarFile ->
                jar.entries().asSequence().filter { entry -> !entry.isDirectory && entry.name.endsWith(".class") }.map { entry -> jar.getInputStream(entry).use { stream -> stream.readBytes() } }.toList()
            }
            else -> error("the class location $location is neither a directory nor a jar: the scan cannot run (it must not pass silently)")
        }
    }

    /** Skips a `fields` or `methods` table: access flags, name, descriptor and the attributes of each member. */
    private fun skipMembers(input: DataInputStream) {
        repeat(input.readUnsignedShort()) {
            input.skipFully(6)
            repeat(input.readUnsignedShort()) {
                input.skipFully(2)
                val length: Int = input.readInt()
                if (length < 0) throw MalformedClass("a member attribute is longer than a class file can hold")
                input.skipFully(length)
            }
        }
    }

    private fun DataInputStream.skipFully(count: Int) {
        var left: Int = count
        while (left > 0) {
            val skipped: Int = skipBytes(left)
            if (skipped <= 0) throw EOFException()
            left -= skipped
        }
    }

    private const val MAGIC: Int = 0xCAFEBABE.toInt()
    private const val TAG_UTF8: Int = 1
    private const val TAG_INTEGER: Int = 3
    private const val TAG_FLOAT: Int = 4
    private const val TAG_LONG: Int = 5
    private const val TAG_DOUBLE: Int = 6
    private const val TAG_CLASS: Int = 7
    private const val TAG_STRING: Int = 8
    private const val TAG_FIELD_REF: Int = 9
    private const val TAG_METHOD_REF: Int = 10
    private const val TAG_INTERFACE_METHOD_REF: Int = 11
    private const val TAG_NAME_AND_TYPE: Int = 12
    private const val TAG_METHOD_HANDLE: Int = 15
    private const val TAG_METHOD_TYPE: Int = 16
    private const val TAG_DYNAMIC: Int = 17
    private const val TAG_INVOKE_DYNAMIC: Int = 18
    private const val TAG_MODULE: Int = 19
    private const val TAG_PACKAGE: Int = 20
}
