package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.io.File
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Group sto-desktop, phase A breaker round 8 (decisions.md D-23), part A8: the soundness of `JcaBoundaryScanTest`, the source scan
 * with baseline zero that round 9 put behind the JCA boundary (D-P12: "nothing else may import `java.security`, `javax.crypto` or
 * `java.util.Base64` (a source-scan test with baseline zero enforces it)"). Its KDoc claims: "comments blanked", "the package names
 * and the type names as whole words, in imports and fully qualified uses alike, so an aliased or wildcard import cannot hide a
 * use"; its stated limits are reflection by a string that does not spell a package name and a type that is not in its list.
 *
 * The scanner is exercised through its own `scan(String): List<String>` (found by reflection on `JcaBoundaryScanTest` and its
 * nested classes, so a fix may move it inside that class freely; keep a `scan(String)` returning the hits there). Each red case
 * is a direct JCA use that the scan reports as clean although it is none of the stated limits:
 *
 * - a string TEMPLATE that holds a nested string literal with `//` or `/*` (`"${"//"}"`): the lexer ends the outer string at the
 *   inner quote and reads the rest of the line, or every line up to the next `*/`, as a comment, so real code is blanked;
 * - the boundary's own `typealias JcaRandom = SecureRandom`: any file can construct a `JcaRandom` and draw from it (a
 *   direct, untyped provider call) without naming `java.security`, `SecureRandom` or anything in the list;
 * - a back-quoted package segment (`` java.`security` ``), legal Kotlin, which defeats the package pattern for every type not in
 *   the type list (here `KeyStore`, a JCA engine class).
 *
 * Mutation by reading: a lexer that tracks `${ ... }` nesting inside string literals, the boundary's typealiases added to the
 * type list (or no typealias exported at all), and back-quotes stripped before matching turn the three red tests green. The
 * green tests pin what must keep holding: the scan's controls (it fails loudly on an empty or missing tree) and that no JVM
 * source outside `src/jvmMain/kotlin` can reach the jvm compilation unseen (no `.java` or `.kts` sources, no other jvm source set
 * directory beside `jvmMain`/`jvmTest`).
 */
class JcaBoundaryScanA8BreakerR8Test {

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

    @Test
    fun `control - the scanner flags a plain use and ignores a comment`() {
        assertTrue(scan("val d = java.security.MessageDigest.getInstance(\"SHA-256\")").isNotEmpty(), "control: a plain fully qualified use")
        assertEquals(emptyList<Any?>(), scan("// java.security.MessageDigest\nval x = 1"), "control: a comment")
    }

    @Test
    fun `a string template with a nested literal never turns the code after it into a comment`() {
        val line = "val p = \"\${\"//\"}\"; val d = java.security.MessageDigest.getInstance(\"SHA-256\")"
        val block = "val open = \"\${\"/*\"}\"\nval d = javax.crypto.Cipher.getInstance(\"AES/GCM/NoPadding\")\nval close = \"*/\"\n"
        assertTrue(scan(line).isNotEmpty(), "a `//` inside a template's nested string hid the JCA use after it on the same line (scan: ${scan(line)})")
        assertTrue(scan(block).isNotEmpty(), "a `/*` inside a template's nested string hid every line up to the next `*/` (scan: ${scan(block)})")
    }

    @Test
    fun `the boundary's own typealias is not a way around the boundary`() {
        val aliases: List<String> = Regex("""typealias\s+(\w+)\s*=""").findAll(boundarySource()).map { it.groupValues[1] }.toList()
        assertTrue(aliases.isNotEmpty(), "control: the boundary exports a typealias to a JCA type")
        for (alias: String in aliases) {
            val source = "val r = $alias(); val b = ByteArray(16); r.next" + "Bytes(b)" // spelled in two parts: the text is not a draw of this test
            assertTrue(
                scan(source).isNotEmpty(),
                "a direct provider call through the boundary's exported typealias $alias passed the scan (scan: ${scan(source)}): " +
                    "'$source' makes a new default SecureRandom and draws from it untyped, outside Jca.kt",
            )
        }
    }

    @Test
    fun `a back-quoted package segment does not hide a JCA type`() {
        val source = "import java.`security`.KeyStore\nval store = KeyStore.getInstance(\"PKCS12\")"
        assertTrue(scan(source).isNotEmpty(), "a back-quoted package segment hid a java.security engine class (scan: ${scan(source)})")
    }

    @Test
    fun `no JVM source can reach the jvm compilation outside the scanned directory`() {
        val src: File = moduleSrc()
        val jvmSets: List<String> = src.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("jvm") }.map { it.name }.sorted()
        assertEquals(listOf("jvmMain", "jvmTest"), jvmSets, "a jvm source set the boundary scan does not read")
        val main = File(src, "jvmMain")
        val foreign: List<String> = main.walkTopDown().filter { it.isFile && it.extension in setOf("java", "kts") }.map { it.name }.toList()
        assertEquals(emptyList(), foreign, "a JVM source in jvmMain that the scan (*.kt only) never reads")
        assertTrue(File(File(main, "kotlin"), "io").isDirectory, "control: the scanned tree exists")
    }

    private fun moduleSrc(): File {
        val working = File(System.getProperty("user.dir") ?: "")
        return listOf(File(working, "src"), File(File(working, "kmptoolkit-storage"), "src"))
            .firstOrNull { File(it, "jvmMain").isDirectory }
            ?: error("the storage module's sources were not found from ${working.path}")
    }

    private fun boundarySource(): String =
        File(moduleSrc(), "jvmMain").walkTopDown().single { it.name == "Jca.kt" && it.parentFile?.name == "desktop" }.readText()
}
