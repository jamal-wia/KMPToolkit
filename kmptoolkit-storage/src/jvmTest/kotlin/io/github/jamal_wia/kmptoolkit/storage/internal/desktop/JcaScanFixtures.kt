@file:OptIn(ExperimentalEncodingApi::class)

package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/*
 * Fixtures of `JcaBytecodeScanTest` (group sto-desktop, round 11): small classes compiled with the test sources, each one a way of
 * reaching the JCA that a lexical scan of source text can be fooled about. The test reads their class files and expects the scan of
 * the compiled classes to flag every `JcaScanFixtureFlagged...` class and to leave `JcaScanFixtureClean...` alone. None of them is
 * ever run.
 */

/** A direct construction by the full name. */
internal class JcaScanFixtureFlaggedDirect {
    fun make(): Any = java.security.MessageDigest.getInstance("SHA-256")
}

/** A call on an INSTANCE of the boundary's random source: no name of the JDK type appears in the source of the call. */
internal class JcaScanFixtureFlaggedInstanceCall {
    fun draw(random: JcaRandom, bytes: ByteArray) {
        random.nextBytes(bytes)
    }
}

/** A constructor reference to the boundary's alias. */
internal class JcaScanFixtureFlaggedConstructorReference {
    val make: () -> JcaRandom = ::JcaRandom
}

private typealias FixtureSecondAlias = JcaRandom

/** An alias of the boundary's alias, constructed. */
internal class JcaScanFixtureFlaggedSecondAlias {
    fun make(): Any = FixtureSecondAlias()
}

/** Whitespace around the dots of a qualified name, and a type that is in no list. */
internal class JcaScanFixtureFlaggedSpaced {
    fun type(): String = java . security . KeyStore.getDefaultType()
}

/** The JDK's Base64. */
internal class JcaScanFixtureFlaggedJdkBase64 {
    fun encode(bytes: ByteArray): ByteArray = java.util.Base64.getEncoder().encode(bytes)
}

/** The Kotlin standard library's Base64. */
internal class JcaScanFixtureFlaggedKotlinBase64 {
    fun encode(bytes: ByteArray): String = Base64.Default.encode(bytes)
}

/** A class name written out as a string. */
internal class JcaScanFixtureFlaggedReflection {
    fun load(): Class<*> = Class.forName("java.security.SecureRandom")
}

/** The one thing that may cross the boundary: the random source's type in a signature, handed straight to the boundary. */
internal class JcaScanFixtureCleanBoundaryUse {
    fun draw(random: JcaRandom): ByteArray = Jca.randomBytes(random, 4)
}

/** Other JDK file and text APIs are not the JCA. */
internal class JcaScanFixtureCleanOtherJdk {
    fun size(path: Path): Long = Files.size(path) + "x".toByteArray(Charsets.UTF_8).size
}

/**
 * Round 12 (breaker A10-m1): a top-level class whose back-quoted name holds a `$`, so that its binary name looks like a class nested in
 * the boundary. It is compiled from this file, not from `Jca.kt`, and it touches the JCA: the scan must not exempt it.
 */
internal class `Jca$Round12NotNested` {
    fun digest(bytes: ByteArray): ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
}
