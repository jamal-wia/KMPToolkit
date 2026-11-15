package io.github.jamal_wia.kmptoolkit.storage.a10fixture.desktop

/*
 * Fixture of `JcaBytecodeScanA10BreakerR10Test` (group sto-desktop, phase A breaker round 10): a class named `Jca` in a package
 * that is NOT the boundary's package but whose last segment is `desktop`. It touches the JCA directly and is never run. The scan
 * of the compiled classes must flag it like any other class that is not the boundary.
 */

/** Not the boundary: same simple name, another package. */
internal object Jca {
    fun digest(bytes: ByteArray): ByteArray = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
}
