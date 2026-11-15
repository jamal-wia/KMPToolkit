@file:JvmName("Jca\$B11Facade")

package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

/*
 * Fixtures of `JcaBytecodeScanBreakerR11Test` (group sto-desktop, phase A breaker round 11). This file is NOT the JCA boundary: it is
 * a test-set file whose base name happens to be `Jca.kt`, in the boundary's package. The JVM `SourceFile` attribute of a class holds
 * only the base name of the file it was compiled from (no directory, no source set), so every class compiled from this file carries
 * `SourceFile = "Jca.kt"`, exactly like the boundary's own classes. Kotlin does not require the directory to match the package, so
 * the same file could sit in any directory of any source set compiled into the jvm target (the `jvmFfm` one included, which the
 * lexical source scan does not read). Nothing here is ever run.
 */

/** A top-level class whose back-quoted name holds a `$`: its JVM binary name reads like a class nested in `Jca`. */
internal class `Jca$B11Forged` {
    fun digest(bytes: ByteArray): ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
}

/**
 * A top-level function: it compiles into this file's facade class, which `@file:JvmName` names `Jca$B11Facade` (no back-quotes
 * needed), with the same `SourceFile`.
 */
internal fun b11FacadeDigest(bytes: ByteArray): ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
