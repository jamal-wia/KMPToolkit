package io.github.jamal_wia.kmptoolkit.files

/** The text in Unicode normalization form C. */
internal expect fun normalizeNfc(text: String): String

/** Whether the host's own file system uses Windows path syntax (only a JVM running on Windows does). */
internal expect val hostUsesWindowsPaths: Boolean
