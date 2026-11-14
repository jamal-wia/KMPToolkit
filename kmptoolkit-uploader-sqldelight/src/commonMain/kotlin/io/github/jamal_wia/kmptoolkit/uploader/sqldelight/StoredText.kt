package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

/**
 * How a text is stored in a TEXT column so that every `String` comes back as it went in, on both drivers, and so
 * that a row the queue accepted can always be read back.
 *
 * SQLite and its drivers change some texts: the Android driver binds UTF-16 and SQLite drops a leading U+FEFF, reads a
 * leading U+FFFE as a byte-order mark and mangles a lone surrogate; the iOS driver binds UTF-8, so a NUL ends the text,
 * a leading U+FEFF is dropped and a lone surrogate becomes U+FFFD. [toStoredText] leaves a text that survives
 * untouched (every text a version before 5.0.0 stored stays as it is, except one that is itself exactly an escaped form: it reads back as the text it encodes) and rewrites only the others behind
 * [MARKER]. The rewritten form is compact so that it is never more than twice as large in UTF-8 as the text it
 * holds (a NUL is one two-byte character, a lone surrogate five bytes), which is what lets [fitsStoredBudget] keep every
 * accepted row inside Android's cursor window:
 *
 * - U+0000 is stored as U+0080;
 * - U+0080 and U+0081 are stored as U+0081 followed by `0` and `1`;
 * - a leading U+FEFF or U+FFFE is stored as U+0081 followed by `m` and `n`;
 * - a lone surrogate is stored as U+0081 followed by three lower-case hex digits of its distance from U+D000.
 *
 * [fromStoredText] reverses it. A text stored by the first 5.0.0 development snapshots behind [LEGACY_MARKER] (every escaped unit as
 * U+FDD1 and four hex digits) is still read. A stored text that starts with a marker but is not exactly the
 * form the writer produces for what it decodes to (it is malformed, or a text an earlier version stored that merely starts like
 * one) is returned as it is, and the store reaches its row by that text through every lookup it has: id, tag, type and
 * type plus unique key.
 */
private const val MARKER: String = "﷐kmp2﷑"

private const val LEGACY_MARKER: String = "﷐kmp1﷑"

private const val LEGACY_ESCAPE: Char = '﷑'

private const val HEX_WIDTH: Int = 4

private const val NUL: Char = '\u0000'

private const val NUL_TOKEN: Char = '\u0080'

private const val ESCAPE: Char = '\u0081'

private const val BYTE_ORDER_MARK: Char = '﻿'

private const val REVERSED_BYTE_ORDER_MARK: Char = '￾'

private const val SURROGATE_BASE: Int = 0xD000

private const val SURROGATE_HEX_WIDTH: Int = 3

/**
 * The most UTF-8 bytes the stored text columns of one row may hold together.
 *
 * Android's `CursorWindow` is 2 MiB; a row has to fit it with its headers. A stored text is at most twice as large as
 * the text it holds plus the marker, so this is what bounds an accepted row: 2000 KiB leaves 48 KiB of room.
 */
internal const val MAX_ROW_STORED_BYTES: Int = 2_048_000

/** [text] in the form that is stored; the identity for every text the drivers keep as it is. */
internal fun toStoredText(text: String): String {
    if (!needsEscaping(text)) return text
    return buildString {
        append(MARKER)
        text.forEachIndexed { index, c ->
            when {
                c == NUL -> append(NUL_TOKEN)
                c == NUL_TOKEN -> append(ESCAPE).append('0')
                c == ESCAPE -> append(ESCAPE).append('1')
                index == 0 && c == BYTE_ORDER_MARK -> append(ESCAPE).append('m')
                index == 0 && c == REVERSED_BYTE_ORDER_MARK -> append(ESCAPE).append('n')
                isLoneSurrogate(text, index) ->
                    append(ESCAPE).append((c.code - SURROGATE_BASE).toString(16).padStart(SURROGATE_HEX_WIDTH, '0'))

                else -> append(c)
            }
        }
    }
}

/** Whether [text] starts with the marker of either stored form. */
internal fun startsWithStoredFormMarker(text: String): Boolean = text.startsWith(MARKER) || text.startsWith(LEGACY_MARKER)

/** [toStoredText] of a nullable text. */
internal fun toStoredTextOrNull(text: String?): String? = text?.let { toStoredText(it) }

/** The text that [stored] holds: the inverse of [toStoredText]. */
internal fun fromStoredText(stored: String): String {
    val decoded: String = when {
        stored.startsWith(MARKER) -> decode(stored)
        stored.startsWith(LEGACY_MARKER) -> decodeLegacy(stored)
        else -> return stored
    }
    // A text is decoded only when it is exactly what the writer produces for the decoded text: a text an earlier
    // version stored that merely starts with a marker is not, and is returned as it is.
    val canonical: Boolean = if (stored.startsWith(MARKER)) toStoredText(decoded) == stored else encodeLegacy(decoded) == stored
    return if (canonical) decoded else stored
}

