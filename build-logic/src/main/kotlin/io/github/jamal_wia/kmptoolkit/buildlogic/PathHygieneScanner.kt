package io.github.jamal_wia.kmptoolkit.buildlogic

/** One rule of the path-hygiene scan; see [PathHygieneScanner] for what each rule looks for. */
internal enum class PathHygieneRule(val id: String, val advice: String) {
    PH1(
        "PH1",
        "File.separator / File.separatorChar bakes the host's separator into a string; build paths with " +
            "File(parent, child) or Path.resolve, and in tests use TestPaths",
    ),
    PH2(
        "PH2",
        "POSIX-only file API (permission bits, /proc, /dev/fd); in tests use TestFs / " +
            "TestHost.assumePosixFileSemantics",
    ),
    PH3(
        "PH3",
        "a native path is taken apart on '/'; in tests use TestPaths.parent / fileName / toLogical",
    ),
    PH4(
        "PH4",
        "a path-like value is joined to the rest with '/'; in tests use TestPaths.join",
    ),
    PH5(
        "PH5",
        "startsWith('/') as an absolute-path check is false for C:\\ paths; use Paths.isAbsolute from kmptoolkit-files, " +
            "or suppress it where the string is a logical name rather than a file path",
    ),
}

/** A finding of [PathHygieneScanner]: [file] is relative to the module's `src/` folder. */
internal data class PathHygieneViolation(
    val file: String,
    val line: Int,
    val rule: String,
    val message: String,
) {
    override fun toString(): String = "$file:$line: $rule $message"
}

/**
 * The lexical scan behind `checkPathHygiene`: it reads Kotlin source text line by line and reports
 * constructs that behave differently on Windows than on macOS or Linux. It is fast feedback, not
 * proof — it cannot see a separator that arrives through a variable, a helper or a platform call,
 * and the Windows CI job (not this scan) is what runs the code with real Windows behaviour. What it
 * deliberately does not catch is listed in CONTRIBUTING.md § "Paths and the path-hygiene check".
 *
 * Rules (the source-set names below are the folder names under `src/`):
 *  - PH1, every scanned source set: `File.separator`, `File.separatorChar` (also as a
 *    static import), `"file.separator"`, `FileSystems.getDefault().separator`.
 *  - PH2, every scanned source set: `PosixFilePermission(s)`, `setPosixFilePermissions`,
 *    `setReadable(`, `setWritable(`, `setExecutable(`, `setReadOnly(` (with or without a receiver), `UnixOperatingSystemMXBean`,
 *    a string starting `"/proc/`, the string `"/dev/fd`.
 *  - PH3, source sets whose name ends in `Test`: `substringBeforeLast('/')`, `substringAfterLast('/')`,
 *    `lastIndexOf('/')`, `.split('/')` (and the `"/"` forms, with or without a second argument).
 *  - PH4, source sets whose name ends in `Test`: a path-like name (`dir`, `path`, `base`, `root`,
 *    `folder`, `tmp`, `temp`, ...) followed by `/` in a string template (including `${x.path}/`) or a `+ "/..."` concatenation.
 *  - PH5, every scanned source set that is not a `*Test` one (`*Main`, `jvmShared`): `startsWith('/')`,
 *    `startsWith("/")`, `startsWith(PATH_SEPARATOR)` and similar, a `val x = "/"` declaration, and
 *    `[0] == '/'` / `.first() == '/'`.
 *
 * iOS (and other native) source sets are never scanned: their paths are POSIX by construction.
 * Comment-only lines and `import` / `package` lines are skipped.
 *
 * Suppressions, both requiring a reason (a bare one is itself a violation):
 *  - one line: `// path-hygiene-ignore: <rule>[,<rule>...] <reason>`, on the offending line or alone on
 *    the line directly above it;
 *  - one file: `// path-hygiene-file: logical-paths <reason>` anywhere in a TEST file (in a production
 *    source set it is itself a violation), for a file whose strings are logical paths of a fake file
 *    system, not host paths. It exempts PH3, PH4 and PH5
 *    only; PH1 and PH2 are host-API rules and stay line-by-line.
 */
