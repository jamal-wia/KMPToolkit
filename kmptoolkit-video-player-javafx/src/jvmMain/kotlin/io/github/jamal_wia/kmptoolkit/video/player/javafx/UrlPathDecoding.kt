package io.github.jamal_wia.kmptoolkit.video.player.javafx

import java.io.ByteArrayOutputStream

/**
 * A percent-encoded URL path decoded the way a JDK class loader encodes it.
 *
 * The JDK encodes a character outside the Basic Multilingual Plane (an emoji, a CJK extension B ideograph) one
 * UTF-16 surrogate at a time, so the three-byte sequences `%ed%a0%bd%ed%b8%80` stand for one character and the
 * text is not valid UTF-8. A strict decoder (`Paths.get(URI)`, the JDK's own jar handler, JavaFX) turns those
 * bytes into U+FFFD or rejects them. [text] is the path with every such pair joined, and [repaired] says
 * whether any was found.
 */
internal class DecodedUrlPath(val text: String, val repaired: Boolean)

/**
 * Decodes the raw (still percent-encoded) path [raw], accepting both UTF-8 and the per-surrogate form of the
 * JDK. `null` when it is not decodable at all (a stray `%`, bytes that are no character), in which case the
 * caller keeps the path as written.
 */
internal fun decodeUrlPath(raw: String): DecodedUrlPath? {
    val out = StringBuilder(raw.length)
    val run = ByteArrayOutputStream()
    var repaired = false

    fun flush(): Boolean {
        if (run.size() == 0) return true
        val bytes: ByteArray = run.toByteArray()
        run.reset()
        var i = 0
        while (i < bytes.size) {
            val lead: Int = bytes[i].toInt() and 0xff
            val length: Int
            var codePoint: Int
            when {
                lead < 0x80 -> { length = 1; codePoint = lead }
                lead in 0xC2..0xDF -> { length = 2; codePoint = lead and 0x1f }
                lead in 0xE0..0xEF -> { length = 3; codePoint = lead and 0x0f }
                lead in 0xF0..0xF4 -> { length = 4; codePoint = lead and 0x07 }
                else -> return false
            }
            if (i + length > bytes.size) return false
            for (k in 1 until length) {
                val next: Int = bytes[i + k].toInt() and 0xff
                if (next and 0xC0 != 0x80) return false
                codePoint = (codePoint shl 6) or (next and 0x3f)
            }
            when (length) {
                3 -> {
                    if (codePoint < 0x800) return false
                    if (codePoint in 0xD800..0xDFFF) repaired = true
                    out.append(codePoint.toChar())
                }
                4 -> {
                    if (codePoint !in 0x10000..0x10FFFF) return false
                    out.appendCodePoint(codePoint)
                }
                else -> out.append(codePoint.toChar())
            }
            i += length
        }
        return true
    }

    var index = 0
    while (index < raw.length) {
        val c: Char = raw[index]
        if (c == '%') {
            val high: Int = if (index + 2 < raw.length) Character.digit(raw[index + 1], 16) else -1
            val low: Int = if (index + 2 < raw.length) Character.digit(raw[index + 2], 16) else -1
            if (high < 0 || low < 0) return null
            run.write(high * 16 + low)
            index += 3
        } else {
            if (!flush()) return null
            out.append(c)
            index++
        }
    }
    if (!flush()) return null
    val text: String = out.toString()
    var k = 0
    while (k < text.length) {
        val ch: Char = text[k]
        if (Character.isHighSurrogate(ch) && k + 1 < text.length && Character.isLowSurrogate(text[k + 1])) k += 2
        else if (Character.isSurrogate(ch)) return null
        else k++
    }
    return DecodedUrlPath(text, repaired)
}
