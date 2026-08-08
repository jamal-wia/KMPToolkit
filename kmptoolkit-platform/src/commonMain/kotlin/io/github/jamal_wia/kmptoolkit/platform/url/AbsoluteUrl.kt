package io.github.jamal_wia.kmptoolkit.platform.url

/**
 * Whether [url] looks like an absolute URL — a scheme, then `:`, then something.
 *
 * Both platform openers run this first so that `""`, `"   "`, `"/help"` and `"example.com"` all
 * come back as [UrlOpenResult.INVALID_URL] from the same place, on both platforms, instead of
 * Android silently building an `Intent` with a null-scheme `Uri` and iOS returning a non-null
 * `NSURL` for a relative reference. Consistency of the *rejection* is the point.
 *
 * The check is scheme-shaped only (RFC 3986: an ASCII letter followed by letters, digits, `+`,
 * `-`, `.`), not a URL parser. Whether the rest is a URL any handler accepts is the platform's
 * question to answer, and it answers it with [UrlOpenResult.NO_HANDLER].
 */
internal fun isAbsoluteUrl(url: String): Boolean {
    val separator: Int = url.indexOf(':')
    if (separator <= 0 || separator == url.lastIndex) return false
    val scheme: String = url.substring(0, separator)
    if (!scheme.first().isAsciiLetter()) return false
    return scheme.all { ch -> ch.isAsciiLetter() || ch in '0'..'9' || ch == '+' || ch == '-' || ch == '.' }
}

private fun Char.isAsciiLetter(): Boolean = this in 'a'..'z' || this in 'A'..'Z'
