package io.github.jamal_wia.kmptoolkit.buildlogic

/** One rule of the timing-hygiene scan; see [TimingHygieneScanner] for what each rule looks for. */
internal enum class TimingHygieneRule(val id: String, val advice: String) {
    TH1(
        "TH1",
        "a sleep or park used as a wait is a flaky test; wait on a latch, a CompletableDeferred, a flow value " +
            "or an idle signal (a fake peer's injected latency is marked 'timing-hygiene-ignore: TH1 inject <reason>')",
    ),
    TH2(
        "TH2",
        "wall-clock or monotonic time read in a test; use virtual time or an injected clock, and keep real " +
            "time to a hang bound ('timing-hygiene-ignore: TH2 bound|measure R-<n>|producer <reason>')",
    ),
    TH3(
        "TH3",
        "unseeded randomness; draw from Random(seed) with a fixed default seed and put the seed in every " +
            "failure message ('timing-hygiene-ignore: TH3 name <reason>' for a unique name only)",
    ),
    TH4(
        "TH4",
        "the shared temp directory; create and delete a directory of the test's own " +
            "('timing-hygiene-ignore: TH4 private-dir|create <reason>')",
    ),
    TH5(
        "TH5",
        "an environment variable is a hidden test input; use a system property forwarded from a Gradle " +
            "property ('timing-hygiene-ignore: TH5 host <reason>' for a host fact)",
    ),
    TH6(
        "TH6",
        "a Robolectric SDK level outside the allowed list, or more than six levels on one annotation (ruling " +
            "R-8); extending it needs its own ruling, so this rule has no suppression category",
    ),
}

/** A finding of [TimingHygieneScanner]: [file] is relative to the module's `src/` folder. */
internal data class TimingHygieneViolation(
    val file: String,
    val line: Int,
    val rule: String,
    val message: String,
) {
    override fun toString(): String = "$file:$line: $rule $message"
}

/**
 * The lexical scan behind `checkTimingHygiene`: it reads Kotlin test source text line by line and
 * reports constructs that make a test depend on real time, on chance or on the host. It is fast
 * feedback that stops NEW flaky tests; the hits that already exist are recorded in
 * `build-logic/timing-hygiene-baseline.txt` and only a change in a file's count fails the build
 * (see [ratchet]). It cannot see a wait that hides behind a helper, and it is not a proof of
 * determinism: the proof is the repeated run of the checklist in COMMON.md and CONTRIBUTING.md.
 *
 * Only source sets whose folder name ends in `Test` are scanned (this includes test-only mains
 * under them). Rules, one hit per line:
 *  - TH1: `Thread.sleep(`, `TimeUnit.<X>.sleep(`, a statically imported `<UNIT>.sleep(`,
 *    `LockSupport.parkNanos(` (also bare); a bare `sleep(` in a file that imports
 *    `java.lang.Thread.sleep` (or `Thread.*`); in native test source sets (`ios*`, `apple*`,
 *    `macos*`, `native*`, ...) also `usleep(` and `sleep(`.
 *  - TH2: `System.currentTimeMillis()`, `System.nanoTime()` (also as the method references
 *    `System::nanoTime` and `System::currentTimeMillis`), `measureTimeMillis`, `measureNanoTime`,
 *    `measureTime`, `measureTimedValue`, `TimeSource.Monotonic`, `Clock.System.now()`, `NSDate()`,
 *    `CFAbsoluteTimeGetCurrent`.
 *  - TH3: `Random.next*`, `Random.Default`, a no-argument `Random()`, `SecureRandom(`,
 *    `ThreadLocalRandom`, `Math.random(`, `.shuffled()`, `.shuffle()` and `.random()` /
 *    `.randomOrNull()` without an argument, `Collections.shuffle(list)` with one argument,
 *    `UUID.randomUUID(`.
 *  - TH4: `"java.io.tmpdir"`, `NSTemporaryDirectory(`.
 *  - TH5: `System.getenv(`; in native test source sets also `getenv(`.
 *  - TH6 (ruling R-8, per test class): every `@Config(` annotation of a file, joined across lines
 *    up to its closing parenthesis, is a hit when its `sdk = [...]` holds a level outside
 *    [ALLOWED_SDK_LEVELS], more than [MAX_SDK_LEVELS] levels, or an element that is not an integer
 *    literal (`Build.VERSION_CODES.X`, `intArrayOf(`); when it uses `minSdk` / `maxSdk` without
 *    BOTH bounds being integer literals of a range of at most [MAX_SDK_LEVELS] allowed levels
 *    (a bare `minSdk` runs every level up to the target); and, once per file, when the union of
 *    the literal levels of all its annotations (class plus methods) exceeds [MAX_SDK_LEVELS].
 *
 * Comment-only lines and `import` / `package` lines are skipped, and a trailing `// ...` comment is
 * not scanned.
 *
 * Suppression, always with a reason (a malformed one is itself a violation, `TH-SUPPRESSION`):
 *  - `// timing-hygiene-ignore: <rule> <category> <text>` alone on the line DIRECTLY ABOVE the hit
 *    (stacked comments for several rules are fine). Categories: TH1 `inject` | `poll`;
 *    TH2 `bound` | `measure R-<n>` | `producer`; TH3 `name`; TH4 `private-dir` | `create`; TH5 `host`.
 *    TH6 has no suppression category (extending the SDK list needs a ruling); its existing hits are
 *    in the baseline like any other rule's.
 *  - `// timing-hygiene-file: poll-helper <reason>` anywhere in a file that is a bounded polling
 *    helper; only such a file may use the `poll` category.
 */
