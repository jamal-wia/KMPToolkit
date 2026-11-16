package io.github.jamal_wia.kmptoolkit.storage

/**
 * One fenced code block of a Markdown file, as [DocFences] read it: an opening line, a body, and a closing line.
 *
 * @property opening the opening line as written, without a trailing carriage return (for a [recognised] fence its indentation, three
 *   backticks and the info string; for a line the reader refused, that line without surrounding spaces, tabs and carriage returns)
 * @property info the info string after the fence characters (for a refused line, the text after its first run of fence characters)
 * @property marker the line before the opening line without surrounding spaces, tabs and carriage returns (`<!-- sample: id -->`
 *   and the like), or empty at the top of a file
 * @property recognised false for a line the reader refused and reports as a fence-like line (see [DocFences.oddKotlinFences]),
 *   true for a fence the reader read
 */
internal class DocFence(
    val file: String,
    val line: Int,
    val opening: String,
    val info: String,
    val marker: String,
    val body: List<String>,
    val recognised: Boolean = true,
) {
    /** True for a fence that a renderer shows as Kotlin, however it is spelled (`kt`, `kts`, `Kotlin`, `kotlin` with more text). */
    val isKotlinLike: Boolean
        get() {
            val lower: String = info.lowercase()
            val firstWord: String = lower.trimStart('{', '.').takeWhile { it.isLetterOrDigit() }
            return lower.contains("kotlin") || firstWord == "kt" || firstWord == "kts"
        }

    /** True only for the one spelling the sample check reads: a recognised fence whose opening line is exactly three backticks and `kotlin`. */
    val isExactKotlin: Boolean
        get() = recognised && opening == "```kotlin"

    val location: String
        get() = "$file:$line"
}

/**
 * The fence reader shared by [DocsSamplesDriftTest] and its adversarial tests. It is NOT a Markdown or CommonMark reader. It is
 * correct by construction: it accepts only the two line layouts the real docs use, byte for byte, and it refuses every other
 * line that could be, or could hide, a fence.
 *
 * Accepted, nothing else:
 * - **An opening line**: some ASCII spaces (none in every real doc), then exactly three backticks, then an info string of ASCII
 *   letters and digits, possibly empty (`kotlin`, `bash`, or nothing). The spaces are the fence's indentation.
 * - **A closing line** inside a fence: the same spaces and exactly three backticks. Outside a fence the same line is an opening
 *   line with an empty info string.
 * - One trailing carriage return is dropped from a line before it is compared (a document split on `\n` only keeps one).
 *
 * Only an unindented ```` ```kotlin ```` is a sample ([DocFence.isExactKotlin]); an indented one is read, so that it is reported
 * (a renderer draws it as Kotlin when it sits in a list item) instead of refused without a reason.
 *
 * Refused (the line is reported with `file:line`, and [scan] throws an [IllegalStateException] for it): every line that holds a run
 * of three or more backticks or three or more tildes and is not one of those layouts, wherever it is: outside a fence or in a fence
 * body, whatever precedes the run (a tab, `>`, a list marker, a no-break space, text), whatever follows it, inline code, a heading,
 * a table row, an HTML block, four backticks, tildes, a closing line with other indentation or trailing characters. A fence that is
 * never closed is refused too.
 *
 * Everything else is not looked at: a line without such a run is body text or plain text. Block quotes, list items, lazy
 * continuation, HTML blocks, indented code and tab stops are not modelled, because nothing that carries a fence-like run is
 * accepted in them. The scan has one state (inside a fence with a given indentation, or not), reads each line once with plain loops (no regular
 * expressions, no recursion, so a line of any length costs linear time and cannot overflow the stack), and treats only the ASCII
 * space, tab and carriage return as white space.
 *
 * What this guarantees: every line of a document that holds a fence-like run is either read as an opening or closing line of a
 * fence or refused. A fence that a renderer shows as Kotlin has a line with such a run, so it is read (and the sample check sees it)
 * or refused; it cannot be skipped, and no fence can swallow another, because a run inside a fence body that is not the closing
 * line is refused. What it does not guarantee: that a renderer shows what was read (an accepted line inside an HTML comment is read as a fence
 * a renderer does not draw; that can only fail the sample check loudly, never pass a snippet unchecked).
 *
 * [scan] is the strict reader and the authority: [DocsSamplesDriftTest] fails on whatever it throws. [oddKotlinFences] and
 * [kotlinFences] are reports built on the same single pass; they never throw and carry on after a refused line (a refused line
 * inside a fence that has the shape of an opening line ends that fence as never closed and opens a new one, so one stray line does
 * not hide the rest of the file from the report).
 */
internal object DocFences {

    private const val FENCE: String = "```"

    /**
     * One line the reader refused: [text] is the line as written, [neverClosed] is true for a fence that was left open (at the end of the
     * file, or by a line that opens a new one), [reopened] for a refused line that the report then reads as the opening of a new fence.
     */
    private class Refusal(val line: Int, val text: String, val why: String, val neverClosed: Boolean, val reopened: Boolean = false)

    /** What one pass over a file found: the fences it read (in order) and the lines it refused (in line order). */
    private class Reading(val fences: List<DocFence>, val refusals: List<Refusal>)

    private class OpenFence(
        val line: Int,
        val opening: String,
        val info: String,
        val marker: String,
        val closing: String,
        val body: MutableList<String> = mutableListOf(),
    )

