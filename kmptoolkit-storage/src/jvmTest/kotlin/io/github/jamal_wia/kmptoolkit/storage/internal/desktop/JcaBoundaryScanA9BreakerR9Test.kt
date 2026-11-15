package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Compile proof for the second-alias evasion: an alias of the boundary's alias, declared outside `Jca.kt`. */
private typealias A9SecondAlias = JcaRandom

/** Compile proof for the back-quote evasion: a back-quoted name may hold an apostrophe. */
private fun `it's a9`(): String = "'//"

/**
 * Group sto-desktop, phase A breaker round 9 (decisions.md D-23), part A9: the soundness of `JcaBoundaryScanTest` after its round-10
 * repair (A8-m2: string templates lexed as code, the boundary's typealiases, back-quotes). Its KDoc claims the scan cannot be
 * fooled by a comment ("a nested literal ... cannot end the outer string early and turn real code into a 'comment'"), that fully
 * qualified uses are found ("in imports and fully qualified uses alike"), and that constructing a boundary alias or calling its
 * statics outside the boundary is flagged; its stated limits are reflection by a string that does not spell a package name, a type
 * that is not in its list, and calls on a `JcaRandom` INSTANCE obtained from `Jca`.
 *
 * Every red case below is real Kotlin that compiles (each shape is compiled in this file, outside the scanned tree, and its value
 * asserted) and makes a direct JCA call that the scanner reports as clean, without being one of the stated limits:
 *
 * - a raw string that ends in a run of more than three quotes (`"""x""""`: Kotlin's closing delimiter is the LAST three quotes of
 *   the run, the scanner's lexer closes at the first three): the lexer's string state flips, a later string's `//` becomes a
 *   "comment" to the end of the line, or (a run of six) a later raw string's `/*` blanks whole lines up to a `*/`;
 * - a back-quoted name that holds an apostrophe (common in names written as sentences): the lexer opens a character literal at
 *   the apostrophe, its state flips at the next `'`, and a `//` inside a later string blanks the rest of the line;
 * - whitespace, a line break or a comment between the segments of a fully qualified name (`java . security . KeyStore`), legal
 *   Kotlin, with a type outside the 14-name list: the package patterns match only the dense spelling;
 * - the boundary's alias reached without its name being followed by `(`, `.` or `::`: a constructor reference `::JcaRandom`, and an
 *   alias of the alias (`typealias R = JcaRandom`) declared outside `Jca.kt` and then constructed.
 *
 * Mutation by reading: a lexer that closes a raw string at the last three quotes of a run and skips back-quoted names as one token,
 * whitespace and comments removed around `.` before matching (or a token-level match), and the alias flagged in a constructor
 * reference and in any `typealias` outside the boundary turn these red tests green. A bytecode scan of the compiled `jvmMain` classes
 * (constant-pool class references to `java/security/`, `javax/crypto/`, `java/util/Base64`) closes the whole lexical class at once.
 *
 * The scanner is reached through its own `scan(String): List<*>` (by reflection on `JcaBoundaryScanTest` and its nested classes, as
 * the A8 test does), so a fix may restructure it as long as such a method stays there.
 */
class JcaBoundaryScanA9BreakerR9Test {

    private fun scanner(): Pair<Any?, Method> {
        val classes: List<Class<*>> = listOf(JcaBoundaryScanTest::class.java) + JcaBoundaryScanTest::class.java.declaredClasses
        for (owner: Class<*> in classes) {
            val method: Method = owner.declaredMethods.firstOrNull { candidate: Method ->
                candidate.name == "scan" && candidate.parameterTypes.contentEquals(arrayOf(String::class.java)) &&
                    List::class.java.isAssignableFrom(candidate.returnType)
            } ?: continue
            method.isAccessible = true
            if (Modifier.isStatic(method.modifiers)) return null to method
            val instance: Any = if (owner == JcaBoundaryScanTest::class.java) {
                JcaBoundaryScanTest()
            } else {
                JcaBoundaryScanTest::class.java.declaredFields.firstOrNull { it.type == owner }?.apply { isAccessible = true }?.get(null)
                    ?: owner.getDeclaredField("INSTANCE").apply { isAccessible = true }.get(null)
            }
            return instance to method
        }
        error("JcaBoundaryScanTest has no scan(String) method in the class or its nested classes")
    }

