package io.github.jamal_wia.kmptoolkit.files.internal

import io.github.jamal_wia.kmptoolkit.files.FileNames
import io.github.jamal_wia.kmptoolkit.files.hostUsesWindowsPaths

/** A path taken apart: [root] is normalized, [segments] has no empty entry, [rooted] says the path is anchored. */
internal class ParsedPath(val root: String, val segments: List<String>, val rooted: Boolean)

/**
 * The lexical rules of one path syntax. The engine uses the rules of its [io.github.jamal_wia.kmptoolkit.files.spi.FileSystemOps],
 * the public `Paths` the rules of the host. No function touches a file system.
 */
internal sealed class PathRules(val separator: Char) {

    abstract fun isSeparator(c: Char): Boolean

    /** Length of the root prefix of [path] as written (0 for a relative path); for a UNC path it ends after the share name. */
    abstract fun rootLength(path: String): Int

    abstract fun isAbsolute(path: String): Boolean

    abstract fun parse(path: String): ParsedPath

    fun join(parent: String, child: String): String {
        require(child.isNotEmpty()) { "child must not be empty" }
        require(!isAbsolute(child)) { "child must not be absolute: $child" }
        val parentEnds: Boolean = parent.isNotEmpty() && isSeparator(parent.last())
        val childStarts: Boolean = isSeparator(child.first())
        return when {
            parentEnds && childStarts -> parent + child.substring(1)
            parentEnds || childStarts -> parent + child
            else -> parent + separator + child
        }
    }

    fun parentOf(path: String): String? {
        val root: Int = rootLength(path)
        var end: Int = path.length
        while (end > root && isSeparator(path[end - 1])) end--
        if (end <= root) return null
        var index: Int = end - 1
        while (index >= root && !isSeparator(path[index])) index--
        if (index < root) return if (root == 0) null else path.substring(0, root)
        var parentEnd: Int = index
        while (parentEnd > root && isSeparator(path[parentEnd - 1])) parentEnd--
        return path.substring(0, parentEnd)
    }

    /** [path] without trailing separators; a root keeps its own (`/`, `C:\`). */
    fun withoutTrailingSeparators(path: String): String {
        val keep: Int = maxOf(rootLength(path), 1)
        var end: Int = path.length
        while (end > keep && isSeparator(path[end - 1])) end--
        return if (end == path.length) path else path.substring(0, end)
    }

    fun normalizeLexically(path: String): String {
        val parsed: ParsedPath = parse(path)
        val kept: MutableList<String> = mutableListOf()
        for (segment: String in parsed.segments) {
            when {
                segment == "." -> Unit
                segment == ".." -> when {
                    kept.isNotEmpty() && kept.last() != ".." -> kept.removeAt(kept.lastIndex)
                    parsed.rooted -> Unit
                    else -> kept.add(segment)
                }
                else -> kept.add(segment)
            }
        }
        if (parsed.root.isEmpty() && kept.isEmpty()) return "."
        val result: String = parsed.root + kept.joinToString(separator.toString())
        // A relative result must stay relative: a first segment that looks like a drive ("C:") would otherwise turn
        // "./C:/x" into the drive-absolute "C:\x". POSIX can never get here.
        if (parsed.root.isEmpty() && rootLength(result) > 0) return "." + separator + result
        return result
    }

    fun resolveInside(base: String, relative: String): String? {
        if (relative.indexOf('\u0000') >= 0 || base.indexOf('\u0000') >= 0) return null
        if (isAbsolute(relative) || rootLength(relative) > 0) return null
        val normalizedBase: ParsedPath = parse(normalizeLexically(base))
        val joined: String = if (relative.isEmpty()) base else join(base, relative)
        val resolved: String = normalizeLexically(joined)
        val target: ParsedPath = parse(resolved)
        if (target.root != normalizedBase.root) return null
        // A base or result of "." has the segment list ["."]: compare without every "." segment.
        val baseSegments: List<String> = normalizedBase.segments.filter { it != "." }
        val targetSegments: List<String> = target.segments.filter { it != "." }
        if (targetSegments.size < baseSegments.size) return null
        for (i: Int in baseSegments.indices) {
            if (targetSegments[i] != baseSegments[i]) return null
        }
        // Past a relative base, a leading ".." in what remains climbs out of it.
        if (targetSegments.drop(baseSegments.size).any { it == ".." }) return null
        return resolved
    }

    object Posix : PathRules('/') {
        override fun isSeparator(c: Char): Boolean = c == '/'

        // path-hygiene-ignore: PH5 this is the POSIX branch of the path rules, not a host-path check
        override fun rootLength(path: String): Int = if (path.startsWith('/')) 1 else 0

        override fun isAbsolute(path: String): Boolean = rootLength(path) == 1

        override fun parse(path: String): ParsedPath {
            val rooted: Boolean = rootLength(path) == 1
            return ParsedPath(if (rooted) "/" else "", path.split('/').filter { it.isNotEmpty() }, rooted)
        }
    }

