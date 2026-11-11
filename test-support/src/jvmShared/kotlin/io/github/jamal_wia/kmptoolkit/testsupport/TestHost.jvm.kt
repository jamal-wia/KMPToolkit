package io.github.jamal_wia.kmptoolkit.testsupport

import java.io.File
import org.junit.Assume

internal actual fun hostPathSyntax(): PathSyntax =
    // path-hygiene-ignore: PH1 this is the host detection itself: the JVM's own separator character decides the PathSyntax
    if (File.separatorChar == '\\') PathSyntax.WINDOWS else PathSyntax.POSIX

internal actual fun hostAssume(condition: Boolean, message: String) {
    Assume.assumeTrue(message, condition)
}

internal actual fun hostSystemProperty(name: String): String? = System.getProperty(name)