internal object TimingHygieneScanner {

    /** Reported when a suppression comment is malformed. */
    const val SUPPRESSION_RULE: String = "TH-SUPPRESSION"

    /** The Robolectric levels the suite already loads (ruling R-8); a level outside the list needs its own ruling. */
    val ALLOWED_SDK_LEVELS: Set<Int> = setOf(24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35)

    /** At most this many levels on one `@Config(sdk = [...])` (ruling R-8). */
    const val MAX_SDK_LEVELS: Int = 6

    /** Source-set name prefixes of native test source sets, where the posix `sleep` and `getenv` forms are scanned. */
    val NATIVE_PREFIXES: List<String> = listOf("ios", "apple", "macos", "tvos", "watchos", "darwin", "native")

    private val categories: Map<String, Set<String>> = mapOf(
        "TH1" to setOf("inject", "poll"),
        "TH2" to setOf("bound", "measure", "producer"),
        "TH3" to setOf("name"),
        "TH4" to setOf("private-dir", "create"),
        "TH5" to setOf("host"),
    )

    private val th1: Regex = Regex(
        """\bThread\.sleep\(|\bTimeUnit\.\w+\.sleep\(|\bLockSupport\.parkNanos\(|""" +
            """\b(NANOSECONDS|MICROSECONDS|MILLISECONDS|SECONDS|MINUTES|HOURS|DAYS)\.sleep\(|(?<![\w.])parkNanos\(""",
    )
    private val th1StaticSleep: Regex = Regex("""(?<![\w.])sleep\(""")
    private val threadSleepImport: Regex = Regex("""^\s*import\s+java\.lang\.Thread\.(sleep|\*)\s*$""")
    private val th1Native: Regex = Regex("""(?<![\w])(usleep|sleep)\(""")
    private val th2: Regex = Regex(
        """\bSystem\.currentTimeMillis\(\)|\bSystem\.nanoTime\(\)|\bTimeSource\.Monotonic\b|""" +
            """\bClock\.System\.now\(\)|\bNSDate\(\)|\bCFAbsoluteTimeGetCurrent\b|""" +
            """\bSystem::(nanoTime|currentTimeMillis)\b|\bmeasure(TimeMillis|NanoTime|Time|TimedValue)\b""",
    )
    private val th3: Regex = Regex(
        """\bRandom\.(next\w*|Default)\b|\bThreadLocalRandom\b|\bMath\.random\(|\.shuffled\(\s*\)|""" +
            """\.random(OrNull)?\(\s*\)|\bUUID\.randomUUID\(|(?<![\w.])Random\(\s*\)|\.Random\(\s*\)|""" +
            """\bSecureRandom\(|\.shuffle\(\s*\)|\bCollections\.shuffle\((?:[^,()]|\([^()]*\))*\)""",
    )
    private val th4: Regex = Regex(""""java\.io\.tmpdir"|\bNSTemporaryDirectory\(""")
    private val th5: Regex = Regex("""\bSystem\.getenv\(""")
    private val th5Native: Regex = Regex("""(?<![\w])getenv\(""")
    private val configStart: Regex = Regex("""@Config\(""")
    private val configSdk: Regex = Regex("""\bsdk\s*=\s*\[([^\]]*)]""")
    private val configMin: Regex = Regex("""\bminSdk\s*=\s*([^,)\s]+)""")
    private val configMax: Regex = Regex("""\bmaxSdk\s*=\s*([^,)\s]+)""")
    private val testAnnotation: Regex = Regex("""^\s*@(org\.junit\.(jupiter\.api\.)?|kotlin\.test\.)?Test\b""")

