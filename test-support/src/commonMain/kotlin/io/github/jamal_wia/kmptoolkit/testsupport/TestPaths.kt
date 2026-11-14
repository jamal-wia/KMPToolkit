package io.github.jamal_wia.kmptoolkit.testsupport

/**
 * Converts between **logical** and **native** file paths in tests.
 *
 * A *native* path is a string the file system handed out (`File.path`, `Context.filesDir`, a
 * `getTempFilePath()` result): on Windows it looks like `C:\Users\x\files\a.bin`. A *logical* path is
 * one the code under test makes up itself (`tmp/expect/<id>.sha256`): always `/`-separated, never
 * absolute. Mixing the two (`"$base/tmp"`, `substringBeforeLast('/')`) is what breaks tests on
 * Windows; this object is the one place that turns a logical path into a native one and back.
 *
 * Every function takes the [PathSyntax] it works in and defaults to [TestHost.pathSyntax], so the
 * Windows behaviour is unit-tested on a POSIX machine by passing [PathSyntax.WINDOWS] explicitly.
 *
 * The functions are lexical: they never touch a file system. The Windows extended prefix (`\\?\`,
 * `\\?\UNC\`) is dropped by every function, because `java.io.File` never produces one.
 *
 * @since 5.0.0
 */
public object TestPaths {

    /**
     * The native path of [logical] (`"a/b/c.bin"`) below [base].
     *
     * Empty segments are dropped (as `java.io.File` does), `.` and `..` are kept verbatim (as
     * `java.io.File` does), and the result has no trailing separator unless it is a bare root.
     * On [PathSyntax.WINDOWS] the separators of [base] become `\`.
     *
     * @throws IllegalArgumentException when [logical] is absolute in [syntax].
     * @since 5.0.0
     */
    public fun join(base: String, logical: String, syntax: PathSyntax = TestHost.pathSyntax): String {
        require(!isLogicalAbsolute(logical, syntax)) { "A logical path must be relative, was: $logical" }
        val parsed: Parsed = parse(base, syntax)
        val extra: List<String> = logical.split('/').filter { it.isNotEmpty() }
        val result: String = format(parsed.root, parsed.segments + extra, syntax)
        // java.io.File keeps no trailing separator on a UNC share: File("\\\\srv\\share").path is that string.
        return if ((parsed.segments + extra).isEmpty()) uncShare(result, syntax) else result
    }

    /**
     * The native parent of [path], or `null` for a bare name or a root (as `java.io.File.getParent`).
     *
     * @since 5.0.0
     */
    public fun parent(path: String, syntax: PathSyntax = TestHost.pathSyntax): String? {
        val parsed: Parsed = parse(path, syntax)
        if (parsed.segments.isEmpty()) return null
        // The parent of a file directly under a UNC share is the share without a trailing separator (as java.io.File).
        if (parsed.segments.size == 1) return uncShare(parsed.root, syntax).ifEmpty { null }
        return format(parsed.root, parsed.segments.dropLast(1), syntax)
    }

    /**
     * The last segment of [path]; empty for a root.
     *
     * @since 5.0.0
     */
    public fun fileName(path: String, syntax: PathSyntax = TestHost.pathSyntax): String =
        parse(path, syntax).segments.lastOrNull().orEmpty()

    /**
     * [path] spelled with `/` so it can be compared with a `/`-literal.
     *
     * [PathSyntax.WINDOWS]: strips `\\?\` (`\\?\UNC\s\x` becomes `//s/x`) and turns `\` into `/`; the
     * drive is kept (`C:/x`). [PathSyntax.POSIX]: the identity, because a backslash is an ordinary
     * file-name character there and must not be converted.
     *
     * @since 5.0.0
     */
    public fun toLogical(path: String, syntax: PathSyntax = TestHost.pathSyntax): String {
        if (syntax == PathSyntax.POSIX) return path
        val slashed: String = path.replace('\\', '/')
        return when {
            slashed.startsWith("//?/UNC/", ignoreCase = true) -> "//" + slashed.substring(8)
            slashed.startsWith("//?/") -> slashed.substring(4)
            else -> slashed
        }
    }

    /**
     * Resolves `.` and `..` lexically. `..` at a root is dropped; `..` at the start of a relative
     * path is kept; a relative path that cancels out becomes `.`. The root is kept (`/`, `C:\`,
     * `\\srv\share\`).
     *
     * @since 5.0.0
     */
    public fun normalize(path: String, syntax: PathSyntax = TestHost.pathSyntax): String {
        val parsed: Parsed = parse(path, syntax)
        val resolved: MutableList<String> = mutableListOf()
        for (segment: String in parsed.segments) {
            when {
                segment == "." -> Unit
                segment == ".." -> when {
                    resolved.isNotEmpty() && resolved.last() != ".." -> resolved.removeAt(resolved.lastIndex)
                    parsed.rooted -> Unit
                    else -> resolved.add(segment)
                }
                else -> resolved.add(segment)
            }
        }
        if (resolved.isEmpty() && parsed.root.isEmpty()) return "."
        return format(parsed.root, resolved, syntax)
    }

