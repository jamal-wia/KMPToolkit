package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.net.URL
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
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
 * - a descriptor or a signature (EVERY `Utf8` entry that is not the name of a `CONSTANT_Class`, read for each `L<name>` that ends in `;` or
 *   `<`, at any position: a field or method descriptor, an array of any dimension, a generic `Signature` with its arguments, wildcards
 *   and inner classes, the bound of a type parameter) that mentions one of those types other than `java/security/SecureRandom`, the one type that may
 *   cross the boundary (`JcaRandom`, how an injectable random source is accepted). Naming it in a signature is no call: a call on it
 *   is a `CONSTANT_Class`, which is flagged. A function of another module that takes a `java.util.Random` or a `RandomGenerator` is
 *   flagged through the descriptor of the call, so passing a `JcaRandom` to it is seen too.
 *
 * Not seen, stated: reflection by a computed string (`Class.forName("java.sec" + name)` built at run time; a constant expression is
 * folded by the compiler into one string and is flagged), `MethodHandles` and a `ServiceLoader` lookup by a computed name, and a
 * helper of ANOTHER module that reaches the JCA inside its own code (the scan reads this module's classes only), the `toString()` of
 * a provider's random engine that code outside the boundary reaches through `Object` (`"$random"`: the owner is `StringBuilder`, the
 * descriptor `Object`; negligible, and nothing in the module does it), and class bytes under an entry name that is not a class name (`X.bin`)
 * that a custom class loader defines at run time (the same class as reflection by a computed string). Both scans are defence in depth for the
 * maintainers of the module (decisions D-36): they stop an accidental direct JCA use, not repository code written on purpose to evade them, and a
 * further evasion of that kind is a limit named here, not a rule to add. Compile-time constants are no hole: an `Int` constant is inlined
 * as a number and names no class. The source scan (`JcaBoundaryScanTest`) sees none of those either.
 *
 * Where the classes are read from, in two layers.
 *
 * PRIMARY: the entries of the BUILT JVM JAR ([shippedJar], [shippedClassBytes]). The build makes `jvmTest` depend on `jvmJar` and
 * gives it the jar's path in the system property [JVM_JAR_PROPERTY]; the scan reads EVERY `.class` entry of that one file, whatever the
 * entry's path and whatever the case of the extension (`Hidden.CLASS` is a class on a case-insensitive file system). The jar is by definition what ships, so nothing is discovered and nothing is classified by a directory name: a class
 * that is packed from the class directories, from the processed resources, from a multi-release directory or from a `from(...)` that a
 * later build change adds (the `ffm` output of the FFM branch rides in this jar the same way) is read. The scan FAILS, and never
 * passes, when the property is missing or the file does not exist, when the file is not a readable archive, when it holds no class
 * entry, when it holds a nested archive (`.jar`, `.war`, `.ear`, `.zip`: its classes would be invisible to this read, so it is refused
 * instead of counted as scanned; the jar holds none), and when an entry name occurs twice (Gradle's `Jar` task refuses to write one, so
 * it cannot happen to a built jar; the read fails on it all the same, so no class can hide behind another of its name). What the jar read
 * cannot see is stated above (reflection by a computed string, a helper of another module, `toString()` through `Object`) plus what is
 * not in this jar: the Android artifact and the iOS klib are out of scope by D-P12 (the Android store has its own rules), and a
 * renamed jvm target has no `jvmJar`, so its `jvmTest` does not exist either.
 *
 * SECOND: the directories the jar is built from ([mainOutputs]): every compilation output of the jvm main code found by walking
 * Gradle's `classes` tree and the `processedResources` beside it, Kotlin and Java alike (`build/classes/kotlin/jvm/main`, the `ffm`
 * output where that source set exists, the javac output of `src/jvmMain/java`, a `.class` file among the resources). It exists for
 * a tree without a jar and for a failure that names the file; it is a classification by path (a `jvm` segment, no `test` or `check`
 * segment), so a shipped output named like a test is invisible to it and only the jar read sees it. One build fact about `jvmFfm`: a
 * Java source under `src/jvmFfm/java` makes the build fail on a branch that has that source set (the Java and Kotlin compile tasks
 * of the `ffm` compilation disagree on the JVM target, observed by the round-12 breaker), so no such class ships.
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

    /**
     * A class type named inside ANY `Utf8` entry: `L<internal name>` ended by `;` or by `<` (a type with type arguments), at any position.
     * One rule for every shape the JVM writes a type in: a field or method descriptor (`Ljava/util/Random;`, `([Ljavax/crypto/Cipher;)V`,
     * any number of array dimensions), a generic `Signature` (`Ljava/util/List<Ljavax/crypto/Cipher;>;`, a wildcard `<+Ljava/util/Random;>`,
     * a method signature with a `throws` part, an inner class of a generic outer `Lp/O<TT;>.I<Lq/Random;>;`) and the bound of a type
     * parameter (`<T:Ljava/util/Random;>`, an interface bound `<T::Lq/I;>`). The name stops at `;`, `<`, `>`, `:`, `(`, `)` and `[`, which
     * a name never holds, so the next type of the same entry starts where this one ended. A type variable (`TT;`) names no class and an
     * entry that merely looks like a descriptor can only add a finding for a FORBIDDEN type, which is loud and acceptable.
     */
    private val DESCRIPTOR_TYPE: Regex = Regex("L([^;<>()\\[:]+)[;<]")
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
            val classNames: Set<Int> = classEntries.values.toSet()
            return Facts(
                name = thisName,
                classRefs = classEntries.values.map { nameIndex: Int -> text(nameIndex, "class name") }.toSet(),
                strings = stringEntries.map { stringIndex: Int -> text(stringIndex, "string") }.toSet(),
                // The name of a CONSTANT_Class (`[Ljava/util/Random;` for an array class) is reported as a class above, once.
                descriptors = utf8.withIndex().mapNotNull { (entry: Int, value: String?) ->
                    value?.takeIf { text: String -> entry !in classNames && ('(' in text || DESCRIPTOR_TYPE.containsMatchIn(text)) }
                }.toSet(),
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
     * Every compiled-output location of the module's jvm main code that exists, FOUND BY WALKING, never by naming a directory:
     * [codeSource] of the boundary (it must exist, or the scan cannot run and fails) plus, when [codeSource] is a directory, every
     * other output beside it that belongs to the jvm main code. This is the SECOND layer of the bytecode scan: the first reads the
     * entries of the built jvm jar ([shippedClassBytes]), which is what ships whatever its inputs are; this one reads the directories
     * the jar is built from, so a failure names the file and the test can run on a tree without a jar. Four rounds in a row a scan that
     * listed the outputs it knew missed a layout that was shipped in the jvm jar; this one has no list.
     *
     * How: the `classes` directory is the nearest ancestor of [codeSource] with that name (Gradle's own `build/classes`; none is a
     * failure), and its sibling `processedResources` (the resources the jar also packs, a `.class` file among them) is walked too when it
     * exists. Every class file is attributed to its OWN output ROOT, the directory its package path starts at, read from the class
     * itself (`classes/kotlin/jvm/main`, `.../ffm`, `classes/java/jvmMain`, `processedResources/jvm/main`, a layout of a later Kotlin or
     * Gradle); a class whose directories do not follow its package gets its own directory. A root belongs to the jvm main code when a
     * segment of its path below the walked directory starts with `jvm` (any case) and none ends with `test` (tests are not shipped) or
     * `check` (the check-only compilation `ffmApiCheck` compiles the sources of `ffm` again and ships nothing). The Android outputs carry
     * no such segment: the Android store has its own rules (D-P12). Only AFTER that classification is a root dropped for being nested
     * in another root that is read anyway (reading both would read its classes twice), so a class file lying above a real output (a
     * default-package class directly in `classes` or in `classes/java`) is a root of its own that is not shipped, and never hides what
     * is below it. A jar needs no walk: it carries every class, and it is returned as it is. The code source is first; the rest follow in path order.
     */
    fun mainOutputs(codeSource: URL): List<URL> {
        val main: Path = Paths.get(codeSource.toURI())
        check(Files.exists(main)) { "the compiled output $codeSource does not exist: the scan cannot run (it must not pass silently)" }
        if (!main.isDirectory()) return listOf(main.toUri().toURL())
        val classes: Path = generateSequence(main.parent) { directory: Path -> directory.parent }
            .firstOrNull { directory: Path -> directory.fileName?.toString() == CLASSES_DIRECTORY }
            ?: error("the compiled output $codeSource is not under a `$CLASSES_DIRECTORY` directory: the other compilation outputs of the jvm main code cannot be found, so the scan cannot run (it must not pass silently)")
        val walked: List<Path> = listOf(classes, classes.resolveSibling(PROCESSED_RESOURCES_DIRECTORY)).filter { directory: Path -> directory.isDirectory() }
        // `+ setOf(main)`: a Path is an Iterable of its own segments, so `set + main` would add them one by one.
        val shipped: Set<Path> = walked.flatMap { base: Path -> outputRoots(base).filter { root: Path -> isJvmMainOutput(base.relativize(root)) } }.toSet() + setOf(main)
        // An output that lies inside another one is read through the outer one: listing both would read its classes twice.
        val outer: List<Path> = shipped.filterNot { root: Path -> shipped.any { other: Path -> other != root && root.startsWith(other) } }
        return outer.sortedWith(compareBy<Path>({ root: Path -> root != main }, { root: Path -> root })).map { output: Path -> output.toUri().toURL() }
    }

    /** The name of Gradle's directory of compiled classes, the one fixed point of the walk in [mainOutputs]. */
    private const val CLASSES_DIRECTORY: String = "classes"

    /** The directory beside `classes` where Gradle puts the resources of a compilation (`processedResources/jvm/main`): the jvm jar packs them. */
    private const val PROCESSED_RESOURCES_DIRECTORY: String = "processedResources"

    /** Whether a compilation output at [relative] (its path below the walked directory) is jvm code that is shipped: see [mainOutputs]. */
    private fun isJvmMainOutput(relative: Path): Boolean {
        val segments: List<String> = relative.map { segment: Path -> segment.toString().lowercase() }
        return segments.any { segment: String -> segment.startsWith("jvm") } &&
            segments.none { segment: String -> segment.endsWith("test") || segment.endsWith("check") }
    }

    /** The root of EVERY class file under [base] (see [rootOf]); no directory and no class file is skipped. */
    private fun outputRoots(base: Path): Set<Path> {
        val roots: MutableSet<Path> = linkedSetOf()
        Files.walkFileTree(
            base,
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (isClassFileName(file.fileName.toString())) roots.add(rootOf(file))
                    return FileVisitResult.CONTINUE
                }
            },
        )
        return roots
    }

    /**
     * The directory the package path of [classFile] starts at, from the name the class file itself carries. A class whose directories
     * do not follow its package (nothing the compilers do) has no such directory: its own directory is taken instead, so the class is
     * still read, and never dropped.
     */
    private fun rootOf(classFile: Path): Path {
        // path-hygiene-ignore: PH3 a JVM internal class name, not a file path
        val packageSegments: List<String> = parse(classFile.readBytes()).name.split('/').dropLast(1)
        var root: Path = classFile.parent
        for (segment: String in packageSegments.asReversed()) {
            if (root.fileName?.toString() != segment) return classFile.parent
            root = root.parent ?: return classFile.parent
        }
        return root
    }

    /** The class files under every one of [locations] (see [classBytes]). */
    fun classBytes(locations: List<URL>): List<ByteArray> = locations.flatMap { location: URL -> classBytes(location) }

    /** The class files under [location]: a directory (walked) or a jar (its entries). Fails when [location] is neither. */
    fun classBytes(location: URL): List<ByteArray> {
        val path: Path = Paths.get(location.toURI())
        return when {
            path.isDirectory() -> Files.walk(path).use { stream -> stream.filter { file: Path -> file.isRegularFile() && isClassFileName(file.fileName.toString()) }.map { file: Path -> file.readBytes() }.toList() }
            path.isRegularFile() && path.extension == "jar" -> JarFile(path.toFile()).use { jar: JarFile ->
                jar.entries().asSequence().filter { entry -> !entry.isDirectory && isClassFileName(entry.name) }.map { entry -> jar.getInputStream(entry).use { stream -> stream.readBytes() } }.toList()
            }
            else -> error("the class location $location is neither a directory nor a jar: the scan cannot run (it must not pass silently)")
        }
    }

    /** The system property the build gives `jvmTest`: the absolute path of the built jvm jar (`kmptoolkit-storage/build.gradle.kts`). */
    const val JVM_JAR_PROPERTY: String = "kmptoolkit.test.jvmJar"

    /**
     * The built jvm jar named by [path] (the value of [JVM_JAR_PROPERTY]): the one artifact that ships. Fails, never passes, when the
     * property is missing or blank (a test run that the build did not start, an IDE run without the jar), when the file does not
     * exist or is not a regular file.
     */
    fun shippedJar(path: String?): Path {
        check(!path.isNullOrBlank()) { "the system property $JVM_JAR_PROPERTY is not set: the jar that ships cannot be scanned (it must not pass silently); run the test through Gradle" }
        val jar: Path = Paths.get(path)
        check(jar.isRegularFile()) { "the jvm jar $jar named by $JVM_JAR_PROPERTY does not exist or is not a file: the jar that ships cannot be scanned (it must not pass silently)" }
        return jar
    }

    /**
     * Every `.class` entry of [jar], whatever its path (a class in the processed resources, in a multi-release directory, anywhere): what
     * the jar holds is what ships, so there is nothing to discover and nothing to classify. Fails, never passes, when the jar is not
     * a readable archive, holds no class entry, or holds an archive of its own (`.jar`, `.war`, `.ear`, `.zip`): a class inside a nested
     * archive is invisible to this read, and no class loader of a plain consumer opens one, so a nested archive is refused instead of
     * being counted as scanned. The same entry name twice (which Gradle's `Jar` task refuses to write, and `java.util.zip` lists
     * as two entries) fails too, so no class can hide behind another of its name.
     */
    fun shippedClassBytes(jar: Path): List<ByteArray> =
        JarFile(jar.toFile()).use { archive: JarFile ->
            val entries: List<java.util.jar.JarEntry> = archive.entries().asSequence().filter { entry -> !entry.isDirectory }.toList()
            val names: List<String> = entries.map { entry -> entry.name }
            val twice: List<String> = names.groupingBy { name: String -> name }.eachCount().filterValues { count: Int -> count > 1 }.keys.sorted()
            check(twice.isEmpty()) { "the jar $jar lists these entries more than once: $twice (a class could hide behind another of its name)" }
            val nested: List<String> = names.filter { name: String -> NESTED_ARCHIVES.any { suffix: String -> name.lowercase().endsWith(suffix) } }
            check(nested.isEmpty()) { "the jar $jar holds the archives $nested: the classes inside them are not read by this scan, so it refuses them" }
            val classes: List<java.util.jar.JarEntry> = entries.filter { entry -> isClassFileName(entry.name) }
            check(classes.isNotEmpty()) { "the jar $jar holds no class entry (${names.size} entries): the scan has nothing to read, so it fails" }
            classes.map { entry -> archive.getInputStream(entry).use { stream -> stream.readBytes() } }
        }

    private val NESTED_ARCHIVES: List<String> = listOf(".jar", ".war", ".ear", ".zip")

    /**
     * Whether a file or an archive entry called [name] is a class file for every reader of this scan: its name ends in `.class` in ANY
     * case, Unicode case folding included. A class loader that reads a directory on a case-insensitive file system (macOS and Windows by
     * default) loads `x/Hidden.CLASS` as `x.Hidden`, and Gradle packs such a file from the resources verbatim, so a case-sensitive test
     * would skip a class that runs. Such a file system folds more than ASCII: APFS treats `Hidden.claſs` (U+017F, the long s),
     * `Hidden.claß` (`ß`) and `Hidden.claẞ` (U+1E9E) as `Hidden.class` (observed on APFS in breaker round 16), and `lowercase()` alone
     * leaves all three as they are. The name is therefore folded the way those file systems do, to lower case, to upper case and to
     * lower case again (the long s becomes `S` and then `s`, `ß` and `ẞ` become `ss`). A file that is named like a class and is not one
     * fails the parse loudly (`MalformedClass`) instead of being skipped. The fold is the simple one of the JDK's `String` case
     * mapping, not every folding a file system could implement: a file system with another table is a limit of this defence in depth
     * (decisions D-36). The nested archives above are matched in lower case.
     */
    fun isClassFileName(name: String): Boolean = name.lowercase().uppercase().lowercase().endsWith(CLASS_SUFFIX)

    private const val CLASS_SUFFIX: String = ".class"

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