    private val lineIgnore: Regex = Regex("""^\s*//\s*timing-hygiene-ignore:(.*)$""")
    private val trailingIgnore: Regex = Regex("""\S.*//\s*timing-hygiene-ignore:""")
    private val fileMarker: Regex = Regex("""//\s*timing-hygiene-file:(.*)$""")
    private val commentOnly: Regex = Regex("""^\s*(//|/\*|\*)""")
    private val declarationOnly: Regex = Regex("""^\s*(import|package)\s""")
    private val trailingComment: Regex = Regex("""\s//.*$""")
    private val whitespace: Regex = Regex("""\s+""")
    private val rulingId: Regex = Regex("""R-\d+""")

    /** Whether a source set (a folder name under `src/`) is scanned at all. */
    fun isScanned(sourceSet: String): Boolean = sourceSet.endsWith("Test")

    private fun isNative(sourceSet: String): Boolean {
        val lower: String = sourceSet.lowercase()
        return NATIVE_PREFIXES.any { lower.startsWith(it) }
    }

    /**
     * Scans one file and returns every hit that is not suppressed, plus malformed suppressions.
     * [relativePath] is relative to the module's `src/` folder with `/` separators
     * (`commonTest/kotlin/pkg/File.kt`); its first segment is the source set.
     */
    fun scan(relativePath: String, text: String): List<TimingHygieneViolation> {
        val sourceSet: String = relativePath.substringBefore('/')
        if (!isScanned(sourceSet)) return emptyList()
        val native: Boolean = isNative(sourceSet)
        val lines: List<String> = text.lines()
        val found: MutableList<TimingHygieneViolation> = mutableListOf()

        // The file marker first: it enables the 'poll' category for the whole file.
        var pollHelper: Boolean = false
        lines.forEachIndexed { index, line ->
            if (!commentOnly.containsMatchIn(line)) return@forEachIndexed
            val body: String = fileMarker.find(line)?.groupValues?.get(1) ?: return@forEachIndexed
            val parts: List<String> = body.trim().split(whitespace, limit = 2)
            when {
                parts.first() != "poll-helper" -> found += TimingHygieneViolation(
                    relativePath, index + 1, SUPPRESSION_RULE,
                    "unknown timing-hygiene-file kind '${parts.first()}'; the only kind is 'poll-helper'",
                )
                parts.getOrNull(1).isNullOrBlank() -> found += TimingHygieneViolation(
                    relativePath, index + 1, SUPPRESSION_RULE,
                    "timing-hygiene-file: poll-helper needs a reason after it",
                )
                lines.any { testAnnotation.containsMatchIn(it) } -> found += TimingHygieneViolation(
                    relativePath, index + 1, SUPPRESSION_RULE,
                    "a poll-helper file must not contain a @Test; put the helper in its own file",
                )
                else -> pollHelper = true
            }
        }

        // Line suppressions: a comment-only line allows its rule on the next line (and chains through stacked comments).
        val allowed: Array<Set<String>> = Array(lines.size) { emptySet() }
        lines.forEachIndexed { index, line ->
            val ignore: MatchResult? = lineIgnore.find(line)
            if (ignore == null) {
                if (trailingIgnore.containsMatchIn(line) && !commentOnly.containsMatchIn(line)) {
                    found += TimingHygieneViolation(
                        relativePath, index + 1, SUPPRESSION_RULE,
                        "timing-hygiene-ignore goes alone on the line directly above the hit, not after code",
                    )
                }
                return@forEachIndexed
            }
            val parts: List<String> = ignore.groupValues[1].trim().split(whitespace)
            val rule: String = parts.first()
            val category: String? = parts.getOrNull(1)
            val problem: String? = when {
                rule.isEmpty() || rule !in categories ->
                    "timing-hygiene-ignore must name a suppressible rule (${categories.keys.joinToString()}) first; got '$rule'"
                category == null || category !in categories.getValue(rule) ->
                    "$rule needs a category (${categories.getValue(rule).joinToString(" | ")}) after the rule; got '${category ?: ""}'"
                category == "poll" && !pollHelper ->
                    "the 'poll' category is only for a file marked '// timing-hygiene-file: poll-helper <reason>'"
                category == "measure" &&
                    (parts.getOrNull(2)?.let { rulingId.matches(it) } != true || parts.size < 4) ->
                    "TH2 measure needs a ruling id and a text: 'timing-hygiene-ignore: TH2 measure R-<n> <text>'"
                category != "measure" && parts.size < 3 -> "$rule $category needs a reason text after the category"
                else -> null
            }
            if (problem != null) {
                found += TimingHygieneViolation(relativePath, index + 1, SUPPRESSION_RULE, problem)
            } else {
                if (index + 1 < lines.size) allowed[index + 1] = allowed[index + 1] + allowed[index] + rule
            }
        }

        val staticSleep: Boolean = lines.any { threadSleepImport.containsMatchIn(it) }
        val codeLines: List<String> = lines.map { raw ->
            if (declarationOnly.containsMatchIn(raw) || commentOnly.containsMatchIn(raw)) "" else raw.replace(trailingComment, "")
        }
        lines.forEachIndexed { index, _ ->
            val line: String = codeLines[index]
            if (line.isEmpty()) return@forEachIndexed
            fun hit(rule: TimingHygieneRule) {
                if (rule.id in allowed[index]) return
                found += TimingHygieneViolation(relativePath, index + 1, rule.id, rule.advice)
            }
            if (th1.containsMatchIn(line) || (native && th1Native.containsMatchIn(line)) ||
                (staticSleep && th1StaticSleep.containsMatchIn(line))
            ) {
                hit(TimingHygieneRule.TH1)
            }
            if (th2.containsMatchIn(line)) hit(TimingHygieneRule.TH2)
            if (th3.containsMatchIn(line)) hit(TimingHygieneRule.TH3)
            if (th4.containsMatchIn(line)) hit(TimingHygieneRule.TH4)
            if (th5.containsMatchIn(line) || (native && th5Native.containsMatchIn(line))) hit(TimingHygieneRule.TH5)
        }
        for (lineIndex in sdkHits(codeLines)) {
            found += TimingHygieneViolation(relativePath, lineIndex + 1, TimingHygieneRule.TH6.id, TimingHygieneRule.TH6.advice)
        }
        return found.sortedBy { it.line }
    }

