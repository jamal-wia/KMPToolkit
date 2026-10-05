package io.github.jamal_wia.kmptoolkit.downloader

/**
 * A SHA-256 digest: exactly 64 hexadecimal digits, held in lowercase.
 *
 * A type rather than a bare `String` so that a malformed hash cannot travel through the catalogue
 * unnoticed: a value that is not 64 hex digits never becomes a [Sha256], and every holder of one
 * can compare it without re-validating. Two instances are equal when they hold the same digest,
 * whichever case the text they were parsed from used.
 *
 * Which factory to call depends on where the text comes from:
 * - [parse] for a value the host wrote itself (a constant in its catalogue). A malformed constant
 *   is a programming error and throws immediately, at the place that wrote it.
 * - [parseOrNull] for a value that arrived from a backend. A hash that is absent or garbled there
 *   must never fail the download — it only means "no hash to check against" — so this returns
 *   `null` instead of throwing.
 */
public class Sha256 private constructor(
    /** The digest as 64 lowercase hex digits. */
    public val hex: String,
) {

    override fun equals(other: Any?): Boolean = other is Sha256 && other.hex == hex

    override fun hashCode(): Int = hex.hashCode()

    override fun toString(): String = "Sha256($hex)"

    public companion object {

        /**
         * Parses [hex], 64 hex digits in either case.
         *
         * @throws IllegalArgumentException when [hex] is not exactly 64 hexadecimal digits.
         */
        public fun parse(hex: String): Sha256 {
            require(hex.isSha256Hex()) {
                "A SHA-256 is exactly 64 hex digits, was ${hex.length} characters: '$hex'"
            }
            return Sha256(hex.lowercase())
        }

        /**
         * Parses [hex] like [parse], but answers `null` for `null`, for blank text and for anything
         * that is not 64 hex digits. The call for a value read from a backend response, where an
         * absent or garbled hash means "no check" and must never fail the download.
         */
        public fun parseOrNull(hex: String?): Sha256? =
            if (hex != null && hex.isSha256Hex()) Sha256(hex.lowercase()) else null
    }
}
