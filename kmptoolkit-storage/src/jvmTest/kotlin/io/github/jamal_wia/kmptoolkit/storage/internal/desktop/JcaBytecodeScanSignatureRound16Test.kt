package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Group sto-desktop, round 16 (review round 38, m2): the descriptor rule of [ClassFileScan.violations] reads EVERY `Utf8` entry of a
 * class for the types it names (`L<internal name>` ended by `;` or `<`, at any position), so a forbidden type in an array descriptor,
 * a generic `Signature` or the bound of a type parameter is flagged in a Java class too, which has no `kotlin.Metadata` to name it
 * a second time. The breaker class `JcaBytecodeScanBreakerR14Test` pins the four javac shapes of its report; this one adds the shapes
 * around them (several array dimensions, a method descriptor, a nested argument, a second argument, an inner class of a generic outer,
 * an interface bound, a type parameter named `L`, a wildcard bound that is an array) and the controls that the rule does not
 * over-flag: the one type that may cross the boundary (`SecureRandom`) in every one of those positions, type variables, and `Utf8`
 * entries that only look a little like a descriptor.
 *
 * Each class is built from a constant pool that holds the entry and nothing else, which is all the rule reads.
 *
 * Mutation by reading: restoring the old filter (`(` or `L...;`) or the old pattern (`L([^;()\[]+);`) turns the first test red; dropping
 * the `type != ALLOWED_TYPE` exemption, or letting the pattern end only at `;`, turns the second red.
 */
class JcaBytecodeScanSignatureRound16Test {

    @Test
    fun `a forbidden type in any position of a descriptor or a signature is flagged`() {
        val shapes: List<Pair<String, String>> = listOf(
            "[[Ljava/util/Random;" to "java/util/Random",
            "([Ljavax/crypto/Cipher;)V" to "javax/crypto/Cipher",
            "Ljava/util/Map<Ljava/lang/String;Ljava/util/Random;>;" to "java/util/Random",
            "Ljava/util/Map<Ljava/lang/String;Ljava/util/List<Ljavax/crypto/Cipher;>;>;" to "javax/crypto/Cipher",
            "<T::Ljava/util/random/RandomGenerator;>Ljava/lang/Object;" to "java/util/random/RandomGenerator",
            "Lp/Outer<TT;>.Inner<Ljavax/crypto/Cipher;>;" to "javax/crypto/Cipher",
            "<L:Ljava/util/Random;>Ljava/lang/Object;" to "java/util/Random",
            "(TL;Ljava/util/Random;)V" to "java/util/Random",
            "<A:Ljava/lang/Object;B:Ljava/util/Base64;>Ljava/lang/Object;" to "java/util/Base64",
            "Ljava/util/List<+[Ljava/util/Random;>;" to "java/util/Random",
            "<E:Ljava/lang/Exception;>(TE;)V^Ljava/security/GeneralSecurityException;" to "java/security/GeneralSecurityException",
            "Ljava/util/List<Ljava/util/Base64\$Encoder;>;" to "java/util/Base64\$Encoder",
            "Ljava/util/random/RandomGeneratorFactory<TT;>;" to "java/util/random/RandomGeneratorFactory",
        )
        val missed: List<String> = shapes.mapNotNull { (entry: String, forbidden: String) ->
            val facts: ClassFileScan.Facts = ClassFileScan.parse(classOf("j/Shape", entry))
            val found: List<String> = ClassFileScan.violations(facts)
            if (found == listOf("j/Shape has a descriptor that mentions $forbidden") && entry in facts.descriptors) null else "$entry: found $found"
        }
        assertEquals(emptyList(), missed, "a type that a descriptor or a signature names must be flagged by that name, once")
    }

    @Test
    fun `the secure random type is allowed in every position and entries that only look like a descriptor are not flagged`() {
        val allowed: List<String> = listOf(
            "Ljava/security/SecureRandom;",
            "[Ljava/security/SecureRandom;",
            "[[Ljava/security/SecureRandom;",
            "(Ljava/security/SecureRandom;)V",
            "Ljava/util/List<Ljava/security/SecureRandom;>;",
            "<T:Ljava/security/SecureRandom;>Ljava/lang/Object;",
            "Ljava/util/Map<Ljava/lang/String;[Ljava/security/SecureRandom;>;",
            "<T:Ljava/lang/Object;>(TT;[TT;)V",
            "Ljava/util/List<TT;>;",
            "Hello; List<String> and L; or L<",
            "Lorem ipsum; dolor",
            "Ljava/lang/String;",
            "java/util/Random",
            "kotlin/Metadata",
        )
        val flagged: List<String> = allowed.mapNotNull { entry: String ->
            val found: List<String> = ClassFileScan.violations(ClassFileScan.parse(classOf("j/Allowed", entry)))
            if (found.isEmpty()) null else "$entry: found $found"
        }
        assertEquals(emptyList(), flagged, "nothing in these entries is a forbidden type")
        // Control: the allowed type next to a forbidden one in the same entry flags the forbidden one only.
        val mixed: List<String> = ClassFileScan.violations(ClassFileScan.parse(classOf("j/Mixed", "Ljava/util/Map<Ljava/security/SecureRandom;Ljavax/crypto/Cipher;>;")))
        assertEquals(listOf("j/Mixed has a descriptor that mentions javax/crypto/Cipher"), mixed)
        assertTrue(ClassFileScan.isForbiddenType("java/util/Random"), "control: the type the first shapes name is forbidden")
    }

    /** A class file named [name] whose constant pool is its own name and the one `Utf8` entry [entry]: no member, no attribute. */
    private fun classOf(name: String, entry: String): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).apply {
            writeInt(0xCAFEBABE.toInt())
            writeShort(0)
            writeShort(CLASS_VERSION)
            writeShort(POOL_COUNT)
            writeByte(TAG_UTF8)
            writeUTF(name) // 1
            writeByte(TAG_CLASS)
            writeShort(1) // 2
            writeByte(TAG_UTF8)
            writeUTF(entry) // 3
            writeShort(ACC_PUBLIC)
            writeShort(2) // this class
            writeShort(0) // super class
            writeShort(0) // interfaces
            writeShort(0) // fields
            writeShort(0) // methods
            writeShort(0) // attributes
            flush()
        }
        return out.toByteArray()
    }

    private companion object {
        const val CLASS_VERSION: Int = 61
        const val ACC_PUBLIC: Int = 0x0001
        const val POOL_COUNT: Int = 4
        const val TAG_UTF8: Int = 1
        const val TAG_CLASS: Int = 7
    }
}
