package io.github.jamal_wia.kmptoolkit.platform.crash

private const val FIELD_SEPARATOR: Char = '\t'
private const val ESCAPE: Char = '\\'
private const val FIELD_COUNT: Int = 4

/**
 * Encodes [record] as a single line: four escaped, tab-separated fields, no trailing newline.
 *
 * Hand-rolled rather than JSON, for two reasons. It keeps `kotlinx-serialization` off every
 * consumer's dependency graph for the sake of four fields — and, more importantly, the encoder
 * runs inside a dying process. A crash caused by `OutOfMemoryError` is exactly when a
 * reflection-free but allocation-hungry serializer is least likely to finish; a handful of string
 * appends is about as little as this can cost.
 */
internal fun encodeCrashRecord(record: CrashRecord): String = buildString {
    append(record.timestampMs)
    append(FIELD_SEPARATOR)
    appendEscaped(record.threadName)
    append(FIELD_SEPARATOR)
    appendEscaped(record.message)
    append(FIELD_SEPARATOR)
    appendEscaped(record.stackTrace)
}

/**
 * Decodes one line produced by [encodeCrashRecord], or returns `null` when it cannot.
 *
 * `null` rather than an exception, because the caller is reading a file that a killed process may
 * have left half-written: a truncated final line is a normal thing to find, and it must cost the
 * reader that line only. An unparseable timestamp, a wrong field count and a blank line all take
 * the same path.
 */
internal fun decodeCrashRecord(line: String): CrashRecord? {
    val fields: List<String> = splitEscaped(line)
    if (fields.size != FIELD_COUNT) return null
    val timestampMs: Long = fields[0].toLongOrNull() ?: return null
    return CrashRecord(
        timestampMs = timestampMs,
        threadName = fields[1],
        message = fields[2],
        stackTrace = fields[3],
    )
}

/**
 * Appends [value] with the separator, the escape character and every line break neutralised, so
 * that a stack trace — which is nothing but line breaks — still occupies exactly one line.
 */
private fun StringBuilder.appendEscaped(value: String) {
    value.forEach { ch ->
        when (ch) {
            ESCAPE -> append(ESCAPE).append(ESCAPE)
            FIELD_SEPARATOR -> append(ESCAPE).append('t')
            '\n' -> append(ESCAPE).append('n')
            '\r' -> append(ESCAPE).append('r')
            else -> append(ch)
        }
    }
}

/**
 * Splits an encoded line back into its fields, undoing [appendEscaped].
 *
 * A trailing lone escape character — the signature of a line truncated mid-escape — is dropped,
 * which makes the field count wrong and lets [decodeCrashRecord] reject the line rather than
 * inventing a character that was never written.
 */
private fun splitEscaped(line: String): List<String> {
    val fields: MutableList<String> = mutableListOf()
    val current: StringBuilder = StringBuilder()
    var index = 0
    while (index < line.length) {
        when (val ch: Char = line[index]) {
            FIELD_SEPARATOR -> {
                fields.add(current.toString())
                current.clear()
            }

            ESCAPE -> {
                index++
                if (index >= line.length) return emptyList()
                when (line[index]) {
                    ESCAPE -> current.append(ESCAPE)
                    't' -> current.append(FIELD_SEPARATOR)
                    'n' -> current.append('\n')
                    'r' -> current.append('\r')
                    else -> return emptyList()
                }
            }

            else -> current.append(ch)
        }
        index++
    }
    fields.add(current.toString())
    return fields
}
