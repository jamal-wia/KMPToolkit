package io.github.jamal_wia.kmptoolkit.storage

/**
 * One fenced code block of a Markdown file, as CommonMark (and so GitHub) reads it: an opening line of three or more backticks or
 * tildes with an optional info string, a body, and a closing line of the same character that is at least as long as the opening.
 *
 * @property opening the opening line, trimmed
 * @property info the info string after the fence characters, trimmed (may be empty)
 * @property marker the line before the opening line, trimmed (`<!-- sample: id -->` and the like), or empty at the top of a file
 * @property inContainer true when the opening line sits inside a block quote or a list item (`> ```kotlin`, `- ```kotlin`, an
 *   indented fence under a list item); [opening] is then the whole trimmed line, container marker included
 */
internal class DocFence(
    val file: String,
    val line: Int,
    val opening: String,
    val info: String,
    val marker: String,
    val body: List<String>,
    val inContainer: Boolean = false,
) {
    /** True for a fence that a renderer shows as Kotlin, however it is spelled (`kt`, `kts`, `Kotlin`, `kotlin` with more text). */
    val isKotlinLike: Boolean
        get() {
            val lower: String = info.lowercase()
            val firstWord: String = lower.trimStart('{', '.').takeWhile { it.isLetterOrDigit() }
            return lower.contains("kotlin") || firstWord == "kt" || firstWord == "kts"
        }

    /**
     * True only for the one spelling the sample check reads: a line that, trimmed, is exactly three backticks and `kotlin`, outside
     * any block quote or list item (the docs keep their samples at the top level, so a Kotlin fence in a container is reported).
     */
    val isExactKotlin: Boolean
        get() = opening == "```kotlin" && !inContainer

    val location: String
        get() = "$file:$line"
}

/**
 * The fence reader shared by [DocsSamplesDriftTest] and its adversarial tests: a small CommonMark block reader that models what
 * decides whether a renderer shows a line as a fence.
 *
 * - **Fences.** Three or more backticks or tildes, up to three columns of indentation, an optional info string (the info string
 *   of a backtick fence holds no backtick, so three backticks, text and three backticks on one line are inline code). A fence
 *   ends at a closing line of the same character, at least as long, indented by at most three columns; what lies between is
 *   text (a `kotlin` line inside a longer outer fence is not a fence).
 * - **Containers.** A block quote (`>`) and a list item (`-`, `*`, `+`, `1.`, `1)`) open and continue the CommonMark way: a quote
 *   line needs its `>`, an item line needs the item's content indentation, a blank line continues an item and ends a quote. A
 *   fence may open on the line that opens a container (`> ```kotlin`, `- ```kotlin`); it is closed by a line of the same
 *   container. A fence that is still open when its container ends is a defect of the document, as is one that is never closed:
 *   both throw with `file:line`.
 * - **Indented code.** A line indented by four or more columns (relative to its container) is an indented code block or a
 *   paragraph continuation, never a fence and never a container, so fence characters shown in one cannot swallow the real fence
 *   that follows, and an over-indented line does not close a fence.
 *
 * Not modelled: lazy continuation (a text line of a quote or an item that lacks the marker or the indentation is read as ending
 * that container), HTML blocks, and tab stops (a tab counts as four columns in the leading indentation only).
 */
internal object DocFences {

    private val fenceLine: Regex = Regex("""^(`{3,}|~{3,})(.*)$""")
    private val quoteMarker: Regex = Regex("""^ {0,3}>""")
    private val listMarker: Regex = Regex("""^([-*+]|\d{1,9}[.)])( *)(.*)$""")

    /** An open block quote ([quote]) or list item, whose content starts at [contentColumns]. */
    private class Container(val quote: Boolean, val contentColumns: Int)

    private class OpenFence(
        val line: Int,
        val opening: String,
        val info: String,
        val marker: String,
        val character: Char,
        val length: Int,
        val inContainer: Boolean,
        val body: MutableList<String> = mutableListOf(),
    )