    /**
     * The logical path of [path] below [base] after [normalize], `""` when they are the same, or
     * `null` when [path] is outside [base]. Trailing separators are ignored.
     *
     * Case is exact on [PathSyntax.POSIX] and ignored on [PathSyntax.WINDOWS] (the NTFS default); use
     * it for containment only, never for equality.
     *
     * @since 5.0.0
     */
    public fun relativeLogical(base: String, path: String, syntax: PathSyntax = TestHost.pathSyntax): String? {
        val b: Parsed = parseNormalized(base, syntax)
        val p: Parsed = parseNormalized(path, syntax)
        if (!same(b.root, p.root, syntax)) return null
        if (p.segments.size < b.segments.size) return null
        for (i: Int in b.segments.indices) {
            if (!same(b.segments[i], p.segments[i], syntax)) return null
        }
        return p.segments.drop(b.segments.size).joinToString("/")
    }

    /**
     * True when [path] is strictly below [base] (see [relativeLogical]).
     *
     * @since 5.0.0
     */
    public fun isInside(base: String, path: String, syntax: PathSyntax = TestHost.pathSyntax): Boolean =
        !relativeLogical(base, path, syntax).isNullOrEmpty()

    // -- internals ---------------------------------------------------------------------------

    /** [normalize] then parse, reading the `.` of a relative path that cancels out as no segments at all. */
    private fun parseNormalized(path: String, syntax: PathSyntax): Parsed {
        val normalized: String = normalize(path, syntax)
        return if (normalized == ".") Parsed("", emptyList(), false) else parse(normalized, syntax)
    }

    private fun uncShare(root: String, syntax: PathSyntax): String =
        if (syntax == PathSyntax.WINDOWS && root.startsWith("\\\\")) root.trimEnd('\\') else root

    private class Parsed(val root: String, val segments: List<String>, val rooted: Boolean)

    private fun same(a: String, b: String, syntax: PathSyntax): Boolean =
        a.equals(b, ignoreCase = syntax == PathSyntax.WINDOWS)

    private fun isLogicalAbsolute(logical: String, syntax: PathSyntax): Boolean = when (syntax) {
        // path-hygiene-ignore: PH5 TestPaths parses an explicit PathSyntax; this is its POSIX/logical branch, not a host-path check
        PathSyntax.POSIX -> logical.startsWith('/')
        PathSyntax.WINDOWS ->
            // path-hygiene-ignore: PH5 TestPaths parses an explicit PathSyntax; this is its POSIX/logical branch, not a host-path check
            logical.startsWith('/') || logical.startsWith('\\') ||
                (logical.length >= 2 && logical[0].isLetter() && logical[1] == ':')
    }

    private fun format(root: String, segments: List<String>, syntax: PathSyntax): String {
        val separator: String = if (syntax == PathSyntax.WINDOWS) "\\" else "/"
        val joined: String = segments.joinToString(separator)
        if (root.length > 2 && !root.endsWith(separator) && joined.isNotEmpty()) return root + separator + joined
        return root + joined
    }

    private fun parse(path: String, syntax: PathSyntax): Parsed = when (syntax) {
        PathSyntax.POSIX -> {
            // path-hygiene-ignore: PH5 TestPaths parses an explicit PathSyntax; this is its POSIX/logical branch, not a host-path check
            val rooted: Boolean = path.startsWith('/')
            Parsed(if (rooted) "/" else "", path.split('/').filter { it.isNotEmpty() }, rooted)
        }
        PathSyntax.WINDOWS -> parseWindows(path)
    }

    private fun parseWindows(path: String): Parsed {
        var text: String = path.replace('/', '\\')
        if (text.startsWith("\\\\?\\UNC\\", ignoreCase = true)) {
            text = "\\\\" + text.substring(8)
        } else if (text.startsWith("\\\\?\\")) {
            text = text.substring(4)
        }
        if (text.startsWith("\\\\")) {
            val parts: List<String> = text.substring(2).split('\\').filter { it.isNotEmpty() }
            val head: List<String> = parts.take(2)
            val root: String = "\\\\" + head.joinToString("\\") + if (head.size == 2) "\\" else ""
            return Parsed(root, parts.drop(2), true)
        }
        if (text.length >= 2 && text[0].isLetter() && text[1] == ':') {
            val drive: String = text.substring(0, 2)
            val rest: String = text.substring(2)
            val rooted: Boolean = rest.startsWith('\\')
            return Parsed(if (rooted) "$drive\\" else drive, rest.split('\\').filter { it.isNotEmpty() }, rooted)
        }
        val rooted: Boolean = text.startsWith('\\')
        return Parsed(if (rooted) "\\" else "", text.split('\\').filter { it.isNotEmpty() }, rooted)
    }
}
