package io.github.jamal_wia.kmptoolkit.uploader

/**
 * [value] as it goes between the quotes of a multipart `name` or `filename` parameter, escaped the way browsers
 * do (WHATWG): `"` becomes `%22`, CR `%0D` and LF `%0A`, and nothing else changes. Without it a name that
 * carries a quote or a line break ends its parameter early or starts a header or a part of its own.
 */
internal fun multipartQuotedValue(value: String): String {
    if (value.none { it == '"' || it == '\r' || it == '\n' }) return value
    return buildString(value.length + 8) {
        for (character in value) {
            when (character) {
                '"' -> append("%22")
                '\r' -> append("%0D")
                '\n' -> append("%0A")
                else -> append(character)
            }
        }
    }
}

/** [value] as a header value: CR and LF removed, since a header value has no escape and a line break would end it. */
internal fun multipartHeaderValue(value: String): String = value.filterNot { it == '\r' || it == '\n' }
