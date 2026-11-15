package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.net.URL
import kotlin.random.asKotlinRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * Fixtures of `JcaBytecodeScanA10BreakerR10Test` below. They are compiled with the tests and never run.
 */

/**
 * A class that is NOT the boundary although its JVM binary name looks like a nested class of it: a back-quoted Kotlin name may hold
 * a `$`, so a class declared in any file of this package can be called `Jca$...`.
 */
internal class `Jca$A10NotNested` {
    fun digest(bytes: ByteArray): ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
}

/** A call on the boundary's random source through its supertype `java.util.Random`: an implicit upcast, no cast in the source. */
internal class JcaA10UpcastFixtureRandom {
    fun draw(random: JcaRandom, bytes: ByteArray) {
        val plain: java.util.Random = random
        plain.nextBytes(bytes)
    }
}

/** The same through the JDK 17 interface `RandomGenerator`, which `SecureRandom` implements. */
internal class JcaA10UpcastFixtureGenerator {
    fun draw(random: JcaRandom, bytes: ByteArray) {
        val generator: java.util.random.RandomGenerator = random
        generator.nextBytes(bytes)
    }
}

/** The same through the Kotlin standard library's adapter, a one-word idiom. */
internal class JcaA10UpcastFixtureKotlin {
    fun draw(random: JcaRandom, bytes: ByteArray) {
        random.asKotlinRandom().nextBytes(bytes)
    }
}

/**
 * Group sto-desktop, phase A breaker round 10 (decisions D-23), attack on the round-11 fix of A9-m2: the JCA boundary of D-P12 is now
 * enforced on the compiled classes' constant pools (`JcaBytecodeScanTest`, `ClassFileScan`). Contract under attack, in the words of the
 * code and of the decision proposal: "In every class but `Jca` (and its nested classes) the constant pool must name no
 * `java.security`, `javax.crypto` ... class" and the scan "cannot be fooled by quoting, aliases, spacing or a call on an instance the
 * boundary handed out" (D-P12 final text; `JcaBoundaryScanTest` KDoc).
 *
 * 1. **Who is exempt.** `ClassFileScan.isBoundary` exempts by NAME SHAPE: any internal name ending in `/desktop/Jca` or containing
 *    `/desktop/Jca$`. A class `Jca` in another package whose last segment is `desktop`, and a top-level class whose back-quoted name is
 *    `Jca$...` declared in any file, are not the boundary and are exempted. The fixtures here are such classes; each touches the JCA
 *    directly (control: the same scan flags it when asked), and the real-tree test's filter (`filterNot { isBoundary }`) must keep it.
 * 2. **A call on an instance the boundary handed out, through a supertype.** Kotlin upcasts implicitly: assigning a `JcaRandom` to a
 *    `java.util.Random` or a `RandomGenerator`, or `asKotlinRandom()`, needs no cast in the source, and the call's owner in the
 *    constant pool is then the supertype. The provider's `engineNextBytes` runs outside the boundary (a `ProviderException` of NativePRNG
 *    leaves untyped: rule 1 of D-P12 is lost) and the scan sees nothing.
 *
 * Mutation by reading: an exemption by the exact internal name of the boundary (plus its own nested classes, told apart by
 * `SourceFile` = `Jca.kt` or by the InnerClasses/NestHost attribute) turns 1 green; flagging the supertypes through which a JCA instance
 * can be driven (`java/util/Random`, `java/util/random/RandomGenerator`, `kotlin/random/PlatformRandomKt` / `asKotlinRandom`, none of
 * which the main code uses) turns 2 green.
 */
class JcaBytecodeScanA10BreakerR10Test {

    private fun codeSource(type: Class<*>): URL =
        type.protectionDomain?.codeSource?.location ?: error("no code source for ${type.name}: the fixtures cannot be found")

    private fun internalName(type: Class<*>): String = type.name.replace('.', '/')

    private fun factsOf(type: Class<*>): ClassFileScan.Facts {
        val name: String = internalName(type)
        return ClassFileScan.classBytes(codeSource(type)).map(ClassFileScan::parse).singleOrNull { facts: ClassFileScan.Facts -> facts.name == name }
            ?: error("control: the class file of $name was not found next to the tests")
    }

    @Test
    fun `a class that is not the boundary is never exempted because of the shape of its name`() {
        val fixtures: List<ClassFileScan.Facts> = listOf(
            factsOf(`Jca$A10NotNested`::class.java),
            factsOf(io.github.jamal_wia.kmptoolkit.storage.a10fixture.desktop.Jca::class.java),
        )
        for (facts: ClassFileScan.Facts in fixtures) {
            assertTrue(ClassFileScan.violations(facts).isNotEmpty(), "control: ${facts.name} touches the JCA and the scan flags it when asked")
        }
        val boundary: String = internalName(Jca::class.java)
        assertTrue(ClassFileScan.isBoundary(boundary), "control: the real boundary is exempt")
        // The real-tree test keeps every class that is not exempt and fails on its violations: these must be kept.
        val exempted: List<String> = fixtures.filter { facts: ClassFileScan.Facts -> ClassFileScan.isBoundary(facts.name) }.map { it.name }
        assertEquals(
            emptyList(),
            exempted,
            "classes that are NOT the boundary $boundary (another package, a top-level class compiled from another file) are exempted " +
                "from the scan by the shape of their name, so their JCA uses pass the real-tree test",
        )
    }

    @Test
    fun `a call on the boundary's random source through a supertype is seen`() {
        val fixtures: List<ClassFileScan.Facts> = listOf(
            factsOf(JcaA10UpcastFixtureRandom::class.java),
            factsOf(JcaA10UpcastFixtureGenerator::class.java),
            factsOf(JcaA10UpcastFixtureKotlin::class.java),
        )
        for (facts: ClassFileScan.Facts in fixtures) {
            assertTrue(
                facts.descriptors.any { descriptor: String -> "Ljava/security/SecureRandom;" in descriptor },
                "control: ${facts.name} takes the boundary's random source (JcaRandom) as a parameter",
            )
            assertTrue(!ClassFileScan.isBoundary(facts.name), "control: ${facts.name} is not exempt")
        }
        val unseen: List<String> = fixtures.filter { facts: ClassFileScan.Facts -> ClassFileScan.violations(facts).isEmpty() }.map { it.name }
        assertEquals(
            emptyList(),
            unseen,
            "D-P12: the scan 'cannot be fooled by ... a call on an instance the boundary handed out'; these classes drive the SecureRandom " +
                "engine outside the boundary through an implicit upcast (no cast in the source) and the scan reports them as clean",
        )
    }
}
