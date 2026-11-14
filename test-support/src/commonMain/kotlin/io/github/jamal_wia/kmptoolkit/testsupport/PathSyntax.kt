package io.github.jamal_wia.kmptoolkit.testsupport

/**
 * The file-path syntax a path string is written in. It is a parameter of every [TestPaths] function,
 * so the Windows behaviour of the converter can be unit-tested on a POSIX machine.
 *
 * @since 5.0.0
 */
public enum class PathSyntax {
    /** `/` separates, a backslash is an ordinary file-name character, only `/`-rooted paths are absolute. */
    POSIX,

    /** `\` and `/` separate, drive letters and UNC shares are roots, names are case-insensitive. */
    WINDOWS,
}
