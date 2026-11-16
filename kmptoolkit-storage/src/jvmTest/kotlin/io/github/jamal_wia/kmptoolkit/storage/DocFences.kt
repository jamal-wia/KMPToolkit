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
 * The fence reader shared by [DocsSamplesDriftTest] and its adversarial tests. It is a reader of fences, not a CommonMark parser,
 * and it is built FAIL-CLOSED: it reads the layouts listed below, and a line it does not understand that could still hide or
 * fake a fence makes it throw, so that a doc can never pass the drift check because the reader misread it.
 *
 * Read (the layouts the reader models):
 * - **Fences.** Three or more backticks or tildes, up to three columns of indentation, an optional info string (the info string
 *   of a backtick fence holds no backtick). A fence ends at a closing line of the same character, at least as long, indented by
 *   at most three columns; what lies between is text (a `kotlin` line inside a longer outer fence is not a fence).
 * - **Containers.** A block quote (`>`) and a list item (`-`, `*`, `+`, `1.`, `1)`) open and continue as in CommonMark: a quote line
 *   needs its `>`, an item line needs the item's content indentation, a blank line continues an item and ends a quote, and a line
 *   of paragraph text without the marker or the indentation continues the quote or item when the line before it was paragraph
 *   text (a lazy continuation line). A fence may open on the line that opens a container (`> ```kotlin`, `- ```kotlin`). A fence
 *   that is still open when its container ends, or that is never closed, throws with `file:line`.
 * - **Indented code.** A line indented by four or more columns (relative to its container) that does not continue a paragraph
 *   is an indented code block: never a fence, never a container, never a closing line.
 * - **Thematic breaks and headings** (`* * *`, `- - -`, `---`, `#`) open no container; a bare list marker is an empty item.
 *
 * Refused (each throws `IllegalStateException` that names `file:line`, and no input makes the reader throw anything else):
 * - **A fence-like line that is not read.** Every line outside a fence body that contains three or more backticks or tildes and
 *   that the reader did not consume as a fence opening or as an indented code block throws
 *   `file:line: unrecognised fence-like line`: three backticks inside a sentence (inline code), in a heading, in a table row or in
 *   a paragraph continuation, an opening line whose info string holds a backtick, a fence inside an HTML block.
 * - **An HTML block that holds a fence-like line.** A line that starts an HTML block (`<` and a tag, `<!--`, `<?`, `<!X`,
 *   `<![CDATA[`) is followed by lines that CommonMark does not read as fences; the reader does not model where the block ends
 *   except by its end condition (the comment end, the closing tag of `script`, `pre`, `style` or `textarea`, else a blank line),
 *   and refuses a fence-like line before then.
 * - **A tab in the indentation or after a container marker.** Tab stops are not modelled, so a tab there (`-<tab>text`,
 *   `><tab>text`, a tab-indented line) throws; a fence-like line with a tab there throws as an unrecognised fence-like line.
 *
 * What the reader does NOT do: parse the rest of CommonMark (setext headings, link reference definitions, tables, inline syntax),
 * so a doc may not rely on any of it to hide or to show a fence.
 */
internal object DocFences {

    private val fenceLine: Regex = Regex("""^(`{3,}|~{3,})(.*)$""")
    private val fenceRun: Regex = Regex("""`{3,}|~{3,}""")
    private val quoteMarker: Regex = Regex("""^ {0,3}>""")
    private val listMarker: Regex = Regex("""^([-*+]|\d{1,9}[.)])( *)(.*)$""")
    private val thematicBreak: Regex = Regex("""^([-*_])( *\1){2,} *$""")
    private val atxHeading: Regex = Regex("""^#{1,6}([ \t]|$)""")
    private val htmlStart: Regex = Regex("""^<[A-Za-z/!?]""")
    private val rawTextTag: Regex = Regex("""^<(script|pre|style|textarea)([ \t>]|$)""", RegexOption.IGNORE_CASE)

    /** A tab reached through spaces, block quote markers and list markers only: tab stops are not modelled, so such a line is refused. */
    private val tabbedPrefix: Regex = Regex("""^(?:[ >]|[-*+](?=[ \t])|\d{1,9}[.)](?=[ \t]))*\t""")

    /** An open block quote ([quote]) or list item, whose content starts at [contentColumns]. */
    private class Container(val quote: Boolean, val contentColumns: Int)

    /** An HTML block that began at [line] and has not ended: at a line containing [terminator], or at a blank line when it is null. */
    private class HtmlBlock(val line: Int, val terminator: String?)

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

