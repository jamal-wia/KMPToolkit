package io.github.jamal_wia.kmptoolkit.downloader

private const val SHA256_HEX_LENGTH: Int = 64

/** True for a SHA-256 written as hex digits, in either case — the only text a [Sha256] is made from. */
internal fun String.isSha256Hex(): Boolean =
    length == SHA256_HEX_LENGTH && all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

/** Lowercase hex, two digits per byte. */
internal fun ByteArray.toLowerHex(): String = joinToString(separator = "") { byte: Byte ->
    (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
}
