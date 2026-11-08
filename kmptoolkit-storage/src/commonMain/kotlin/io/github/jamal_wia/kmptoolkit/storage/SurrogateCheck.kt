package io.github.jamal_wia.kmptoolkit.storage

/**
 * True when this string holds a UTF-16 surrogate that is not half of a pair: a high surrogate not
 * followed by a low one, or a low one not preceded by a high one. Such a string is a legal Kotlin
 * value but has no encoding that every backend preserves (UTF-8 and XML turn it into `?` or drop
 * it), so the stores refuse it instead of answering Success and losing it. One pass.
 */
internal fun String.hasUnpairedSurrogate(): Boolean {
    var index = 0
    while (index < length) {
        val char: Char = this[index]
        when {
            char.isHighSurrogate() -> {
                if (index + 1 < length && this[index + 1].isLowSurrogate()) index++ else return true
            }
            char.isLowSurrogate() -> return true
        }
        index++
    }
    return false
}

/**
 * The failure every shipped store answers a `put` with when the key or the value holds an unpaired
 * surrogate, or null when both are fine. Returned before any I/O, so nothing is changed.
 */
internal fun unpairedSurrogateFailure(key: String, value: String): StorageResult.Failure? =
    if (key.hasUnpairedSurrogate() || value.hasUnpairedSurrogate()) {
        StorageResult.Failure(
            StorageError.OperationFailed(
                StorageOperation.PUT,
                key = key,
                cause = IllegalArgumentException("Unpaired UTF-16 surrogate in the key or value"),
            ),
        )
    } else {
        null
    }