    /**
     * Every fence of the file, in order. A refused line (see the class KDoc) or a fence that is never closed is a defect of the
     * document and throws an [IllegalStateException] whose message starts with `file:line:` of the first such line.
     */
    fun scan(file: String, lines: List<String>): List<DocFence> {
        val reading: Reading = read(file, lines)
        // A line that explains the failure comes before "never closed", which is only its consequence.
        val first: Refusal? = reading.refusals.firstOrNull { !it.neverClosed } ?: reading.refusals.firstOrNull()
        if (first != null) {
            error(
                if (first.neverClosed) "$file:${first.line}: the fence opened by '${shown(first.text)}' is never closed"
                else "$file:${first.line}: unrecognised fence-like line (${first.why}): '${shown(first.text)}'",
            )
        }
        return reading.fences
    }

    /**
     * Report: the fences a renderer shows as Kotlin but that the sample check would skip because they are not spelled exactly
     * `kotlin` at the start of a line. A fence that was read with another spelling (`Kotlin`, `kt`) and a refused line whose text
     * after its fence characters looks like Kotlin (`> ` + backticks + `kotlin`, an indented or tilde fence) both count; the refused
     * ones come back as fences with [DocFence.recognised] false. Never throws.
     */
    fun oddKotlinFences(file: String, lines: List<String>): List<DocFence> {
        val reading: Reading = read(file, lines)
        val refused: List<DocFence> = reading.refusals.filter { !it.neverClosed && !it.reopened }.map { refusal: Refusal -> asFence(file, refusal) }
        return (reading.fences + refused).filter { it.isKotlinLike && !it.isExactKotlin }.sortedBy { it.line }
    }

    /** Report: the fences the sample check reads, exactly `kotlin` at the start of a line. Never throws; [scan] is where a refused line fails. */
    fun kotlinFences(file: String, lines: List<String>): List<DocFence> =
        read(file, lines).fences.filter { it.isExactKotlin }

    private fun read(file: String, lines: List<String>): Reading {
        val fences: MutableList<DocFence> = mutableListOf()
        val refusals: MutableList<Refusal> = mutableListOf()
        var open: OpenFence? = null
        for ((index: Int, raw: String) in lines.withIndex()) {
            val line: String = if (raw.endsWith('\r')) raw.substring(0, raw.length - 1) else raw
            val current: OpenFence? = open
            if (current != null && line == current.closing) {
                fences.add(DocFence(file, current.line, current.opening, current.info, current.marker, current.body))
                open = null
                continue
            }
            if (current != null && !holdsFenceRun(line)) {
                current.body.add(line)
                continue
            }
            if (current == null && !holdsFenceRun(line)) continue
            // A line with a fence-like run that does not close the open fence, or that opens one.
            val started: OpenFence? = opening(line, index, if (index > 0) asciiTrim(lines[index - 1]) else "")
            if (current != null) {
                refusals.add(Refusal(index + 1, raw, "inside the fence opened at line ${current.line}", false, reopened = started != null))
                if (started == null) {
                    current.body.add(line)
                    continue
                }
                fences.add(DocFence(file, current.line, current.opening, current.info, current.marker, current.body))
                refusals.add(Refusal(current.line, current.opening, "", true))
            } else if (started == null) {
                refusals.add(Refusal(index + 1, raw, "not an opening line of exactly three backticks and letters or digits", false))
            }
            open = started
        }
        open?.let { current: OpenFence ->
            fences.add(DocFence(file, current.line, current.opening, current.info, current.marker, current.body))
            refusals.add(Refusal(current.line, current.opening, "", true))
        }
        return Reading(fences, refusals.sortedBy { it.line })
    }

    /** The fence that [line] opens (ASCII spaces, three backticks, then ASCII letters and digits only), or null when it is not an accepted opening line. */
    private fun opening(line: String, index: Int, marker: String): OpenFence? {
        var indent = 0
        while (indent < line.length && line[indent] == ' ') indent++
        if (!line.startsWith(FENCE, indent)) return null
        for (position: Int in indent + FENCE.length until line.length) {
            val character: Char = line[position]
            val alphanumeric: Boolean = character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9'
            if (!alphanumeric) return null
        }
        return OpenFence(index + 1, line, line.substring(indent + FENCE.length), marker, line.substring(0, indent) + FENCE)
    }

    /** True when [line] holds three or more backticks in a row or three or more tildes in a row. One pass, no recursion. */
    private fun holdsFenceRun(line: String): Boolean = runStart(line) >= 0

    /** The index where the first run of three or more backticks or tildes starts in [line], or -1. */
    private fun runStart(line: String): Int {
        var length = 0
        var previous: Char = ' '
        for ((position: Int, character: Char) in line.withIndex()) {
            length = if ((character == '`' || character == '~') && character == previous) length + 1 else if (character == '`' || character == '~') 1 else 0
            previous = character
            if (length >= 3) return position - 2
        }
        return -1
    }

    /** A refused line as a fence for the reports: its info is the text after the first run of fence characters. */
    private fun asFence(file: String, refusal: Refusal): DocFence {
        val start: Int = runStart(refusal.text)
        var end: Int = start
        while (end < refusal.text.length && refusal.text[end] == refusal.text[start]) end++
        return DocFence(file, refusal.line, shown(refusal.text), asciiTrim(refusal.text.substring(end)), "", emptyList(), recognised = false)
    }

    /** [text] without leading and trailing ASCII spaces, tabs and carriage returns, cut to a length fit for a message. */
    private fun shown(text: String): String {
        val trimmed: String = asciiTrim(text)
        return if (trimmed.length <= MAX_SHOWN) trimmed else trimmed.substring(0, MAX_SHOWN) + "..."
    }

    private fun asciiTrim(text: String): String {
        var start = 0
        var end: Int = text.length
        while (start < end && isAsciiBlank(text[start])) start++
        while (end > start && isAsciiBlank(text[end - 1])) end--
        return text.substring(start, end)
    }

    private fun isAsciiBlank(character: Char): Boolean = character == ' ' || character == '\t' || character == '\r'

    private const val MAX_SHOWN: Int = 100
}