    /** The levels `minSdk..maxSdk` span when both are integer literals of a sane range, else null. */
    private fun literalRange(min: String?, max: String?): IntRange? {
        val low: Int = min?.toIntOrNull() ?: return null
        val high: Int = max?.toIntOrNull() ?: return null
        return if (low <= high) low..high else null
    }

    /** Zero-based line numbers of the TH6 hits of one file; [codeLines] has comments blanked out. */
    private fun sdkHits(codeLines: List<String>): List<Int> {
        val hits: MutableList<Int> = mutableListOf()
        val union: MutableSet<Int> = linkedSetOf()
        var firstOverflow: Int? = null
        codeLines.forEachIndexed { index, line ->
            val start: MatchResult = configStart.find(line) ?: return@forEachIndexed
            // Join the annotation up to its balancing parenthesis (at most 15 lines).
            val text: StringBuilder = StringBuilder()
            var depth: Int = 0
            loop@ for (j in index until minOf(codeLines.size, index + 15)) {
                val from: Int = if (j == index) start.range.last else 0
                for (c in codeLines[j].substring(from)) {
                    text.append(c)
                    if (c == '(') depth++
                    if (c == ')') {
                        depth--
                        if (depth == 0) break@loop
                    }
                }
                text.append(' ')
            }
            val body: String = text.toString()
            var bad: Boolean = false
            val levels: MutableList<Int> = mutableListOf()
            configSdk.find(body)?.let { m ->
                val items: List<String> = m.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
                for (item in items) {
                    val level: Int? = item.toIntOrNull()
                    if (level == null) bad = true else levels += level
                }
                if (items.size > MAX_SDK_LEVELS) bad = true
            }
            val min: String? = configMin.find(body)?.groupValues?.get(1)
            val max: String? = configMax.find(body)?.groupValues?.get(1)
            if (min != null || max != null) {
                val range: IntRange? = literalRange(min, max)
                if (range == null || range.count() > MAX_SDK_LEVELS) bad = true else levels += range.toList()
            }
            if (levels.any { it !in ALLOWED_SDK_LEVELS }) bad = true
            if (bad) hits += index
            union += levels
            if (firstOverflow == null && union.size > MAX_SDK_LEVELS) firstOverflow = index
        }
        val overflow: Int? = firstOverflow
        if (overflow != null && overflow !in hits) hits += overflow
        return hits.sorted()
    }

