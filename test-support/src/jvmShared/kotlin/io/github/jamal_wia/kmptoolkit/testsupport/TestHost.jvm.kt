package io.github.jamal_wia.kmptoolkit.testsupport

import java.io.File
import org.junit.Assume

internal actual fun hostPathSyntax(): PathSyntax =
    if (File.separatorChar == '\\') PathSyntax.WINDOWS else PathSyntax.POSIX

internal actual fun hostAssume(condition: Boolean, message: String) {
    Assume.assumeTrue(message, condition)
}

internal actual fun hostSystemProperty(name: String): String? = System.getProperty(name)
