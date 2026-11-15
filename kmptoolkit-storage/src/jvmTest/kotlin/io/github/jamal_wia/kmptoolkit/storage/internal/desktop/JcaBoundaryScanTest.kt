package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Group sto-desktop, round 9: a new direct JCA use in the storage module's JVM code fails here (baseline zero).
 *
 * The rule (decisions D-P12): `Jca.kt` is the ONE file of `kmptoolkit-storage/src/jvmMain` that names `java.security`,
 * `javax.crypto`, `java.util.Base64` or one of their types, because that file holds the three rules every provider call needs
 * (any exception typed, "wrong key" decided in one place, an `Error` never caught). A new use elsewhere is the next audit round
 * waiting to happen. The scan reads the source files with comments blanked (a KDoc may talk about `SecureRandom`) and looks for
 * the package names and for the type names as whole words, in imports and fully qualified uses alike, so an aliased or wildcard
 * import cannot hide a use.
 *
 * Limits, stated: it scans `jvmMain` of this module only (the Android store's Keystore code and the other modules have their own
 * rules), it is lexical (a use through reflection by a string that does not spell a package name, or through a type that is not
 * in the list, is not seen), and it needs the module's source directory: it is found from the test's working directory (the
 * module's directory under Gradle, the repository root under an IDE) and the test FAILS when it cannot be found, so a moved
 * working directory cannot turn the scan into a pass. Controls: the tree has files, the boundary file is flagged by the same scan
 * (so the scan really sees JCA code), and the scanner's own rules are exercised on small sources.
 */
class JcaBoundaryScanTest {

    private fun sourceRoot(): File {
        val working = File(System.getProperty("user.dir") ?: "")
        val candidates: List<File> = listOf(
            File(File(File(working, "src"), "jvmMain"), "kotlin"),
            File(File(File(File(working, "kmptoolkit-storage"), "src"), "jvmMain"), "kotlin"),
        )
        return candidates.firstOrNull { candidate: File -> candidate.isDirectory }
            ?: error("the storage module's jvmMain sources were not found from ${working.path}: the boundary scan cannot run (it must not pass silently)")
    }

    private fun sources(): List<File> = sourceRoot().walkTopDown().filter { file: File -> file.isFile && file.extension == "kt" }.toList()

    private fun isBoundary(file: File): Boolean = file.name == "Jca.kt" && file.parentFile?.name == "desktop"

    @Test
    fun `no file but the boundary uses the JCA`() {
        val all: List<File> = sources()
        assertTrue(all.size > 20, "control: the scan sees the module's sources (${all.size} files)")
        val boundary: List<File> = all.filter(::isBoundary)
        assertEquals(1, boundary.size, "control: exactly one boundary file: $boundary")
        assertTrue(scan(boundary.single().readText()).isNotEmpty(), "control: the same scan flags the boundary file, so it sees JCA code")
        val offenders: List<String> = all.filterNot(::isBoundary).flatMap { file: File ->
            scan(file.readText()).map { hit: String -> "${file.name}: $hit" }
        }
        assertEquals(emptyList(), offenders, "a direct JCA use outside Jca.kt: route it through the boundary (D-P12)")
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

    private companion object {
        val PACKAGES: List<String> = listOf("java.security", "javax.crypto", "java.util.Base64", "kotlin.io.encoding")
        val TYPES: List<String> = listOf(
            "MessageDigest", "SecureRandom", "SecretKeyFactory", "KeyGenerator", "KeyPairGenerator", "KeyFactory", "KeyAgreement",
            "Signature", "Cipher", "Mac", "Base64", "SecretKeySpec", "PBEKeySpec", "GCMParameterSpec",
        )
        val PATTERNS: List<Pair<String, Regex>> =
            PACKAGES.map { name: String -> name to Regex("(?<![\\w.])" + Regex.escape(name) + "(?![\\w])") } +
                TYPES.map { name: String -> name to Regex("(?<![\\w])" + name + "(?![\\w])") }

        /** The JCA names that the code of [source] (comments blanked) uses, one entry per distinct name: `"<name> (line n)"`. */
        fun scan(source: String): List<String> {
            val code: String = blankComments(source)
            return PATTERNS.mapNotNull { (name: String, pattern: Regex) ->
                pattern.find(code)?.let { match: MatchResult -> "$name (line ${code.substring(0, match.range.first).count { it == '\n' } + 1})" }
            }
        }

        /** [source] with every comment replaced by spaces (newlines kept), honouring string, raw string and character literals and nested block comments. */
        fun blankComments(source: String): String {
            val out = StringBuilder(source.length)
            var i = 0
            while (i < source.length) {
                val c: Char = source[i]
                val next: Char = if (i + 1 < source.length) source[i + 1] else ' '
                when {
                    c == '/' && next == '/' -> {
                        while (i < source.length && source[i] != '\n') { out.append(' '); i++ }
                    }
                    c == '/' && next == '*' -> {
                        var depth = 0
                        while (i < source.length) {
                            if (source.startsWith("/*", i)) { depth++; out.append("  "); i += 2 }
                            else if (source.startsWith("*/", i)) { depth--; out.append("  "); i += 2; if (depth == 0) break }
                            else { out.append(if (source[i] == '\n') '\n' else ' '); i++ }
                        }
                    }
                    source.startsWith("\"\"\"", i) -> {
                        out.append("\"\"\""); i += 3
                        while (i < source.length && !source.startsWith("\"\"\"", i)) { out.append(source[i]); i++ }
                        if (i < source.length) { out.append("\"\"\""); i += 3 }
                    }
                    c == '"' -> {
                        out.append(c); i++
                        while (i < source.length && source[i] != '"' && source[i] != '\n') {
                            if (source[i] == '\\' && i + 1 < source.length) { out.append(source[i]); i++ }
                            out.append(source[i]); i++
                        }
                        if (i < source.length && source[i] == '"') { out.append('"'); i++ }
                    }
                    c == '\'' -> {
                        out.append(c); i++
                        while (i < source.length && source[i] != '\'' && source[i] != '\n') {
                            if (source[i] == '\\' && i + 1 < source.length) { out.append(source[i]); i++ }
                            out.append(source[i]); i++
                        }
                        if (i < source.length && source[i] == '\'') { out.append('\''); i++ }
                    }
                    else -> { out.append(c); i++ }
                }
            }
            return out.toString()
        }
    }
}
