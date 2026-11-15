package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Group sto-desktop, round 9: a new direct JCA use in the storage module's desktop (jvm) code fails here (baseline zero).
 *
 * The rule (decisions D-P12): `Jca.kt` is the ONE file of the jvm source sets of `kmptoolkit-storage/src` (`jvmMain`, `jvmFfm` where it exists, any other `jvm*` set that is not a test set) that names `java.security`,
 * `javax.crypto`, `java.util.Base64` or one of their types, because that file holds the three rules every provider call needs
 * (any exception typed, "wrong key" decided in one place, an `Error` never caught). A new use elsewhere is the next audit round
 * waiting to happen. The scan reads the source files with comments blanked (a KDoc may talk about `SecureRandom`) and looks for
 * the package names and for the type names as whole words, in imports and fully qualified uses alike, so an aliased or wildcard
 * import cannot hide a use. Three evasions that round 10 closed (breaker A8-m2): a string TEMPLATE (`"${"//"}"`) whose nested
 * literal looked like the start of a comment (the lexer now lexes a template's body as code, recursively), the boundary's own
 * `typealias` (every alias `Jca.kt` exports is a type name: constructing it or calling its statics outside the boundary is a
 * direct provider call; naming it as a type, which is how `EnvelopeCipher` accepts an injectable random source, is not), and a
 * back-quoted package segment (back-quotes are deleted before matching).
 *
 * Since round 11 this is the SECOND layer. The first is `JcaBytecodeScanTest`, which reads the constant pools of every class entry of the built jvm jar
 * and therefore is not fooled by quoting, back-quotes, spacing, aliases, constructor references, a call on an instance the boundary
 * handed out (a call on the random source through its supertypes `java.util.Random`, `RandomGenerator` or `asKotlinRandom()` is
 * flagged too: breaker A10-m2) or a class that is named like the boundary but is not it (breaker A10-m1). It does not see
 * reflection by a string built at run time, nor a helper of another module that reaches the JCA inside its own code (`ClassFileScan`
 * lists these limits). Breaker A9-m2 was the third round in a row of lexer shapes. This layer stays for what the bytecode cannot say:
 * it reads the source files themselves, so it sees an unused import or a file the compiler never reached, and it is the reason the
 * lexer is kept correct for the shapes the breakers found (raw strings closing at the last three quotes of a run, back-quoted names as
 * one token, whitespace around the dots of a qualified name, `::Alias`, a second alias and an import alias).
 *
 * Limits, stated: it scans EVERY `.kt` and `.java` file under every directory of this module's `src/` whose name starts with `jvm` and does
 * not end with `Test` (found by walking `src/`, not listed: `jvmMain`, `jvmFfm` wherever a branch has it, and a source set that does not
 * exist yet; whatever the directory below is called, so `src/jvmMain/java` and a `.kt` file in it are read; a Java file is lexed as Java:
 * block comments do not nest, a text block closes at its first three quotes, `${` is no template). `commonMain` cannot name a JDK type and
 * the bytecode scan reads its classes; the Android store's Keystore code and the other modules have their own rules. It is lexical (a use
 * through reflection by a string that does not spell a package name, or through a type that is not in the list, is not seen), and it needs
 * the module's source directory: it is found from the test's working directory (the
 * module's directory under Gradle, the repository root under an IDE) and the test FAILS when it cannot be found, so a moved
 * working directory cannot turn the scan into a pass. Controls: the tree has files, the boundary file is flagged by the same scan
 * (so the scan really sees JCA code), and the scanner's own rules are exercised on small sources.
 */
class JcaBoundaryScanTest {

    /** The module's `src` directory, found from the working directory (the module's directory under Gradle, the repository root under an IDE). */
    private fun moduleSrc(): File {
        val working = File(System.getProperty("user.dir") ?: "")
        val candidates: List<File> = listOf(File(working, "src"), File(File(working, "kmptoolkit-storage"), "src"))
        return candidates.firstOrNull { candidate: File -> File(File(candidate, "jvmMain"), "kotlin").isDirectory }
            ?: error("the storage module's jvmMain sources were not found from ${working.path}: the boundary scan cannot run (it must not pass silently)")
    }

    /**
     * The source roots of the desktop code that is compiled into the jvm jar, FOUND BY WALKING `src/`, never by naming a source set:
     * every directory directly under it whose name starts with `jvm` (any case) and does not end with `Test` (a test source set is not
     * shipped): `jvmMain` and, where a branch has it, `jvmFfm`, and a source set that does not exist yet. Four rounds in a row a scan that
     * listed the layouts it knew (the `kotlin` directory of two source sets) missed one that was shipped. `commonMain` cannot name a
     * JDK type and is covered by the bytecode scan.
     */
    private fun sourceRoots(moduleSrc: File): List<File> =
        moduleSrc.listFiles().orEmpty()
            .filter { directory: File -> directory.isDirectory && directory.name.lowercase().startsWith("jvm") && !directory.name.lowercase().endsWith("test") }
            .sortedBy { directory: File -> directory.name }

    /** Every `.kt` and every `.java` file under [roots], whatever the directory below the source set is called (`kotlin`, `java`, anything). */
    private fun sources(roots: List<File> = sourceRoots(moduleSrc())): List<File> =
        roots.flatMap { root: File -> root.walkTopDown().filter { file: File -> file.isFile && file.extension in SOURCE_EXTENSIONS }.toList() }

    /** The JCA names that [file] uses: a Java file is lexed as Java, everything else as Kotlin. */
    private fun scanFile(file: File): List<String> = if (file.extension == "java") scan(file.readText(), java = true) else scan(file.readText())

    private fun isBoundary(file: File): Boolean = file.name == "Jca.kt" && file.parentFile?.name == "desktop"

    @Test
    fun `no file but the boundary uses the JCA`() {
        val all: List<File> = sources()
        assertTrue(all.size > 20, "control: the scan sees the module's sources (${all.size} files)")
        val boundary: List<File> = all.filter(::isBoundary)
        assertEquals(1, boundary.size, "control: exactly one boundary file: $boundary")
        assertTrue(scanFile(boundary.single()).isNotEmpty(), "control: the same scan flags the boundary file, so it sees JCA code")
        val offenders: List<String> = all.filterNot(::isBoundary).flatMap { file: File ->
            scanFile(file).map { hit: String -> "${file.name}: $hit" }
        }
        assertEquals(emptyList(), offenders, "a direct JCA use outside Jca.kt: route it through the boundary (D-P12)")
    }

    @Test
    fun `the source roots are found by walking src and a use in any language or source set of the jvm jar is flagged`() {
        val real: File = moduleSrc()
        val realRoots: List<String> = sourceRoots(real).map { root: File -> root.name }
        assertTrue(File(real, "jvmTest").isDirectory, "control: this test's own source set exists next to the others")
        assertTrue("jvmMain" in realRoots, "jvmMain is a root: $realRoots")
        assertTrue(realRoots.none { name: String -> name.endsWith("Test") }, "no test source set is a root: $realRoots")
        assertEquals(realRoots.size, sourceRoots(real).size, "control: the roots are listed once")
        val root: File = Files.createTempDirectory("kmptoolkit-jca-roots").toFile()
        try {
            fun write(text: String, vararg segments: String) {
                val file: File = segments.fold(root) { directory: File, segment: String -> File(directory, segment) }
                file.parentFile.mkdirs()
                file.writeText(text)
            }
            val kotlin = "import javax.crypto.Cipher\nclass K"
            val java = "import java.security.MessageDigest;\nclass J {}"
            write("class A", "jvmMain", "kotlin", "A.kt")
            assertEquals(listOf("jvmMain"), sourceRoots(root).map { it.name }, "a branch without jvmFfm is not an error")
            assertEquals(emptyList(), sources(sourceRoots(root)).filter { file: File -> scanFile(file).isNotEmpty() }, "control: a clean tree has no hit")
            write(java, "jvmMain", "java", "p", "JavaInMain.java")
            write(kotlin, "jvmMain", "java", "p", "KotlinInJavaDir.kt")
            write(kotlin, "jvmMain", "kotlin", "p", "KotlinInMain.kt")
            write(kotlin, "jvmFfm", "kotlin", "p", "KotlinInFfm.kt")
            write(java, "jvmFfm", "java", "p", "JavaInFfm.java")
            write(kotlin, "jvmBrandNew", "any", "depth", "here", "KotlinInNewSet.kt")
            write(java, "jvmBrandNew", "other", "JavaInNewSet.java")
            // Not shipped in the jvm jar, or not a source file: never a hit.
            write(kotlin, "jvmTest", "kotlin", "p", "InTest.kt")
            write(java, "jvmFfmTest", "java", "p", "InFfmTest.java")
            write(kotlin, "commonMain", "kotlin", "p", "InCommon.kt")
            write(kotlin, "androidMain", "kotlin", "p", "InAndroid.kt")
            write("import javax.crypto.Cipher", "jvmMain", "resources", "notes.txt")
            assertEquals(listOf("jvmBrandNew", "jvmFfm", "jvmMain"), sourceRoots(root).map { it.name }, "every jvm source set that is not a test set is a root")
            assertEquals(
                listOf(
                    "JavaInFfm.java", "JavaInMain.java", "JavaInNewSet.java", "KotlinInFfm.kt", "KotlinInMain.kt", "KotlinInNewSet.kt", "KotlinInJavaDir.kt",
                ).sorted(),
                sources(sourceRoots(root)).filter { file: File -> scanFile(file).isNotEmpty() }.map { file: File -> file.name }.sorted(),
                "a JCA use in a Java source, in a Kotlin source of a Java directory, in jvmFfm and in a source set no rule knows is found; tests and other targets are not read",
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a Java source is lexed as Java so a comment or a string of Java never hides a use`() {
        val use = "MessageDigest md = java.security.MessageDigest.getInstance(\"SHA-256\");"
        assertTrue(scan("/* a /* b */ $use", java = true).isNotEmpty(), "Java block comments do not nest: the first close ends the comment")
        val note = "// MessageDigest is only mentioned in this comment\nint z = 0;"
        assertEquals(emptyList(), scan("String s = \"\${\"; $note", java = true), "a dollar and a brace in a Java string are no template: the comment after the string is a comment")
        assertTrue(scan("String s = \"\${\"; $note").isNotEmpty(), "control: the Kotlin lexer reads the same text as a template, whose nested string swallows the comment")
        assertTrue(scan("String t = \"\"\"\n// not a comment\n\"\"\";\n$use", java = true).isNotEmpty(), "a use after a text block")
        assertEquals(emptyList(), scan("String e = \"\"\"\n \\\"\"\" \n\"\"\"; $note", java = true), "an escaped quote run does not close a Java text block")
        assertTrue(scan("String e = \"\"\"\n \\\"\"\" \n\"\"\"; $note").isNotEmpty(), "control: the Kotlin lexer closes the raw string at the escaped run and then reads the comment as text")
        assertTrue(scan("char q = '\"'; $use", java = true).isNotEmpty(), "a quote character")
        assertTrue(scan("import java.security.*;", java = true).isNotEmpty(), "a wildcard import")
        assertTrue(scan("var d = java . security . MessageDigest.getInstance(\"x\");", java = true).isNotEmpty(), "spaces around the dots")
        assertEquals(emptyList(), scan("// uses SecureRandom\n/* javax.crypto.Cipher */\n/** MessageDigest */\nint x = 1;", java = true), "comments are ignored")
        // The same text read as Kotlin hides the use: the mode is what makes the Java scan sound.
        assertEquals(emptyList(), scan("/* a /* b */ $use"), "control: Kotlin nests block comments, so this text is one comment to the Kotlin lexer")
    }

    @Test
    fun `the scanner flags what it must and ignores what it must`() {
        assertTrue(scan("import java.security.MessageDigest\n").any { hit: String -> hit.startsWith("java.security") }, "a plain import")
        assertTrue(scan("import java.security.MessageDigest as Digest\nval d = Digest.getInstance(\"x\")").isNotEmpty(), "an aliased import")
        assertTrue(scan("import javax.crypto.*\n").isNotEmpty(), "a wildcard import")
        assertTrue(scan("val c = javax.crypto.Cipher.getInstance(\"AES\")").isNotEmpty(), "a fully qualified use")
        assertTrue(scan("val r = Secure" + "Random.getInstanceStrong()").isNotEmpty(), "a bare type name")
        assertTrue(scan("val s = \"a//b\"; val c = java.util.Base64.getEncoder()").isNotEmpty(), "a use after a string that holds two slashes")
        assertTrue(scan("val s = \"\"\"\n// not a comment\n\"\"\"\nval m = MessageDigest.getInstance(\"SHA-256\")").isNotEmpty(), "a use after a raw string")
        assertTrue(scan("val q = '\"'\nval m = MessageDigest.getInstance(\"SHA-256\")").isNotEmpty(), "a use after a quote character")
        assertEquals(emptyList(), scan("// uses SecureRandom\n/* javax.crypto.Cipher */\n/** a KDoc about MessageDigest and java.security */\nval x = 1"), "comments are ignored")
        assertEquals(emptyList(), scan("/* outer /* nested SecureRandom */ still a comment Cipher */ val x = 1"), "nested block comments are ignored")
        assertEquals(emptyList(), scan("class EnvelopeCipher; val mac = MacBackend(); val a = Jca.sha256(b)"), "longer identifiers that contain a type name are not uses")
    }

    @Test
    fun `a string template is lexed as code so a nested literal never hides what follows`() {
        val use = "val d = java.security.MessageDigest.getInstance(\"SHA-256\")"
        assertTrue(scan("val p = \"\${\"//\"}\"; $use").isNotEmpty(), "a nested `//` literal in a template, use after it on the same line")
        assertTrue(scan("val o = \"\${\"/*\"}\"\n$use\nval c = \"*/\"").isNotEmpty(), "a nested `/*` literal in a template, use on the next line")
        assertTrue(scan("val r = \"\"\"\${\"//\"}\"\"\"; $use").isNotEmpty(), "the same inside a raw string")
        assertTrue(scan("val n = \"\${\"\${\"//\"}\"}\"; $use").isNotEmpty(), "a template nested in a template")
        assertTrue(scan("val b = \"\${ list.map { it + \"//\" } }\"; $use").isNotEmpty(), "braces inside a template body do not end it early")
        assertTrue(scan("val c = \"\${ '\"' }\"; $use").isNotEmpty(), "a quote character inside a template body")
        assertTrue(scan("val e = \"\${ 1 /* \\\" */ }\"; $use").isNotEmpty(), "a comment inside a template body")
        // What is inside a template's code is code: a JCA use there is a use, and a comment there is a comment.
        assertTrue(scan("val u = \"\${ java.security.MessageDigest.getInstance(\"x\") }\"").isNotEmpty(), "a use inside a template body")
        assertEquals(emptyList(), scan("val u = \"\${ 1 /* java.security.MessageDigest */ }\"\nval v = \"\${ 2 // javax.crypto.Cipher\n}\""), "a comment inside a template body")
        assertEquals(emptyList(), scan("val a = \"\$ not a template\"; val x = 1"), "a lone dollar sign is not a template")
    }

    @Test
    fun `an alias the boundary exports is a use when constructed or called but not as a type`() {
        assertTrue(BOUNDARY_ALIASES.isNotEmpty(), "control: the boundary exports an alias")
        for (alias: String in BOUNDARY_ALIASES) {
            assertTrue(scan("val r = $alias()").isNotEmpty(), "$alias constructed")
            assertTrue(scan("val r = $alias.getInstanceStrong()").isNotEmpty(), "$alias with a static call")
            assertTrue(scan("val f = $alias::class").isNotEmpty(), "$alias as a class reference")
            assertEquals(emptyList(), scan("class C(private val random: $alias = Jca.newRandom())"), "$alias as a parameter type is how a source is injected")
            assertEquals(emptyList(), scan("val r: $alias = Jca.newRandom()"), "$alias as a declared type")
            assertEquals(emptyList(), scan("fun f(random: $alias): ByteArray = Jca.randomBytes(random, 1)"), "$alias in a signature")
        }
    }

    @Test
    fun `a back-quoted package segment or type does not hide a use`() {
        assertTrue(scan("import java.`security`.KeyStore\nval k = KeyStore.getInstance(\"PKCS12\")").isNotEmpty(), "a back-quoted package segment, a type that is in no list")
        assertTrue(scan("import `javax`.crypto.*").isNotEmpty(), "a back-quoted first segment")
        assertTrue(scan("val k = java.`util`.`Base64`.getEncoder()").isNotEmpty(), "back-quoted segments of a fully qualified use")
        assertTrue(scan("val c = `Cipher`.getInstance(\"AES\")").isNotEmpty(), "a back-quoted type name")
        assertTrue(scan("import java.util.`Base64`").isNotEmpty(), "a back-quoted Base64")
    }

    @Test
    fun `the lexer follows Kotlin's own rules for raw string ends, back-quoted names, spaced names and aliases`() {
        val use = "val d = java.security.MessageDigest.getInstance(\"SHA-256\")"
        // A raw string closes at the last three quotes of a run: with four quotes the fourth is content, not the start of a string.
        assertTrue(scan("val a = \"\"\"say \"hi\"\"\"\"; val u = \"https://example.org\"; $use").isNotEmpty(), "four closing quotes")
        assertTrue(scan("val a = \"\"\"x\"\"\"\"\"\"\nval b = \"\"\" /* \"\"\"\n$use\nval c = \"\"\" */ \"\"\"\n").isNotEmpty(), "six closing quotes")
        assertEquals(emptyList(), scan("val a = \"\"\"\"\"\"; val n = 1"), "an empty raw string")
        // A back-quoted name is one token: an apostrophe, a quote or two slashes in it open nothing.
        assertTrue(scan("fun `it's done`(): String = \"'//\"; $use").isNotEmpty(), "an apostrophe in a back-quoted name")
        assertTrue(scan("fun `say \"hi\"`() = 1\n$use").isNotEmpty(), "a quote in a back-quoted name")
        // Whitespace, line breaks and comments around the dots of a qualified name.
        assertTrue(scan("val k = java . security . KeyStore.getInstance(\"PKCS12\")").isNotEmpty(), "spaces around the dots")
        assertTrue(scan("val k = java\n    .security\n    .KeyStore.getInstance(\"PKCS12\")").isNotEmpty(), "line breaks around the dots")
        assertTrue(scan("val k = java./* x */security.KeyStore.getInstance(\"PKCS12\")").isNotEmpty(), "a comment inside the name")
        assertTrue(scan("val p = javax .crypto .CipherInputStream(input, cipher)").isNotEmpty(), "a type that is in no list")
        assertTrue(scan("val k = java\n    .security\n    .KeyStore.getInstance(\"PKCS12\")").single().contains("line 1"), "the line is the line of the first segment")
        for (alias: String in BOUNDARY_ALIASES) {
            assertTrue(scan("val make: () -> Any = ::$alias").isNotEmpty(), "$alias as a constructor reference")
            assertTrue(scan("val make = :: $alias").isNotEmpty(), "$alias as a spaced constructor reference")
            assertTrue(scan("private typealias R = $alias\nval r = R()").isNotEmpty(), "a second alias")
            assertTrue(scan("typealias Box<T> = $alias").isNotEmpty(), "a generic second alias")
            assertTrue(scan("import io.github.x.$alias as R\nval r = R()").isNotEmpty(), "an import alias")
            assertEquals(emptyList(), scan("val x: List<$alias> = emptyList()"), "$alias as a type argument")
        }
    }

    private companion object {
        /** The source files of the jvm source sets that the scan reads. */
        val SOURCE_EXTENSIONS: Set<String> = setOf("kt", "java")

        val PACKAGES: List<String> = listOf("java.security", "javax.crypto", "java.util.Base64", "kotlin.io.encoding")
        val TYPES: List<String> = listOf(
            "MessageDigest", "SecureRandom", "SecretKeyFactory", "KeyGenerator", "KeyPairGenerator", "KeyFactory", "KeyAgreement",
            "Signature", "Cipher", "Mac", "Base64", "SecretKeySpec", "PBEKeySpec", "GCMParameterSpec",
        )
        val PATTERNS: List<Pair<String, Regex>> =
            PACKAGES.map { name: String ->
                name to Regex("(?<![\\w.])" + name.split('.').joinToString("\\s*\\.\\s*") { segment: String -> Regex.escape(segment) } + "(?![\\w])")
            } +
                TYPES.map { name: String -> name to Regex("(?<![\\w])" + name + "(?![\\w])") }

        /**
         * The names of every `typealias` that the boundary file exports (read from `Jca.kt` itself, so a new alias is covered at
         * once). The scan fails loudly, like the rest of the test, when the boundary file cannot be found.
         */
        val BOUNDARY_ALIASES: List<String> by lazy {
            val working = File(System.getProperty("user.dir") ?: "")
            val root: File = listOf(
                File(File(File(working, "src"), "jvmMain"), "kotlin"),
                File(File(File(File(working, "kmptoolkit-storage"), "src"), "jvmMain"), "kotlin"),
            ).firstOrNull { candidate: File -> candidate.isDirectory }
                ?: error("the storage module's jvmMain sources were not found from ${working.path}")
            val boundary: File = root.walkTopDown().single { file: File -> file.name == "Jca.kt" && file.parentFile?.name == "desktop" }
            Regex("""typealias\s+(\w+)\s*=""").findAll(boundary.readText()).map { match: MatchResult -> match.groupValues[1] }.toList()
        }

        /**
         * An exported alias is a use when it is constructed or its statics are called (`Alias(`, `Alias.`, `Alias::`); as a type
         * annotation it names no provider call.
         */
        fun aliasPatterns(): List<Pair<String, Regex>> = BOUNDARY_ALIASES.flatMap { name: String ->
            val escaped: String = Regex.escape(name)
            listOf(
                name to Regex("(?<![\\w.])" + escaped + "\\s*(?:\\(|\\.|::)"),
                // A constructor reference (`::JcaRandom`), a second alias of the alias, and an import alias are the same construction by another name.
                name to Regex("::\\s*" + escaped + "(?![\\w])"),
                name to Regex("typealias\\s+\\w+(?:\\s*<[^>]*>)?\\s*=\\s*" + escaped + "(?![\\w])"),
                name to Regex("import\\s+[\\w.\\s]*\\.\\s*" + escaped + "\\s+as\\s+\\w+"),
            )
        }

        /** The JCA names that the code of [source] (comments blanked, back-quotes deleted) uses, one entry per distinct name: `"<name> (line n)"`. */
        fun scan(source: String): List<String> = scan(source, java = false)

        /** The same for the text of a Java file ([java]: no nested block comments, no templates, no back-quoted names, text blocks close at the first three quotes). */
        fun scan(source: String, java: Boolean): List<String> {
            val code: String = Lexer(source, java).run()
            return (PATTERNS + aliasPatterns()).mapNotNull { (name: String, pattern: Regex) ->
                pattern.find(code)?.let { match: MatchResult -> "$name (line ${code.substring(0, match.range.first).count { it == '\n' } + 1})" }
            }
        }

        /**
         * [source] with every comment replaced by spaces (newlines kept). Strings, raw strings and character literals are kept as
         * they are, nested block comments are honoured, and the body of a `${ ... }` template inside a string or raw string is
         * lexed as CODE (with its own nested strings, comments and braces), so a nested literal such as `"${"//"}"` cannot end the
         * outer string early and turn real code into a "comment".
         */
        fun blankComments(source: String): String = Lexer(source, java = false).run()

        private class Lexer(private val source: String, private val java: Boolean) {
            private val out = StringBuilder(source.length)
            private var i = 0

            fun run(): String {
                code(inTemplate = false)
                return out.toString()
            }

            /** Lexes code up to the end, or in a template up to and including the `}` that closes it. */
            private fun code(inTemplate: Boolean) {
                var depth = 0
                while (i < source.length) {
                    val c: Char = source[i]
                    val next: Char = if (i + 1 < source.length) source[i + 1] else ' '
                    when {
                        c == '/' && next == '/' -> {
                            while (i < source.length && source[i] != '\n') { out.append(' '); i++ }
                        }
                        c == '/' && next == '*' -> blockComment()
                        source.startsWith("\"\"\"", i) -> if (java) textBlock() else rawString()
                        c == '"' -> string()
                        c == '`' && !java -> backQuoted()
                        c == '\'' -> charLiteral()
                        inTemplate && c == '{' -> { depth++; out.append(c); i++ }
                        inTemplate && c == '}' -> {
                            out.append(c); i++
                            if (depth == 0) return
                            depth--
                        }
                        else -> { out.append(c); i++ }
                    }
                }
            }

            private fun blockComment() {
                var depth = 0
                while (i < source.length) {
                    if (source.startsWith("/*", i) && (!java || depth == 0)) { depth++; out.append("  "); i += 2 }
                    else if (source.startsWith("*/", i)) { depth--; out.append("  "); i += 2; if (depth == 0) return }
                    else { out.append(if (source[i] == '\n') '\n' else ' '); i++ }
                }
            }

            private fun template() {
                out.append("\${"); i += 2
                code(inTemplate = true)
            }

            /**
             * A raw string closes at the LAST three quotes of a run of quotes (`"""x""""` is the text `x"`): the quotes of a longer run
             * before the last three are content.
             */
            private fun rawString() {
                out.append("\"\"\""); i += 3
                while (i < source.length) {
                    if (source[i] == '"') {
                        var run = 0
                        while (i + run < source.length && source[i + run] == '"') run++
                        if (run >= 3) {
                            repeat(run) { out.append('"') }
                            i += run
                            return
                        }
                        repeat(run) { out.append('"') }
                        i += run
                    } else if (source.startsWith("\${", i)) {
                        template()
                    } else {
                        out.append(source[i]); i++
                    }
                }
            }

            /**
             * A Java text block closes at the FIRST three quotes that no backslash escapes (the backslash takes the next character with
             * it), and holds no template.
             */
            private fun textBlock() {
                out.append("\"\"\""); i += 3
                while (i < source.length) {
                    when {
                        source.startsWith("\"\"\"", i) -> { out.append("\"\"\""); i += 3; return }
                        source[i] == '\\' && i + 1 < source.length -> { out.append(source[i]); out.append(source[i + 1]); i += 2 }
                        else -> { out.append(source[i]); i++ }
                    }
                }
            }

            /** A back-quoted name is one token (it may hold an apostrophe, a quote or two slashes): emitted without its back-quotes. */
            private fun backQuoted() {
                i++
                while (i < source.length && source[i] != '`' && source[i] != '\n') { out.append(source[i]); i++ }
                if (i < source.length && source[i] == '`') i++
            }

            private fun string() {
                out.append('"'); i++
                while (i < source.length && source[i] != '"' && source[i] != '\n') {
                    when {
                        source[i] == '\\' && i + 1 < source.length -> { out.append(source[i]); out.append(source[i + 1]); i += 2 }
                        !java && source.startsWith("\${", i) -> template()
                        else -> { out.append(source[i]); i++ }
                    }
                }
                if (i < source.length && source[i] == '"') { out.append('"'); i++ }
            }

            private fun charLiteral() {
                out.append('\''); i++
                while (i < source.length && source[i] != '\'' && source[i] != '\n') {
                    if (source[i] == '\\' && i + 1 < source.length) { out.append(source[i]); i++ }
                    out.append(source[i]); i++
                }
                if (i < source.length && source[i] == '\'') { out.append('\''); i++ }
            }
        }
    }
}