    object Windows : PathRules('\\') {
        override fun isSeparator(c: Char): Boolean = c == '\\' || c == '/'

        private fun hasDrive(path: String): Boolean =
            path.length >= 2 && path[1] == ':' && (path[0] in 'a'..'z' || path[0] in 'A'..'Z')

        /** End index (exclusive) of `\\server\share` at the start of [path], or -1 when it has no share. */
        private fun uncEnd(path: String): Int {
            if (path.length < 3 || !isSeparator(path[0]) || !isSeparator(path[1])) return -1
            var i: Int = 2
            val serverStart: Int = i
            while (i < path.length && !isSeparator(path[i])) i++
            if (i == serverStart || i >= path.length) return -1
            while (i < path.length && isSeparator(path[i])) i++
            val shareStart: Int = i
            while (i < path.length && !isSeparator(path[i])) i++
            return if (i == shareStart) -1 else i
        }

        override fun rootLength(path: String): Int {
            if (path.length >= 2 && isSeparator(path[0]) && isSeparator(path[1])) {
                val end: Int = uncEnd(path)
                return if (end >= 0) end else path.length
            }
            if (hasDrive(path)) return if (path.length >= 3 && isSeparator(path[2])) 3 else 2
            return if (path.isNotEmpty() && isSeparator(path[0])) 1 else 0
        }

        override fun isAbsolute(path: String): Boolean {
            if (hasDrive(path)) return path.length >= 3 && isSeparator(path[2])
            return uncEnd(path) >= 0
        }

        override fun parse(path: String): ParsedPath {
            if (path.length >= 2 && isSeparator(path[0]) && isSeparator(path[1])) {
                val parts: List<String> = path.substring(2).split('\\', '/').filter { it.isNotEmpty() }
                val head: List<String> = parts.take(2)
                val root: String = "\\\\" + head.joinToString("\\") + if (head.size == 2) "\\" else ""
                return ParsedPath(root, parts.drop(2), true)
            }
            if (hasDrive(path)) {
                val rest: String = path.substring(2)
                val rooted: Boolean = rest.isNotEmpty() && isSeparator(rest[0])
                val drive: String = path.substring(0, 2)
                return ParsedPath(
                    if (rooted) "$drive\\" else drive,
                    rest.split('\\', '/').filter { it.isNotEmpty() },
                    rooted,
                )
            }
            val rooted: Boolean = path.isNotEmpty() && isSeparator(path[0])
            return ParsedPath(if (rooted) "\\" else "", path.split('\\', '/').filter { it.isNotEmpty() }, rooted)
        }
    }

    companion object {
        /** The rules of the host the code runs on. */
        val host: PathRules
            get() = if (hostUsesWindowsPaths) Windows else Posix
    }
}

/** Plain-file-name and Windows-reserved-name rules, shared by [FileNames] and the engine. */
internal object NameRules {
    private val RESERVED_BASES: Set<String> = setOf("CON", "PRN", "AUX", "NUL")
    private const val WINDOWS_FORBIDDEN: String = "<>:\"|?*\\"

    fun isWindowsReserved(name: String): Boolean {
        // Windows ignores the extension and the trailing spaces of the stem: "CON .txt" opens the console device.
        val stem: String = name.substringBefore('.').trimEnd(' ').uppercase()
        if (stem in RESERVED_BASES) return true
        if (stem.length != 4) return false
        val prefix: String = stem.substring(0, 3)
        if (prefix != "COM" && prefix != "LPT") return false
        val last: Char = stem[3]
        return last in '0'..'9' || last == '¹' || last == '²' || last == '³'
    }

    fun isPlain(name: String, windows: Boolean): Boolean {
        if (name.isEmpty() || name == "." || name == "..") return false
        if (name.indexOf('/') >= 0 || name.indexOf('\u0000') >= 0) return false
        val bytes: Int = utf8Length(name)
        if (bytes < 0 || bytes > FileNames.MAX_NAME_BYTES) return false
        if (!windows) return true
        if (name.any { it in WINDOWS_FORBIDDEN || it < ' ' }) return false
        if (name.endsWith('.') || name.endsWith(' ')) return false
        return !isWindowsReserved(name)
    }

    /** The UTF-8 length of [text], or -1 when it holds an unpaired surrogate. */
    fun utf8Length(text: String): Int {
        var total: Int = 0
        var i: Int = 0
        while (i < text.length) {
            val c: Char = text[i]
            when {
                c.code < 0x80 -> total += 1
                c.code < 0x800 -> total += 2
                c.isHighSurrogate() -> {
                    if (i + 1 >= text.length || !text[i + 1].isLowSurrogate()) return -1
                    total += 4
                    i++
                }
                c.isLowSurrogate() -> return -1
                else -> total += 3
            }
            i++
        }
        return total
    }
}
