package io.github.jamal_wia.kmptoolkit.files

/**
 * Whether [PathKey.of] folds case.
 *
 * @since 5.0.0
 */
public enum class CaseFolding {
    /** Case is kept: `A` and `a` are different files. */
    Never,

    /** Case and Unicode spellings are folded: see [PathKey.of]. */
    Always,
}

/**
 * Keys under which spellings of one file compare equal.
 *
 * @since 5.0.0
 */
public object PathKey {
    /**
     * A key under which spellings of one file compare equal: [Paths.normalizeLexically] of [path], then for
     * [CaseFolding.Always] NFC, lowercase, uppercase, lowercase, NFC (locale-independent), so `ß`/`ẞ`/`ss`, the
     * sigma forms, `ſ`/`s`, the Kelvin sign/`k` and NFC/NFD spellings share a key on every platform. Over-folding
     * only costs parallelism. Lexical: canonicalize first if symbolic links matter. For in-memory comparison only;
     * never persist it.
     */
    public fun of(path: String, folding: CaseFolding): String {
        val normalized: String = Paths.normalizeLexically(path)
        return when (folding) {
            CaseFolding.Never -> normalized
            CaseFolding.Always -> foldCase(normalized)
        }
    }
}

internal fun foldCase(text: String): String =
    normalizeNfc(normalizeNfc(text).lowercase().uppercase().lowercase())
