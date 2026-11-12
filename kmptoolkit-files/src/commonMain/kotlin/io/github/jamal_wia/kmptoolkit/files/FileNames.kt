package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.files.internal.NameRules

/**
 * Rules for single file names, and for the temporary-file names this module makes.
 *
 * @since 5.0.0
 */
public object FileNames {
    /** File-name byte limit of every supported file system. */
    public const val MAX_NAME_BYTES: Int = 255

    /**
     * Whether [name] can be one directory entry on this host: not empty, not "." or "..", no '/', no NUL, no
     * unpaired surrogate, at most [MAX_NAME_BYTES] UTF-8 bytes; on Windows also no '\\', no `<>:"|?*`, no
     * U+0001..U+001F, no trailing '.' or ' ', and not [isWindowsReserved].
     */
    public fun isPlainName(name: String): Boolean = NameRules.isPlain(name, hostUsesWindowsPaths)

    /**
     * Returns [name] when [isPlainName], else throws [IllegalArgumentException] naming [parameterName].
     */
    public fun requirePlainName(name: String, parameterName: String = "name"): String {
        require(isPlainName(name)) { "$parameterName is not a plain file name: '$name'" }
        return name
    }

    /**
     * Whether the part of [name] before its first '.' is, ignoring case, CON PRN AUX NUL COM0-9 LPT0-9 (also ¹²³).
     * Host independent.
     */
    public fun isWindowsReserved(name: String): Boolean = NameRules.isWindowsReserved(name)

    /**
     * Whether [name] is `<prefix><1..19 ASCII digits>.tmp`: a temporary file of [FileAccess.writeAtomically] or
     * [FileAccess.move] with that prefix.
     */
    public fun isTempName(name: String, prefix: String = FileAccessConfig.DEFAULT_TEMP_PREFIX): Boolean {
        if (!name.startsWith(prefix) || !name.endsWith(TEMP_SUFFIX)) return false
        val digitsEnd: Int = name.length - TEMP_SUFFIX.length
        if (digitsEnd < prefix.length) return false
        val digits: Int = digitsEnd - prefix.length
        if (digits !in 1..MAX_TEMP_DIGITS) return false
        for (i: Int in prefix.length until digitsEnd) {
            if (name[i] !in '0'..'9') return false
        }
        return true
    }
}

internal const val TEMP_SUFFIX: String = ".tmp"
internal const val MAX_TEMP_DIGITS: Int = 19
