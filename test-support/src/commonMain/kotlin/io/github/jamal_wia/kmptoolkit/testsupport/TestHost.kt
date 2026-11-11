package io.github.jamal_wia.kmptoolkit.testsupport

/**
 * Facts about the machine the tests run on, and assumptions that skip a test (reported as skipped,
 * not as passed) when the machine cannot run it.
 *
 * Use the `assume*` functions in Android-only modules: the device is POSIX, so imitating POSIX on a
 * Windows build machine would only test the imitation. Modules with a desktop target use `TestFs`
 * (JVM source sets), because Windows is a real target there.
 *
 * @since 4.1.0
 */
public object TestHost {

    /** The path syntax of the machine running the tests. */
    public val pathSyntax: PathSyntax
        get() = hostPathSyntax()

    /**
     * The longest native path the host's native code can open (Windows MAX_PATH, 259 characters plus
     * the terminator), or `null` when there is no such limit. Java's own file API is not limited by
     * it; native code such as SQLite is.
     */
    public val maxNativePathLength: Int?
        get() = if (pathSyntax == PathSyntax.WINDOWS) MAX_WINDOWS_PATH else null

    /** Skips the test unless the host has POSIX file semantics; [reason] says what the test needs. */
    public fun assumePosixFileSemantics(reason: String) {
        hostAssume(pathSyntax == PathSyntax.POSIX, "needs POSIX file semantics: $reason")
    }

    /**
     * Skips the test when [path] is longer than [maxNativePathLength] (native code could not open it),
     * but only while the build has not switched on the short test temp folder
     * (`-Pkmptoolkit.windowsTestTmpDir`, see `shortenWindowsTestTmpDir` in build-logic). With that folder
     * on, a path that is still too long is the build's configuration to fix, so the test runs and fails
     * visibly instead of being skipped. Inert on every host without a limit and for a path that fits.
     */
    public fun assumeNativePathFits(path: String) {
        val limit: Int? = maxNativePathLength
        hostAssume(
            limit == null || path.length <= limit || shortTestTmpDirIsOn(),
            "native path is ${path.length} characters, over the host limit of $limit: $path",
        )
    }

    private fun shortTestTmpDirIsOn(): Boolean = hostSystemProperty(SHORT_TMP_DIR_PROPERTY) != null

    private const val SHORT_TMP_DIR_PROPERTY: String = "kmptoolkit.windowsTestTmpDir"

    /**
     * True when [name] can be created as a single file or directory name on the host: on
     * [PathSyntax.WINDOWS] no `<>:"|?*`, no `/` or `\`, no control character, no reserved device name
     * (`CON`, `NUL`, `COM1`... with or without an extension), no trailing space or dot; on
     * [PathSyntax.POSIX] no `/` and no NUL.
     */
    public fun isValidHostFileName(name: String, syntax: PathSyntax = pathSyntax): Boolean {
        if (name.isEmpty()) return false
        if (syntax == PathSyntax.POSIX) return name.none { it == '/' || it == '\u0000' }
        if (name.any { it < ' ' || it in "<>:\"|?*/\\" }) return false
        if (name.endsWith(' ') || name.endsWith('.')) return false
        val stem: String = name.substringBefore('.').trimEnd(' ').uppercase()
        if (stem in RESERVED_WINDOWS_NAMES) return false
        return true
    }

    private const val MAX_WINDOWS_PATH: Int = 259

    private val RESERVED_WINDOWS_NAMES: Set<String> =
        setOf("CON", "PRN", "AUX", "NUL") +
            (1..9).map { "COM$it" } +
            (1..9).map { "LPT$it" }
}

/** The path syntax of the host; JVM targets read it from the file separator, iOS is POSIX. */
internal expect fun hostPathSyntax(): PathSyntax

/** Skips the running test with [message] when [condition] is false (no-op where tests cannot be skipped). */
internal expect fun hostAssume(condition: Boolean, message: String)

/** A JVM system property of the test process, or `null` (always `null` on iOS, which has no limit to lift). */
internal expect fun hostSystemProperty(name: String): String?
