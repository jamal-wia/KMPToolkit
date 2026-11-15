package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CharsetEncoder
import java.nio.charset.CoderResult
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
 *
 * Exactly one array is ever made: its size is counted first ([strictUtf8Length]) and the encoder writes straight into it.
 * `CharsetEncoder.encode(CharBuffer)` would grow its buffer by copying and drop the smaller copies unwiped, which for a
 * secret leaves bytes of it on the heap that nothing can reach. A text that has no UTF-8 form wipes the array before it is
 * dropped, so no partial encoding of a secret survives the failure.
 */
internal fun CharSequence.toStrictUtf8(): ByteArray {
    val size: Long = strictUtf8Length()
    require(size <= Int.MAX_VALUE) { "The text is too long to encode" }
    val bytes = ByteArray(size.toInt())
    try {
        val input: CharBuffer = CharBuffer.wrap(this)
        val output: ByteBuffer = ByteBuffer.wrap(bytes)
        val encoder: CharsetEncoder = StandardCharsets.UTF_8.newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        var result: CoderResult = encoder.encode(input, output, true)
        if (result.isUnderflow) result = encoder.flush(output)
        if (result.isError) result.throwException()
        check(result.isUnderflow && !input.hasRemaining() && !output.hasRemaining()) { "The UTF-8 length of the text changed while it was encoded" }
        return bytes
    } catch (_: CharacterCodingException) {
        bytes.fill(0)
        throw IllegalArgumentException("The text holds an unpaired UTF-16 surrogate and has no UTF-8 form")
    } catch (error: Throwable) {
        bytes.fill(0)
        throw error
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