    /**
     * Every fence of the file, in order. A fence that is never closed, or whose container ends first, and every line the reader
     * refuses (see the class KDoc) is a defect of the document and throws an [IllegalStateException] that starts with `file:line`.
     */
    fun scan(file: String, lines: List<String>): List<DocFence> {
        val result: MutableList<DocFence> = mutableListOf()
        val containers: MutableList<Container> = mutableListOf()
        var open: OpenFence? = null
        var html: HtmlBlock? = null
        // True when the previous line, outside a fence, was paragraph text: it decides lazy continuation and what an indented line is.
        var paragraph = false
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
            if (tabbedPrefix.containsMatchIn(raw)) {
                error(refused(file, index, raw, if (fenceRun.containsMatchIn(raw)) "fence-like line" else "line", "a tab in the indentation or after a marker"))
            }
            val block: HtmlBlock? = html
            if (block != null) {
                if (fenceRun.containsMatchIn(raw)) {
                    error(refused(file, index, raw, "fence-like line", "inside the HTML block that starts at line ${block.line}"))
                }
                if (if (block.terminator == null) rest.isBlank() else raw.contains(block.terminator, ignoreCase = true)) html = null
                continue
            }
            if (matched < containers.size && paragraph && isLazy(rest)) {
                if (fenceRun.containsMatchIn(raw)) error(refused(file, index, raw, "fence-like line", "in a paragraph that continues a quote or an item"))
                continue
            }
            while (containers.size > matched) containers.removeAt(containers.size - 1)
            while (columns(rest) < 4) {
                val opened: Pair<Container, String> = openContainer(rest) ?: break
                containers.add(opened.first)
                rest = opened.second
            }
            open = openingFence(rest, index, raw, if (index > 0) lines[index - 1].trim() else "", containers.isNotEmpty())
            if (open != null) {
                paragraph = false
                continue
            }
            val indented: Boolean = columns(rest) >= 4
            val trimmed: String = rest.trim()
            val started: HtmlBlock? = if (indented) null else htmlBlockStart(index + 1, trimmed)
            if (started != null && (started.terminator == null || trimmed.indexOf(started.terminator, 1, ignoreCase = true) < 0)) html = started
            // Indented code that does not continue a paragraph is the only fence-like line the reader leaves alone.
            if (!(indented && !paragraph) && fenceRun.containsMatchIn(raw)) error(refused(file, index, raw, "fence-like line", "not a fence opening"))
            paragraph = trimmed.isNotEmpty() && started == null && !leafBlock(trimmed) && (!indented || paragraph)
        }
        open?.let { error(neverClosed(file, it, "")) }
        return result
    }

    private fun neverClosed(file: String, fence: OpenFence, why: String): String =
        "$file:${fence.line}: the fence opened by '${fence.opening}' is never closed$why"

    private fun refused(file: String, index: Int, raw: String, what: String, why: String): String =
        "$file:${index + 1}: unrecognised $what ($why): '${raw.trim()}'"

    /** [line] with the marker or the indentation of [container] removed, or null when the line does not continue it. */
    private fun continued(line: String, container: Container): String? = when {
        container.quote -> quoteMarker.find(line)?.let { match: MatchResult -> line.substring(match.value.length).removePrefix(" ") }
        line.isBlank() -> ""
        columns(line) >= container.contentColumns -> dropColumns(line, container.contentColumns)
        else -> null
    }

    /** True for a heading or a thematic break: a line that is not paragraph text and cannot be lazy continuation. */
    private fun leafBlock(trimmed: String): Boolean = atxHeading.containsMatchIn(trimmed) || thematicBreak.matches(trimmed)

    /** True when [rest], the part of a line left after the containers it continues, is paragraph text that continues the open paragraph. */
    private fun isLazy(rest: String): Boolean {
        if (rest.isBlank()) return false
        val trimmed: String = rest.trim()
        if (leafBlock(trimmed) || htmlBlockStart(0, trimmed) != null) return false
        return columns(rest) >= 4 || (openContainer(rest) == null && fenceLine.matchEntire(trimmed) == null)
    }

    /** The HTML block that [text] (trimmed, outside a fence) starts, with its end condition (CommonMark's seven start conditions, merged), or null. */
    private fun htmlBlockStart(line: Int, text: String): HtmlBlock? {
        if (!htmlStart.containsMatchIn(text)) return null
        val terminator: String? = when {
            text.startsWith("<!--") -> "-->"
            text.startsWith("<?") -> "?>"
            text.startsWith("<![CDATA[") -> "]]>"
            text.startsWith("<!") -> ">"
            else -> rawTextTag.find(text)?.let { match: MatchResult -> "</${match.groupValues[1]}>" }
        }
        return HtmlBlock(line, terminator)
    }

    /** The container a block quote marker or a list marker at the start of [line] opens, with what is left of the line; null for neither. */
    private fun openContainer(line: String): Pair<Container, String>? {
        val indent: Int = columns(line)
        val stripped: String = line.trimStart(' ', '\t')
        // CommonMark: a thematic break (`* * *`, `- - -`) is not a list item.
        if (thematicBreak.matches(stripped.trimEnd())) return null
        if (stripped.startsWith(">")) return Container(true, 0) to stripped.substring(1).removePrefix(" ")
        val match: MatchResult = listMarker.matchEntire(stripped) ?: return null
        val marker: String = match.groupValues[1]
        val spaces: Int = match.groupValues[2].length
        val text: String = match.groupValues[3]
        if (spaces == 0 && text.isNotEmpty()) return null
        // CommonMark: one to four spaces after the marker belong to it; five or more make the content an indented code block;
        // a bare marker is an empty item whose content starts one column after it.
        val gap: Int = if (text.isEmpty() || spaces > 4) 1 else spaces
        return Container(false, indent + marker.length + gap) to " ".repeat(maxOf(0, spaces - gap)) + text
    }

    private fun openingFence(rest: String, index: Int, raw: String, marker: String, inContainer: Boolean): OpenFence? {
        if (columns(rest) >= 4) return null
        val trimmed: String = rest.trim()
        val match: MatchResult = fenceLine.matchEntire(trimmed) ?: return null
        val fence: String = match.groupValues[1]
        val info: String = match.groupValues[2]
        // CommonMark: the info string of a backtick fence holds no backtick, so ```x``` on one line is inline code (then refused).
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
