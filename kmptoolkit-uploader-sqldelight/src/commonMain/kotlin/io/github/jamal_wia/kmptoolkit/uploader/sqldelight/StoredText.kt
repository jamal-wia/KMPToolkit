package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

/**
 * How a text is stored in a TEXT column so that every `String` comes back as it went in, on both drivers.
 *
 * SQLite and its drivers change some texts: the Android driver binds UTF-16 and SQLite drops a leading U+FEFF, reads a
 * leading U+FFFE as a byte-order mark and mangles a lone surrogate; the iOS driver binds UTF-8, so a NUL ends the text,
 * a leading U+FEFF is dropped and a lone surrogate becomes U+FFFD. [toStoredText] leaves a text that survives
 * untouched (every text a version before 4.1.0 stored stays as it is) and rewrites only the others, behind [MARKER], with
 * each offending UTF-16 code unit as [ESCAPE] and four hex digits; [fromStoredText] reverses it. A stored text that
 * starts with [MARKER] but is not well formed is returned as it is.
 */
private const val MARKER: String = "﷐kmp1﷑"

private const val ESCAPE: Char = '﷑'

private const val HEX_WIDTH: Int = 4

private const val NUL: Char = '\u0000'

private const val BYTE_ORDER_MARK: Char = '﻿'

private const val REVERSED_BYTE_ORDER_MARK: Char = '￾'

/** [text] in the form that is stored; the identity for every text the drivers keep as it is. */
internal fun toStoredText(text: String): String {
    if (!needsEscaping(text)) return text
    return buildString {
        append(MARKER)
        text.forEachIndexed { index, c ->
            if (mustEscape(text, index)) {
                append(ESCAPE).append(c.code.toString(16).padStart(HEX_WIDTH, '0'))
            } else {
                append(c)
            }
        }
    }
}

/** [toStoredText] of a nullable text. */
internal fun toStoredTextOrNull(text: String?): String? = text?.let { toStoredText(it) }

/** The text that [stored] holds: the inverse of [toStoredText]. */
internal fun fromStoredText(stored: String): String {
    if (!stored.startsWith(MARKER)) return stored
    val out = StringBuilder()
    var i: Int = MARKER.length
    while (i < stored.length) {
        val c: Char = stored[i]
        if (c != ESCAPE) {
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

/** [fromStoredText] of a nullable text. */
internal fun fromStoredTextOrNull(stored: String?): String? = stored?.let { fromStoredText(it) }

private fun needsEscaping(text: String): Boolean =
    text.startsWith(MARKER) || text.firstOrNull().let { it == BYTE_ORDER_MARK || it == REVERSED_BYTE_ORDER_MARK } ||
        text.indices.any { mustEscape(text, it) }

/** Whether the unit at [index] is one a driver mangles (a NUL, a lone surrogate), is [ESCAPE], or leads the text as a mark. */
private fun mustEscape(text: String, index: Int): Boolean {
    val c: Char = text[index]
    return when {
        c == NUL || c == ESCAPE -> true
        index == 0 && (c == BYTE_ORDER_MARK || c == REVERSED_BYTE_ORDER_MARK) -> true
        c.isHighSurrogate() -> !(index + 1 < text.length && text[index + 1].isLowSurrogate())
        c.isLowSurrogate() -> !(index > 0 && text[index - 1].isHighSurrogate())
        else -> false
    }
}
