package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Group sto-desktop, phase A breaker round 11 (decisions D-23), attack on the round-12 fix of A10-m1: `ClassFileScan.isBoundaryClass`
 * exempts the boundary by its exact name and "a nested class by a name that starts with `Jca$` AND a `SourceFile` attribute of
 * `Jca.kt`", with the stated reason "A name can be forged with back-quotes, the file a class was compiled from cannot, and a file
 * `Jca.kt` of the boundary's package is the boundary file" (ClassFileScan KDoc; D-P12: "never by the shape of a name"; 05: the scan
 * sees "a class that is only named like the boundary").
 *
 * The attack: the `SourceFile` attribute holds the BASE NAME of the source file only. A second file called `Jca.kt` in the boundary's
 * package (any directory, any source set compiled into the jvm target; Kotlin does not tie the directory to the package) gives every
 * class compiled from it `SourceFile = "Jca.kt"`. Two plain-Kotlin routes then produce a `Jca$...` name: a back-quoted class name, and
 * `@file:JvmName("Jca\$...")` for the file's facade (no back-quotes at all). The fixtures (`Jca.kt` of this test source set) touch the
 * JCA directly; the real-tree test drops every class for which `isBoundaryClass` is true, so these must not be exempt.
 *
 * Controls: each fixture's class file is found, carries `SourceFile = "Jca.kt"`, has the forged name, and the same scan flags it when
 * asked. Mutation by reading: confirming a nested class by the boundary's own class file (its `InnerClasses` table naming the class
 * with `Jca` as the outer class, or the nested class's `EnclosingMethod`/`InnerClasses` entry naming `Jca`, or simply exempting the
 * exact name only, since the compiled `Jca` has no nested class today) turns this test green.
 */
class JcaBytecodeScanBreakerR11Test {

    private fun codeSource(type: Class<*>): URL =
        type.protectionDomain?.codeSource?.location ?: error("no code source for ${type.name}: the fixtures cannot be found")

    private fun internalName(type: Class<*>): String = type.name.replace('.', '/')

    private val boundary: String = internalName(Jca::class.java)

    private fun factsNamed(name: String): ClassFileScan.Facts =
        ClassFileScan.classBytes(codeSource(JcaBytecodeScanBreakerR11Test::class.java)).map(ClassFileScan::parse)
            .singleOrNull { facts: ClassFileScan.Facts -> facts.name == name }
            ?: error("control: the class file of $name was not found next to the tests")

    @Test
    fun `a class compiled from another file that is also called Jca_kt is not exempt as a class nested in the boundary`() {
        val fixtures: List<ClassFileScan.Facts> = listOf(factsNamed(internalName(`Jca$B11Forged`::class.java)), factsNamed("$boundary\$B11Facade"))
        for (facts: ClassFileScan.Facts in fixtures) {
            assertTrue(facts.name.startsWith("$boundary\$"), "control: ${facts.name} has a name that reads like a class nested in the boundary")
            assertEquals("Jca.kt", facts.sourceFile, "control: ${facts.name} was compiled from a test-set file whose base name is Jca.kt")
            assertTrue(ClassFileScan.violations(facts).isNotEmpty(), "control: ${facts.name} touches the JCA and the scan flags it when asked")
        }
        val realBoundary: ClassFileScan.Facts = ClassFileScan.classBytes(codeSource(Jca::class.java)).map(ClassFileScan::parse)
            .single { facts: ClassFileScan.Facts -> facts.name == boundary }
        assertTrue(ClassFileScan.isBoundaryClass(realBoundary), "control: the real boundary is exempt")
        val exempted: List<String> = fixtures.filter(ClassFileScan::isBoundaryClass).map { facts: ClassFileScan.Facts -> facts.name }
        assertEquals(
            emptyList(),
            exempted,
            "classes compiled from ANOTHER file called Jca.kt (a back-quoted name; a @file:JvmName facade) are exempted as classes nested " +
                "in the boundary $boundary: the SourceFile attribute holds only a base name, so 'the file a class was compiled from' is " +
                "forged by naming a file, and their JCA uses pass the real-tree test",
        )
    }
}
