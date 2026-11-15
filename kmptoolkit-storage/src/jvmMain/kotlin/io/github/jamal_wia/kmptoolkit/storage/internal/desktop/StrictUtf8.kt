package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * The UTF-8 bytes of this text, or [IllegalArgumentException] when the text has no UTF-8 form (an unpaired UTF-16
 * surrogate).
 *
 * Format v1 freezes "UTF-8" for entry names and "NFC, then UTF-8" for passphrases. The JDK's own conversions
 * (`String.toByteArray`, `Charset.encode`, PBEKeySpec) silently replace an unencodable character with `?`, which would
 * make `x?` and `x\uD800` one name or one key; this is the only conversion the format layer uses, so that can never
 * happen. The message never contains the text (it may be a passphrase). The returned array is owned by the caller.
 */
internal fun CharSequence.toStrictUtf8(): ByteArray {
    try {
        val encoded: ByteBuffer = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .encode(CharBuffer.wrap(this))
        val bytes = ByteArray(encoded.remaining())
        encoded.get(bytes)
        if (encoded.hasArray()) encoded.array().fill(0)
        return bytes
    } catch (_: CharacterCodingException) {
        throw IllegalArgumentException("The text holds an unpaired UTF-16 surrogate and has no UTF-8 form")
    }
}

/**
 * How many bytes [toStrictUtf8] returns for this text, counted without building them (a size limit must be checked before a
 * large value is encoded, sealed and copied). Text that has a UTF-8 form is counted exactly; a character that has none (an
 * unpaired surrogate, which callers refuse separately) counts 3 bytes, so the answer is never below the real size of
 * the nearest encodable text.
 */
internal fun CharSequence.strictUtf8Length(): Long {
    var bytes: Long = 0
    var index = 0
    while (index < length) {
        val char: Char = this[index]
        when {
            char.code < 0x80 -> bytes += 1
            char.code < 0x800 -> bytes += 2
            char.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate() -> {
                bytes += 4
                index++
            }
            else -> bytes += 3
        }
        index++
    }
    return bytes
}

/** The text these bytes are as strict UTF-8, or `null` when they are not valid UTF-8 (a replacement would change the value). */
internal fun ByteArray.decodeStrictUtf8(): String? = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this)).toString()
} catch (_: CharacterCodingException) {
    null
}