private fun decode(stored: String): String {
    val out = StringBuilder()
    var i: Int = MARKER.length
    while (i < stored.length) {
        val c: Char = stored[i]
        when (c) {
            NUL_TOKEN -> {
                out.append(NUL)
                i += 1
            }

            ESCAPE -> {
                val selector: Char = stored.getOrNull(i + 1) ?: return stored
                when (selector) {
                    '0' -> out.append(NUL_TOKEN).also { i += 2 }
                    '1' -> out.append(ESCAPE).also { i += 2 }
                    'm' -> out.append(BYTE_ORDER_MARK).also { i += 2 }
                    'n' -> out.append(REVERSED_BYTE_ORDER_MARK).also { i += 2 }
                    else -> {
                        val hex: String = stored.substring(i + 1, minOf(i + 1 + SURROGATE_HEX_WIDTH, stored.length))
                        val distance: Int = hex.takeIf { it.length == SURROGATE_HEX_WIDTH && it.all(::isLowerHex) }
                            ?.toIntOrNull(16) ?: return stored
                        val unit: Char = (SURROGATE_BASE + distance).toChar()
                        if (!unit.isSurrogate()) return stored
                        out.append(unit)
                        i += 1 + SURROGATE_HEX_WIDTH
                    }
                }
            }

            else -> {
                out.append(c)
                i += 1
            }
        }
    }
    return out.toString()
}

private fun isLowerHex(c: Char): Boolean = c in '0'..'9' || c in 'a'..'f'

/** The first 5.0.0 development snapshots' form: every offending unit is [LEGACY_ESCAPE] and four hex digits. */
private fun decodeLegacy(stored: String): String {
    val out = StringBuilder()
    var i: Int = LEGACY_MARKER.length
    while (i < stored.length) {
        val c: Char = stored[i]
        if (c != LEGACY_ESCAPE) {
            out.append(c)
            i += 1
            continue
        }
        val hex: String = stored.substring(minOf(i + 1, stored.length), minOf(i + 1 + HEX_WIDTH, stored.length))
        val unit: Int = hex.takeIf { it.length == HEX_WIDTH }?.toIntOrNull(16) ?: return stored
        out.append(unit.toChar())
        i += 1 + HEX_WIDTH
    }
    return out.toString()
}

/** The writer of the first 5.0.0 development snapshots' form, kept only to tell a text in that form from one that starts like it. */
private fun encodeLegacy(text: String): String {
    val needs: Boolean = text.startsWith(MARKER) || text.startsWith(LEGACY_MARKER) ||
        text.firstOrNull().let { it == BYTE_ORDER_MARK || it == REVERSED_BYTE_ORDER_MARK } ||
        text.indices.any { legacyMustEscape(text, it) }
    if (!needs) return text
    return buildString {
        append(LEGACY_MARKER)
        text.forEachIndexed { index, c ->
            if (legacyMustEscape(text, index)) {
                append(LEGACY_ESCAPE).append(c.code.toString(16).padStart(HEX_WIDTH, '0'))
            } else {
                append(c)
            }
        }
    }
}

private fun legacyMustEscape(text: String, index: Int): Boolean {
    val c: Char = text[index]
    return when {
        c == NUL || c == LEGACY_ESCAPE -> true
        index == 0 && (c == BYTE_ORDER_MARK || c == REVERSED_BYTE_ORDER_MARK) -> true
        else -> isLoneSurrogate(text, index)
    }
}

/** [fromStoredText] of a nullable text. */
internal fun fromStoredTextOrNull(stored: String?): String? = stored?.let { fromStoredText(it) }

/** The number of UTF-8 bytes [text] takes in the form that is stored. */
internal fun storedUtf8Length(text: String?): Int = if (text == null) 0 else utf8Length(toStoredText(text))

private fun needsEscaping(text: String): Boolean =
    text.startsWith(MARKER) || text.startsWith(LEGACY_MARKER) ||
        text.firstOrNull().let { it == BYTE_ORDER_MARK || it == REVERSED_BYTE_ORDER_MARK } ||
        text.indices.any { text[it] == NUL || isLoneSurrogate(text, it) }

/** Whether the unit at [index] is a surrogate that has no partner. */
private fun isLoneSurrogate(text: String, index: Int): Boolean {
    val c: Char = text[index]
    return when {
        c.isHighSurrogate() -> !(index + 1 < text.length && text[index + 1].isLowSurrogate())
        c.isLowSurrogate() -> !(index > 0 && text[index - 1].isHighSurrogate())
        else -> false
    }
}