internal object PathHygieneScanner {

    /** Reported when a suppression comment is malformed (no reason, unknown rule or kind). */
    const val SUPPRESSION_RULE: String = "PH-SUPPRESSION"

    /** Source-set name prefixes that are never scanned (native, so POSIX by construction). */
    val SKIPPED_PREFIXES: List<String> =
        listOf("ios", "apple", "native", "macos", "tvos", "watchos", "darwin", "linux", "mingw")

    private const val PATH_NAMES: String = "dir|directory|path|base|root|folder|tmp|temp"

    private val ph1: Regex =
        Regex("""\bFile\.separator(Char)?\b|"file\.separator"|FileSystems\.getDefault\(\)\.separator""")
    private val ph1Import: Regex = Regex("""^\s*import\s+java\.io\.File\.separator(Char)?\b""")
    private val ph2: Regex = Regex(
        """PosixFilePermission|setPosixFilePermissions|\.set(Readable|Writable|Executable|ReadOnly)\(|""" +
            """(?<![.\w])(setReadable|setWritable|setExecutable)\(\s*(true|false)\b|(?<![.\w])setReadOnly\(\s*\)|""" +
            """UnixOperatingSystemMXBean|"/proc/|"/dev/fd""",
    )
    private val ph3: Regex = Regex(
        """\b(substringBeforeLast|substringAfterLast|lastIndexOf)\(\s*['"]/['"]\s*[,)]|\.split\(\s*['"]/['"]\s*[,)]""",
    )
    private val ph4: List<Regex> = listOf(
        Regex("""\$\{?\w*(?i:$PATH_NAMES)\w*(\(\))?\}?/"""),
        Regex("""\$\{[^}]*(?i:$PATH_NAMES)[^}]*\}/"""),
        Regex("""(?i:$PATH_NAMES|absolutePath|canonicalPath)\w*(\(\))?\s*\+\s*"/"""),
    )
    private val ph5: List<Regex> = listOf(
        Regex("""\bstartsWith\(\s*['"]/['"]"""),
        // A named separator constant: path.startsWith(PATH_SEPARATOR).
        Regex("""\bstartsWith\(\s*[A-Z_]*(SEPARATOR|SLASH)[A-Z_]*\s*\)"""),
        // A declaration that names a lone slash, the constant behind the line above.
        Regex("""\bval\s+\w+(\s*:\s*(String|Char))?\s*=\s*['"]/['"]"""),
        // The index forms of the same check.
        Regex("""(\[0\]|\.first\(\))\s*==\s*'/'"""),
    )

    private val lineIgnore: Regex = Regex("""//\s*path-hygiene-ignore:(.*)$""")
    private val fileIgnore: Regex = Regex("""//\s*path-hygiene-file:(.*)$""")
    private val commentOnly: Regex = Regex("""^\s*(//|/\*|\*)""")
    private val declarationOnly: Regex = Regex("""^\s*(import|package)\s""")
    private val whitespace: Regex = Regex("""\s+""")

    /** Whether a source set (a folder name under `src/`) is scanned at all. */
    fun isScanned(sourceSet: String): Boolean {
        val lower: String = sourceSet.lowercase()
        return SKIPPED_PREFIXES.none { lower.startsWith(it) }
    }