    /** The baseline key of one counted hit: `<path relative to the repository>:<rule>`. */
    fun key(modulePath: String, file: String, rule: String): String = "$modulePath/src/$file:$rule"

    /**
     * Counts the baselined hits (everything except [SUPPRESSION_RULE], which is never baselined) as
     * `key -> count`, in key order.
     */
    fun counts(modulePath: String, violations: List<TimingHygieneViolation>): Map<String, Int> =
        violations.filter { it.rule != SUPPRESSION_RULE }
            .groupingBy { key(modulePath, it.file, it.rule) }.eachCount().toSortedMap()

    /** One baseline line per counted key, in the format `<key>:<count>`. */
    fun baselineLines(counts: Map<String, Int>): List<String> = counts.map { (key, n) -> "$key:$n" }

    /**
     * Parses baseline text (`<module>/src/<path>:<rule>:<count>`, one per line; blank lines and `#`
     * comments allowed) into `key -> count`. A malformed line is returned in the second member.
     */
    fun parseBaseline(text: String): Pair<Map<String, Int>, List<String>> {
        val map: MutableMap<String, Int> = linkedMapOf()
        val bad: MutableList<String> = mutableListOf()
        text.lines().forEachIndexed { index, raw ->
            val line: String = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val count: Int? = line.substringAfterLast(':', "").toIntOrNull()
            val rest: String = line.substringBeforeLast(':')
            val rule: String = rest.substringAfterLast(':', "")
            if (count == null || count < 1 || TimingHygieneRule.entries.none { it.id == rule } || "/src/" !in rest) {
                bad += "baseline line ${index + 1} is malformed: '$line'"
            } else if (map.put(rest, count) != null) {
                bad += "baseline line ${index + 1} repeats '$rest'"
            }
        }
        return map to bad
    }

    /**
     * The ratchet: compares this module's [counts] with its slice of the [baseline] and returns one
     * problem per difference. A count ABOVE the baseline is a new hit (fix it or suppress it with a
     * reason); a count BELOW it means the file improved, so its baseline line must be lowered or
     * removed in the same commit. The baseline can only shrink.
     */
    fun ratchet(
        modulePath: String,
        counts: Map<String, Int>,
        baseline: Map<String, Int>,
        violations: List<TimingHygieneViolation>,
    ): List<String> {
        val prefix: String = "$modulePath/src/"
        val mine: Map<String, Int> = baseline.filterKeys { it.startsWith(prefix) }
        val problems: MutableList<String> = mutableListOf()
        for (key in (counts.keys + mine.keys).toSortedSet()) {
            val now: Int = counts[key] ?: 0
            val was: Int = mine[key] ?: 0
            if (now == was) continue
            val file: String = key.removePrefix(prefix).substringBeforeLast(':')
            val rule: String = key.substringAfterLast(':')
            if (now > was) {
                val lines: String = violations.filter { it.file == file && it.rule == rule }
                    .joinToString(", ") { it.line.toString() }
                problems += "$key: $now hit(s), baseline allows $was (lines $lines): fix the new hit or suppress it " +
                    "with a reason; the baseline may never go up. ${TimingHygieneRule.valueOf(rule).advice}"
            } else {
                val fix: String = if (now == 0) "remove its baseline line" else "lower its baseline line to $key:$now"
                problems += "$key: $now hit(s), baseline says $was: the file improved, $fix in the same commit"
            }
        }
        return problems
    }
}