    /** Every fence of the file, in order. A fence that is never closed, or whose container ends first, is a defect of the document and throws. */
    fun scan(file: String, lines: List<String>): List<DocFence> {
        val result: MutableList<DocFence> = mutableListOf()
        val containers: MutableList<Container> = mutableListOf()
        var open: OpenFence? = null
        for ((index: Int, raw: String) in lines.withIndex()) {
            var matched = 0
            var rest: String = raw
            for (container: Container in containers) {
                rest = continued(rest, container) ?: break
                matched++
            }
            val current: OpenFence? = open
            if (current != null) {
                check(matched == containers.size) { neverClosed(file, current, " (its block quote or list item ended first)") }
                if (closes(rest, current)) {
                    result.add(DocFence(file, current.line, current.opening, current.info, current.marker, current.body, current.inContainer))
                    open = null
                } else {
                    current.body.add(rest)
                }
                continue
            }
            while (containers.size > matched) containers.removeAt(containers.size - 1)
            while (columns(rest) < 4) {
                val opened: Pair<Container, String> = openContainer(rest) ?: break
                containers.add(opened.first)
                rest = opened.second
            }
            open = openingFence(rest, index, raw, if (index > 0) lines[index - 1].trim() else "", containers.isNotEmpty())
        }
        open?.let { error(neverClosed(file, it, "")) }
        return result
    }

    private fun neverClosed(file: String, fence: OpenFence, why: String): String =
        "$file:${fence.line}: the fence opened by '${fence.opening}' is never closed$why"

    /** [line] with the marker or the indentation of [container] removed, or null when the line does not continue it. */
    private fun continued(line: String, container: Container): String? = when {
        container.quote -> quoteMarker.find(line)?.let { match: MatchResult -> line.substring(match.value.length).removePrefix(" ") }
        line.isBlank() -> ""
        columns(line) >= container.contentColumns -> dropColumns(line, container.contentColumns)
        else -> null
    }

    /** The container a block quote marker or a list marker at the start of [line] opens, with what is left of the line; null for neither. */
    private fun openContainer(line: String): Pair<Container, String>? {
        val indent: Int = columns(line)
        val stripped: String = line.trimStart(' ', '\t')
        if (stripped.startsWith(">")) return Container(true, 0) to stripped.substring(1).removePrefix(" ")
        val match: MatchResult = listMarker.matchEntire(stripped) ?: return null
        val marker: String = match.groupValues[1]
        val spaces: Int = match.groupValues[2].length
        val text: String = match.groupValues[3]
        if (spaces == 0 && text.isNotEmpty()) return null
        // CommonMark: one to four spaces after the marker belong to it; five or more make the content an indented code block.
        val gap: Int = if (text.isEmpty() || spaces > 4) 1 else spaces
        return Container(false, indent + marker.length + gap) to " ".repeat(spaces - gap) + text
    }

    private fun openingFence(rest: String, index: Int, raw: String, marker: String, inContainer: Boolean): OpenFence? {
        if (columns(rest) >= 4) return null
        val trimmed: String = rest.trim()
        val match: MatchResult = fenceLine.matchEntire(trimmed) ?: return null
        val fence: String = match.groupValues[1]
        val info: String = match.groupValues[2]
        // CommonMark: the info string of a backtick fence holds no backtick, so ```x``` on one line is inline code.
        if (fence.first() == '`' && info.contains('`')) return null
        return OpenFence(index + 1, if (inContainer) raw.trim() else trimmed, info.trim(), marker, fence.first(), fence.length, inContainer)
    }

    private fun closes(rest: String, fence: OpenFence): Boolean {
        if (columns(rest) >= 4) return false
        val trimmed: String = rest.trim()
        return trimmed.length >= fence.length && trimmed.all { it == fence.character }
    }

    /** The width of the leading white space of [line], a tab counting four. */
    private fun columns(line: String): Int {
        var width = 0
        for (character: Char in line) {
            width += when (character) {
                ' ' -> 1
                '\t' -> 4
                else -> return width
            }
        }
        return width
    }

    private fun dropColumns(line: String, count: Int): String {
        var dropped = 0
        var position = 0
        while (dropped < count && position < line.length && (line[position] == ' ' || line[position] == '\t')) {
            dropped += if (line[position] == '\t') 4 else 1
            position++
        }
        return line.substring(position)
    }

    /** The fences a renderer shows as Kotlin but that the sample check would skip because they are not spelled exactly ```` ```kotlin ````. */
    fun oddKotlinFences(file: String, lines: List<String>): List<DocFence> =
        scan(file, lines).filter { it.isKotlinLike && !it.isExactKotlin }

    /** The fences the sample check reads: exactly ```` ```kotlin ```` at the top level. */
    fun kotlinFences(file: String, lines: List<String>): List<DocFence> =
        scan(file, lines).filter { it.isExactKotlin }
}