    private fun scan(source: String): List<*> {
        val (instance: Any?, method: Method) = scanner()
        return method.invoke(instance, source) as List<*>
    }

    private val use = "val d = java.security.MessageDigest.getInstance(\"SHA-256\")"

    @Test
    fun `control - the scanner flags a plain use and the dense spellings of every shape below`() {
        assertTrue(scan(use).isNotEmpty(), "control: a plain fully qualified use")
        assertTrue(scan("val k = java.security.KeyStore.getDefaultType()").isNotEmpty(), "control: the dense spelling of KeyStore")
        assertTrue(scan("val r = JcaRandom()").isNotEmpty(), "control: the alias constructed by name")
        assertEquals(emptyList<Any?>(), scan("val r = \"\"\"x\"\"\"\"; val n = 1"), "control: a raw string with a quote at its end and nothing after it")
    }

    @Test
    fun `a raw string that ends in a run of more than three quotes never turns later code into a comment`() {
        // Compile proof: the closing delimiter is the last three quotes of the run.
        assertEquals("x\"", """x"""", "control: four quotes close a raw string whose content ends in one quote")
        assertEquals("x\"\"\"", """x"""""", "control: six quotes close a raw string whose content ends in three quotes")
        val sameLine = "val a = \"\"\"say \"hi\"\"\"\"; val u = \"https://example.org\"; $use"
        val nextLines = "val a = \"\"\"x\"\"\"\"\"\"\nval b = \"\"\" /* \"\"\"\n$use\nval c = \"\"\" */ \"\"\"\n"
        val missed: List<String> = listOfNotNull(
            "a raw string ending in a quote, then a URL string, hid the JCA use after it on the same line".takeIf { scan(sameLine).isEmpty() },
            "a raw string ending in three quotes made a later raw string's `/*` blank whole lines of code".takeIf { scan(nextLines).isEmpty() },
        )
        assertEquals(emptyList(), missed, "uses the scan reports as clean")
    }

    @Test
    fun `a back-quoted name with an apostrophe never turns later code into a comment`() {
        assertEquals("'//", `it's a9`(), "control: a back-quoted name may hold an apostrophe and the function compiles")
        val source = "fun `it's done`(): String = \"'//\"; $use"
        assertTrue(scan(source).isNotEmpty(), "an apostrophe in a back-quoted name opened a 'character literal' and a later `//` hid the use (scan: ${scan(source)})")
    }

    @Test
    fun `whitespace, a line break or a comment inside a fully qualified name does not hide a use`() {
        // Compile proof: Kotlin allows whitespace, line breaks and comments around the dots of a qualified expression.
        val spaced: String = java . security . KeyStore.getDefaultType()
        val broken: String = java
            .security
            .KeyStore.getDefaultType()
        val commented: String = java./* a9 */security.KeyStore.getDefaultType()
        assertTrue(spaced.isNotEmpty() && spaced == broken && broken == commented, "control: the three spellings compile to the same call")
        val sources: List<String> = listOf(
            "val k = java . security . KeyStore.getInstance(\"PKCS12\")",
            "val k = java\n    .security\n    .KeyStore.getInstance(\"PKCS12\")",
            "val k = java./* x */security.KeyStore.getInstance(\"PKCS12\")",
            "val p = javax .crypto .CipherInputStream(input, cipher)",
        )
        val missed: List<String> = sources.filter { source: String -> scan(source).isEmpty() }
        assertEquals(emptyList(), missed, "fully qualified JCA uses that the scan reports as clean")
    }

    @Test
    fun `the boundary's alias reached through a constructor reference or a second alias is a use`() {
        // Compile proof (nothing is constructed here): a constructor reference to the alias, and a constructor reference through an
        // alias of the alias; calling either makes the boundary's random source outside the boundary.
        val make: () -> JcaRandom = ::JcaRandom
        val second: () -> A9SecondAlias = ::A9SecondAlias
        assertTrue(make !== second, "control: both shapes compile")
        val sources: List<String> = listOf(
            "val make: () -> Any = ::JcaRandom\nval r = make()",
            "private typealias R = JcaRandom\nval r = R()",
        )
        val missed: List<String> = sources.filter { source: String -> scan(source).isEmpty() }
        assertEquals(emptyList(), missed, "constructions of the boundary's random source outside Jca.kt that the scan reports as clean")
    }
}