    /**
     * Scans one file. [relativePath] is relative to the module's `src/` folder with `/` separators
     * (`commonTest/kotlin/pkg/File.kt`); its first segment is the source set.
     */
    fun scan(relativePath: String, text: String): List<PathHygieneViolation> {
        val sourceSet: String = relativePath.substringBefore('/')
        if (!isScanned(sourceSet)) return emptyList()
        val isTest: Boolean = sourceSet.endsWith("Test")
        val lines: List<String> = text.lines()
        val found: MutableList<PathHygieneViolation> = mutableListOf()

        // File-level suppression first: it applies to every line.
        var logicalPaths: Boolean = false
        lines.forEachIndexed { index, line ->
            val body: String = fileIgnore.find(line)?.groupValues?.get(1) ?: return@forEachIndexed
            val parts: List<String> = body.trim().split(whitespace, limit = 2)
            when {
                parts.first() != "logical-paths" -> found += PathHygieneViolation(
                    relativePath, index + 1, SUPPRESSION_RULE,
                    "unknown path-hygiene-file kind '${parts.first()}'; the only kind is 'logical-paths'",
                )
                parts.getOrNull(1).isNullOrBlank() -> found += PathHygieneViolation(
                    relativePath, index + 1, SUPPRESSION_RULE,
                    "path-hygiene-file: logical-paths needs a reason after it",
                )
                !isTest -> found += PathHygieneViolation(
                    relativePath, index + 1, SUPPRESSION_RULE,
                    "path-hygiene-file is for test source sets only; in production code suppress single lines",
                )
                else -> logicalPaths = true
            }
        }

        // Line suppressions: the rules allowed on each line, from the line itself and a comment above.
        val allowed: Array<Set<String>> = Array(lines.size) { emptySet() }
        lines.forEachIndexed { index, line ->
            val body: String = lineIgnore.find(line)?.groupValues?.get(1) ?: return@forEachIndexed
            val parts: List<String> = body.trim().split(whitespace, limit = 2)
            val ids: List<String> = parts.first().split(',').filter { it.isNotBlank() }
            val unknown: List<String> = ids.filter { id -> PathHygieneRule.entries.none { it.id == id } }
            when {
                ids.isEmpty() || unknown.isNotEmpty() -> found += PathHygieneViolation(
                    relativePath, index + 1, SUPPRESSION_RULE,
                    "path-hygiene-ignore must name rules (PH1..PH5, comma separated) first" +
                        if (unknown.isNotEmpty()) "; unknown: ${unknown.joinToString()}" else "",
                )
                parts.getOrNull(1).isNullOrBlank() -> found += PathHygieneViolation(
                    relativePath, index + 1, SUPPRESSION_RULE,
                    "path-hygiene-ignore: ${ids.joinToString(",")} needs a reason after the rule",
                )
                else -> {
                    allowed[index] = allowed[index] + ids
                    if (commentOnly.containsMatchIn(line) && index + 1 < lines.size) {
                        allowed[index + 1] = allowed[index + 1] + ids
                    }
                }
            }
        }

        lines.forEachIndexed { index, rawLine ->
            if (declarationOnly.containsMatchIn(rawLine)) {
                if (ph1Import.containsMatchIn(rawLine) && PathHygieneRule.PH1.id !in allowed[index]) {
                    found += PathHygieneViolation(relativePath, index + 1, PathHygieneRule.PH1.id, PathHygieneRule.PH1.advice)
                }
                return@forEachIndexed
            }
            if (commentOnly.containsMatchIn(rawLine)) return@forEachIndexed
            val line: String = rawLine.replace(lineIgnore, "")
            fun hit(rule: PathHygieneRule) {
                if (rule.id in allowed[index]) return
                found += PathHygieneViolation(relativePath, index + 1, rule.id, rule.advice)
            }
            if (ph1.containsMatchIn(line)) hit(PathHygieneRule.PH1)
            if (ph2.containsMatchIn(line)) hit(PathHygieneRule.PH2)
            if (!logicalPaths) {
                if (isTest && ph3.containsMatchIn(line)) hit(PathHygieneRule.PH3)
                if (isTest && ph4.any { it.containsMatchIn(line) }) hit(PathHygieneRule.PH4)
                if (!isTest && ph5.any { it.containsMatchIn(line) }) hit(PathHygieneRule.PH5)
            }
        }
        return found.sortedBy { it.line }
    }
}
