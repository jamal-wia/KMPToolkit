package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.files.internal.PathRules

/**
 * Lexical path rules of the host: POSIX on Android, iOS and Unix JVMs; Windows rules on a Windows JVM. No file I/O.
 *
 * @since 5.0.0
 */
public object Paths {
    private val rules: PathRules
        get() = PathRules.host

    /**
     * POSIX: starts with '/'. Windows: `C:\`, `C:/`, `\\server\share`, `//server/share`; `\x` and `C:x` are not.
     * Empty: false.
     */
    public fun isAbsolute(path: String): Boolean = rules.isAbsolute(path)

    /**
     * [parent] + one host separator + [child], without doubling a separator at the joint.
     * @throws IllegalArgumentException if [child] is empty or absolute.
     */
    public fun join(parent: String, child: String): String = rules.join(parent, child)

    /**
     * The path without its last segment (trailing separators ignored); `null` for a root, an empty path, or a
     * single relative segment.
     */
    public fun parentOf(path: String): String? = rules.parentOf(path)

    /**
     * Resolves `.`, `..` and repeated separators and drops a trailing separator. `..` above a root stays at the
     * root; leading `..` of a relative path are kept; an empty result is ".". Windows: accepts both separators,
     * writes `\`. Lexical: does not know about symbolic links.
     */
    public fun normalizeLexically(path: String): String = rules.normalizeLexically(path)

    /**
     * `normalizeLexically(join(base, relative))` when that stays inside [base], else `null` (absolute, NUL, or
     * escaping [relative]).
     */
    public fun resolveInside(base: String, relative: String): String? = rules.resolveInside(base, relative)
}
