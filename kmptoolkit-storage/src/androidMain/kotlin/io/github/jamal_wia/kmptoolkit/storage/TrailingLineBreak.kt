package io.github.jamal_wia.kmptoolkit.storage

// Why this exists: AOSP's SharedPreferencesImpl writes its file through an XML serializer with
// indentation on, and that serializer appends the indentation before `</string>` whenever the text it
// just wrote ends in `\n`. A value ending in a line break (a PEM block, a JSON document with a trailing
// newline) therefore came back from disk with spaces appended after the next process start. Keys are
// attributes and the secure store writes Base64, so only the plain store's values are affected.
//
// The fix keeps the text from ending in `\n`: a value that ends in a `\n` followed by zero or more
// markers gets one more marker (U+E000, a private-use character that is legal in XML and written raw).
// Reading drops one marker from a text that ends in a `\n` followed by one or more markers. That is a
// bijection on everything this version writes: the encoded text of a value ending in `\n` + k markers
// ends in `\n` + (k + 1) markers and decodes back to k; every other value is stored and read byte for
// byte. A value that an older version already stored with its spaces (`"abc\n    "`) is returned as is,
// because the damage is ambiguous. A value an older version stored that itself ended in `\n` followed by
// U+E000 reads one marker shorter; the two cannot be told apart.

/** The private-use character appended after a trailing line break; see the note above. */
private const val LINE_BREAK_MARKER: Char = ''

/** Number of marker characters at the end of this text. */
private fun String.trailingMarkers(): Int {
    var count = 0
    while (count < length && this[length - 1 - count] == LINE_BREAK_MARKER) count++
    return count
}

/** The text to write for [value]: [value] plus one marker when it ends in `\n` and zero or more markers. */
internal fun encodeForPreferences(value: String): String {
    val markers: Int = value.trailingMarkers()
    val breakIndex: Int = value.length - markers - 1
    return if (breakIndex >= 0 && value[breakIndex] == '\n') value + LINE_BREAK_MARKER else value
}

/** The inverse of [encodeForPreferences]; text that ends in no `\n` + markers is returned unchanged. */
internal fun decodeFromPreferences(stored: String): String {
    val markers: Int = stored.trailingMarkers()
    val breakIndex: Int = stored.length - markers - 1
    return if (markers > 0 && breakIndex >= 0 && stored[breakIndex] == '\n') stored.dropLast(1) else stored
}
