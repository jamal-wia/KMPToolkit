package io.github.jamal_wia.kmptoolkit.files

/**
 * Settings of one [FileAccess] instance.
 *
 * @param tempPrefix starts the name of every temporary file this instance creates (`<prefix><digits>.tmp`).
 *   1..232 UTF-8 bytes, so every temporary name fits 255 bytes, and `"$tempPrefix0.tmp"` must be a [FileNames.isPlainName] on the host, else
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

        /**
         * The longest [tempPrefix] accepted, in UTF-8 bytes: 255 ([FileNames.MAX_NAME_BYTES]) minus 19 digits minus
         * `.tmp`, so every temporary name fits a file name.
         *
         * @since 5.0.0
         */
        public const val MAX_TEMP_PREFIX_BYTES: Int = FileNames.MAX_NAME_BYTES - 19 - 4
    }
}

private const val MAX_PREFIX_BYTES: Int = FileAccessConfig.MAX_TEMP_PREFIX_BYTES

/** Validates a temporary-file prefix (see [FileAccessConfig.tempPrefix]). */
internal fun requireTempPrefix(prefix: String, parameterName: String) {
    val bytes: Int = io.github.jamal_wia.kmptoolkit.files.internal.NameRules.utf8Length(prefix)
    require(bytes in 1..MAX_PREFIX_BYTES) { "$parameterName must be 1..$MAX_PREFIX_BYTES UTF-8 bytes: '$prefix'" }
    require(FileNames.isPlainName(prefix + "0" + TEMP_SUFFIX)) {
        "$parameterName must make a plain file name on this host: '$prefix'"
    }
}
