package io.github.jamal_wia.kmptoolkit.testsupport

internal actual fun hostPathSyntax(): PathSyntax = PathSyntax.POSIX

// iOS is POSIX, so every assumption the shared helpers make holds; a test cannot be skipped from here.
internal actual fun hostAssume(condition: Boolean, message: String) {
    check(condition) { message }
}
