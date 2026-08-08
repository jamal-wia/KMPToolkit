package io.github.jamal_wia.kmptoolkit.platform.files

/**
 * The outcome of a [FilePicker.pick] call.
 *
 * A sealed hierarchy rather than an enum because two of the outcomes carry data a caller needs:
 * the file itself, and the size that blew the cap. Cancellation is a first-class, expected
 * outcome — most picker invocations end that way — not an error to report.
 */
public sealed interface PickResult {

    /** The user chose a file and it was read successfully. */
    public data class Picked(val file: PickedFile) : PickResult

    /**
     * The user dismissed the picker without choosing.
     *
     * The normal, quiet path: show nothing, change nothing.
     */
    public data object Cancelled : PickResult

    /**
     * The chosen file is larger than [FilePickerConfig.maxBytes] and was **not** read into memory.
     *
     * @property sizeBytes what the platform reported the file's size to be.
     * @property maxBytes the limit that was in effect, so a message can name it without the
     *   caller having to reach back into its own config.
     */
    public data class TooLarge(val sizeBytes: Long, val maxBytes: Long) : PickResult

    /**
     * The picker could not be shown at all.
     *
     * On Android, no picker host is wired up (see `createFilePicker`); on iOS, there is no key
     * window with a root view controller to present from — typically because the app is in the
     * background. This is a wiring or timing problem, not something the user did.
     */
    public data object Unavailable : PickResult

    /**
     * The user chose a file but it could not be read — permission revoked between choosing and
     * reading, the provider backing the URI died, a network-backed document that went away.
     *
     * @property cause the underlying failure when the platform gave one, `null` when it merely
     *   returned nothing.
     */
    public data class Failed(val cause: Throwable?) : PickResult
}

/**
 * A file the user picked, already in memory.
 *
 * Bytes rather than a path or a URI: a platform path is not portable to shared code, and a URI's
 * read grant can expire, so the value handed across the seam is the content itself. The size cap
 * in [FilePickerConfig] is what keeps that affordable.
 *
 * @property name the file name the OS reported, e.g. `"report.pdf"`. Display it, but never trust
 *   it as a path: it is user-controlled and may contain separators or `..`.
 * @property mimeTypeHint what the OS *claimed* the type is — a hint, as the name says. It comes
 *   from a file extension or a content-provider column, both of which the user controls. Sniff
 *   [bytes] before doing anything that depends on the type being real.
 * @property bytes the file content.
 */
public class PickedFile(
    public val name: String,
    public val mimeTypeHint: String,
    public val bytes: ByteArray,
) {

    /** The content length, in bytes. */
    public val sizeBytes: Long get() = bytes.size.toLong()

    /** Structural equality, comparing [bytes] by content rather than by reference. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PickedFile) return false
        return name == other.name &&
            mimeTypeHint == other.mimeTypeHint &&
            bytes.contentEquals(other.bytes)
    }

    /** Content-based hash, consistent with [equals]. */
    override fun hashCode(): Int {
        var result: Int = name.hashCode()
        result = 31 * result + mimeTypeHint.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }

    /**
     * Describes the file without its content.
     *
     * The bytes are deliberately left out: a picked file is user data, and a `toString` that
     * dumps it would put a document's content into any log that ever prints this object.
     */
    override fun toString(): String =
        "PickedFile(name=$name, mimeTypeHint=$mimeTypeHint, sizeBytes=$sizeBytes)"
}
