package io.github.jamal_wia.kmptoolkit.uploader

/**
 * The longest failure text, in characters, the engine hands to a store before it appends the truncation
 * suffix. A persisted `lastError` is a short diagnostic: an unbounded one (an exception message that embeds a
 * response body, say) can make a row too large to read back and with it the whole queue unreadable.
 */
internal const val MAX_FAILURE_TEXT_CHARS: Int = 4096

/**
 * `"<exception class>: <message>"` for [cause], bounded by [boundedFailureText], or `null` for `null`.
 *
 * The message getter is consumer code: it is read inside a guard that swallows every throwable (a getter is no
 * suspension point, so a `CancellationException` from it is the getter's own failure), and a getter that throws
 * yields a text saying so instead of costing the caller its bookkeeping.
 */
internal fun describeFailure(cause: Throwable?): String? {
    if (cause == null) return null
    val name: String = cause::class.simpleName ?: "Throwable"
    val message: String = try {
        cause.message.toString()
    } catch (_: Throwable) {
        "<message unavailable>"
    }
    return boundedFailureText("$name: $message")
}

/**
 * [text] unchanged when it fits in [MAX_FAILURE_TEXT_CHARS]; otherwise cut to that length (one character less
 * when the cut would split a surrogate pair) plus a `"… [truncated N chars]"` suffix, so the result may be
 * slightly longer than the limit. `null` stays `null`.
 */
internal fun boundedFailureText(text: String?): String? {
    if (text == null || text.length <= MAX_FAILURE_TEXT_CHARS) return text
    var keep: Int = MAX_FAILURE_TEXT_CHARS
    if (text[keep - 1].isHighSurrogate()) keep--
    return text.substring(0, keep) + "… [truncated ${text.length - keep} chars]"
}
