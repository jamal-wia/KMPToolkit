package io.github.jamal_wia.kmptoolkit.files

/**
 * Settings of one [FileAccess] instance.
 *
 * @param tempPrefix starts the name of every temporary file this instance creates (`<prefix><digits>.tmp`).
 *   1..64 UTF-8 bytes, and `"$tempPrefix0.tmp"` must be a [FileNames.isPlainName] on the host, else
 *   [IllegalArgumentException]. A parameter, not a constant, per the no-hardcoded-identifier invariant.
 * @since 5.0.0
 */
public data class FileAccessConfig(public val tempPrefix: String = DEFAULT_TEMP_PREFIX) {
    init {
        requireTempPrefix(tempPrefix, "tempPrefix")
    }

    public companion object {
        /** The temporary-file prefix used when none is configured. */
        public const val DEFAULT_TEMP_PREFIX: String = ".kmptoolkit-"
    }
}

private const val MAX_PREFIX_BYTES: Int = 64

/** Validates a temporary-file prefix (see [FileAccessConfig.tempPrefix]). */
internal fun requireTempPrefix(prefix: String, parameterName: String) {
    val bytes: Int = io.github.jamal_wia.kmptoolkit.files.internal.NameRules.utf8Length(prefix)
    require(bytes in 1..MAX_PREFIX_BYTES) { "$parameterName must be 1..$MAX_PREFIX_BYTES UTF-8 bytes: '$prefix'" }
    require(FileNames.isPlainName(prefix + "0" + TEMP_SUFFIX)) {
        "$parameterName must make a plain file name on this host: '$prefix'"
    }
}
