package io.github.jamal_wia.kmptoolkit.storage

/**
 * One fenced code block of a Markdown file, as CommonMark (and so GitHub) reads it: an opening line of three or more backticks or
 * tildes with an optional info string, a body, and a closing line of the same character that is at least as long as the opening.
 *
 * @property opening the opening line, trimmed
 * @property info the info string after the fence characters, trimmed (may be empty)
 * @property marker the line before the opening line, trimmed (`<!-- sample: id -->` and the like), or empty at the top of a file
 */
internal class DocFence(
    val file: String,
    val line: Int,
    val opening: String,
    val info: String,
    val marker: String,
    val body: List<String>,
) {
    /** True for a fence that a renderer shows as Kotlin, however it is spelled (`kt`, `kts`, `Kotlin`, `kotlin` with more text). */
    val isKotlinLike: Boolean
        get() {
            val lower: String = info.lowercase()
            val firstWord: String = lower.trimStart('{', '.').takeWhile { it.isLetterOrDigit() }
            return lower.contains("kotlin") || firstWord == "kt" || firstWord == "kts"
        }

    /** True only for the one spelling the sample check reads: a line that, trimmed, is exactly three backticks and `kotlin`. */
    val isExactKotlin: Boolean
        get() = opening == "```kotlin"

    val location: String
        get() = "$file:$line"
}

/**
 * The fence reader shared by [DocsSamplesDriftTest] and its adversarial test. It understands every way CommonMark opens a fence
 * (three or more backticks or tildes, a space before the info string, indentation), skips the content of a fence it is inside
 * (a `kotlin` line inside a longer outer fence is text, not a fence), and ends a fence only at a closing line of the same
 * character that is at least as long.
 */
internal object DocFences {

    private val opening: Regex = Regex("""^(`{3,}|~{3,})(.*)$""")

    /** Every fence of the file, in order. A fence that is never closed is a defect of the document and throws. */
    fun scan(file: String, lines: List<String>): List<DocFence> {
        val result: MutableList<DocFence> = mutableListOf()
        var index = 0
        while (index < lines.size) {
            val trimmed: String = lines[index].trim()
            val match: MatchResult? = opening.matchEntire(trimmed)
            val fence: String? = match?.groupValues?.get(1)
            val rest: String = match?.groupValues?.get(2).orEmpty()
            // CommonMark: the info string of a backtick fence holds no backtick, so ```x``` on one line is inline code.
            if (match == null || fence == null || (fence.first() == '`' && rest.contains('`'))) {
                index++
                continue
            }
            val body: MutableList<String> = mutableListOf()
            var end: Int = index + 1
            while (end < lines.size && !closes(lines[end].trim(), fence)) {
                body.add(lines[end])
                end++
            }
            check(end < lines.size) { "$file:${index + 1}: the fence opened by '$trimmed' is never closed" }
            result.add(DocFence(file, index + 1, trimmed, rest.trim(), if (index > 0) lines[index - 1].trim() else "", body))
            index = end + 1
        }
        return result
    }

    private fun closes(trimmed: String, fence: String): Boolean =
        trimmed.length >= fence.length && trimmed.all { it == fence.first() }

    /** The fences a renderer shows as Kotlin but that the sample check would skip because they are not spelled exactly ```` ```kotlin ````. */
    fun oddKotlinFences(file: String, lines: List<String>): List<DocFence> =
        scan(file, lines).filter { it.isKotlinLike && !it.isExactKotlin }

    /** The fences the sample check reads: exactly ```` ```kotlin ````. */
    fun kotlinFences(file: String, lines: List<String>): List<DocFence> =
        scan(file, lines).filter { it.isExactKotlin }
}
