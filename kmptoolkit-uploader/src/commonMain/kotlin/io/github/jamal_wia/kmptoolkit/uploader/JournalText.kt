package io.github.jamal_wia.kmptoolkit.uploader

private const val CONTINUATION_MASK: Int = 0xC0

private const val CONTINUATION_TAG: Int = 0x80

private const val PAYLOAD_MASK: Int = 0x3F

private const val REPLACEMENT: Char = '�'

/**
 * [text] as the journal stores a string: UTF-8, with one addition. A lone surrogate, which no UTF-8 encoder can
 * represent (the JVM writes `?`, Kotlin/Native U+FFFD), is written as the three bytes of its own code unit, so a header
 * value comes back from an entry exactly as it went in on every platform. The bytes of every text without a lone
 * surrogate are exactly [encodeToByteArray]'s, so an entry written by an earlier version reads back unchanged and an
 * earlier version reads what this one writes for such a text.
 */
internal fun encodeJournalText(text: String): ByteArray {
    if (!hasLoneSurrogate(text)) return text.encodeToByteArray()
    val out = ArrayList<Byte>(text.length * 3)
    var i = 0
    while (i < text.length) {
        val c: Char = text[i]
        var codePoint: Int = c.code
        if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
            codePoint = 0x10000 + ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
            i += 1
        }
        i += 1
        when {
            codePoint < 0x80 -> out.add(codePoint.toByte())
            codePoint < 0x800 -> {
                out.add((0xC0 or (codePoint shr 6)).toByte())
                out.add((CONTINUATION_TAG or (codePoint and PAYLOAD_MASK)).toByte())
            }
            codePoint < 0x10000 -> {
                out.add((0xE0 or (codePoint shr 12)).toByte())
                out.add((CONTINUATION_TAG or ((codePoint shr 6) and PAYLOAD_MASK)).toByte())
                out.add((CONTINUATION_TAG or (codePoint and PAYLOAD_MASK)).toByte())
            }
            else -> {
                out.add((0xF0 or (codePoint shr 18)).toByte())
                out.add((CONTINUATION_TAG or ((codePoint shr 12) and PAYLOAD_MASK)).toByte())
                out.add((CONTINUATION_TAG or ((codePoint shr 6) and PAYLOAD_MASK)).toByte())
                out.add((CONTINUATION_TAG or (codePoint and PAYLOAD_MASK)).toByte())
            }
        }
    }
    return out.toByteArray()
}

/**
 * The string [encodeJournalText] wrote as [bytes]. Bytes that hold no lone-surrogate form go through the platform's
 * UTF-8 decoder, exactly as before; the others are read by a decoder of our own, the same on every platform, which
 * turns every malformed sequence into U+FFFD.
 */
internal fun decodeJournalText(bytes: ByteArray): String {
    if (!hasSurrogateForm(bytes)) return bytes.decodeToString()
    val out = StringBuilder(bytes.size)
    var i = 0
    while (i < bytes.size) {
        val lead: Int = bytes[i].toInt() and 0xFF
        val length: Int = when {
            lead < 0x80 -> 1
            lead in 0xC2..0xDF -> 2
            lead in 0xE0..0xEF -> 3
            lead in 0xF0..0xF4 -> 4
            else -> 0
        }
        if (length == 0 || i + length > bytes.size || !(1 until length).all { isContinuation(bytes[i + it]) }) {
            out.append(REPLACEMENT)
            i += 1
            continue
        }
        var codePoint: Int = when (length) {
            1 -> lead
            2 -> lead and 0x1F
            3 -> lead and 0x0F
            else -> lead and 0x07
        }
        for (k in 1 until length) codePoint = (codePoint shl 6) or (bytes[i + k].toInt() and PAYLOAD_MASK)
        val minimum: Int = when (length) {
            1 -> 0
            2 -> 0x80
            3 -> 0x800
            else -> 0x10000
        }
        if (codePoint < minimum || codePoint > 0x10FFFF) {
            out.append(REPLACEMENT)
            i += 1
            continue
        }
        if (codePoint >= 0x10000) {
            val offset: Int = codePoint - 0x10000
            out.append((0xD800 + (offset shr 10)).toChar()).append((0xDC00 + (offset and 0x3FF)).toChar())
        } else {
            out.append(codePoint.toChar())
        }
        i += length
    }
    return out.toString()
}

private fun isContinuation(byte: Byte): Boolean = (byte.toInt() and CONTINUATION_MASK) == CONTINUATION_TAG

private fun hasLoneSurrogate(text: String): Boolean {
    for (i in text.indices) {
        val c: Char = text[i]
        if (c.isHighSurrogate() && !(i + 1 < text.length && text[i + 1].isLowSurrogate())) return true
        if (c.isLowSurrogate() && !(i > 0 && text[i - 1].isHighSurrogate())) return true
    }
    return false
}

/** Whether [bytes] hold `ED A0..BF xx`, the three-byte form of a surrogate code unit. */
private fun hasSurrogateForm(bytes: ByteArray): Boolean {
    for (i in 0 until bytes.size - 1) {
        if ((bytes[i].toInt() and 0xFF) == 0xED && (bytes[i + 1].toInt() and 0xFF) >= 0xA0) return true
    }
    return false
}
