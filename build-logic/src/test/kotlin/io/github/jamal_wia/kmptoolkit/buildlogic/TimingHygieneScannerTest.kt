package io.github.jamal_wia.kmptoolkit.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Positive and negative samples for every rule and suppression category of [TimingHygieneScanner],
 * and for the ratchet. The samples are the contract of plans/determinism-plan.md section 5.2, not a
 * copy of what the regexes happen to match.
 */
class TimingHygieneScannerTest {

    private val test: String = "commonTest/kotlin/p/A.kt"
    private val jvmTest: String = "jvmTest/kotlin/p/A.kt"
    private val iosTest: String = "iosTest/kotlin/p/A.kt"
    private val main: String = "commonMain/kotlin/p/A.kt"
    private val poll: String = "// timing-hygiene-file: poll-helper bounded loop"

    private fun rules(path: String, vararg lines: String): List<String> =
        TimingHygieneScanner.scan(path, lines.joinToString("\n")).map { it.rule }

    // ---- scope ----

    @Test
    fun `only source sets ending in Test are scanned`() {
        assertEquals(emptyList(), rules(main, "Thread.sleep(5)"))
        assertEquals(emptyList(), rules("jvmShared/kotlin/A.kt", "Thread.sleep(5)"))
        assertEquals(listOf("TH1"), rules("androidHostTest/kotlin/A.kt", "Thread.sleep(5)"))
        assertEquals(listOf("TH1"), rules("jvmTest/kotlin/pkg/FooChildMain.kt", "Thread.sleep(5)"))
    }

    @Test
    fun `comment import and package lines and trailing comments are skipped`() {
        assertEquals(
            emptyList(),
            rules(test, "// Thread.sleep(5)", "/* System.nanoTime() */", " * Random.nextInt()", "import java.lang.Thread.sleep(", "val x = 1 // Thread.sleep(5)"),
        )
    }

    // ---- TH1 ----

    @Test
    fun `TH1 flags sleeps and parks`() {
        assertEquals(listOf("TH1"), rules(test, "Thread.sleep(50)"))
        assertEquals(listOf("TH1"), rules(test, "TimeUnit.MILLISECONDS.sleep(50)"))
        assertEquals(listOf("TH1"), rules(test, "java.util.concurrent.locks.LockSupport.parkNanos(1000)"))
    }

    @Test
    fun `TH1 ignores lookalikes and delay`() {
        assertEquals(emptyList(), rules(test, "fakeThread.sleep(5)", "delay(50)", "val Thread.sleeper = 1", "clock.sleep(5)"))
    }

    @Test
    fun `TH1 flags posix sleep only in native test source sets`() {
        assertEquals(listOf("TH1"), rules(iosTest, "usleep(1000u)"))
        assertEquals(listOf("TH1"), rules(iosTest, "platform.posix.sleep(1u)"))
        assertEquals(emptyList(), rules(test, "usleep(1000u)", "sleep(1)"))
        assertEquals(emptyList(), rules(iosTest, "val sleeping = 1", "stepsleep = 2"))
    }

    @Test
    fun `TH1 inject is allowed on the line above`() {
        assertEquals(emptyList(), rules(test, "// timing-hygiene-ignore: TH1 inject latency of the fake peer", "Thread.sleep(50)"))
    }

    @Test
    fun `TH1 poll needs the file marker`() {
        assertEquals(
            emptyList(),
            rules(test, poll, "// timing-hygiene-ignore: TH1 poll bounded by the deadline", "Thread.sleep(5)"),
        )
        assertEquals(
            listOf("TH-SUPPRESSION", "TH1"),
            rules(test, "// timing-hygiene-ignore: TH1 poll bounded by the deadline", "Thread.sleep(5)"),
        )
    }

    // ---- TH2 ----

    @Test
    fun `TH2 flags every clock read`() {
        val samples: List<String> = listOf(
            "val t = System.currentTimeMillis()",
            "val t = System.nanoTime()",
            "val m = TimeSource.Monotonic.markNow()",
            "val n = Clock.System.now()",
            "val d = NSDate()",
            "val c = CFAbsoluteTimeGetCurrent()",
        )
        for (sample in samples) assertEquals(listOf("TH2"), rules(test, sample), sample)
    }

    @Test
    fun `TH2 ignores lookalikes`() {
        assertEquals(emptyList(), rules(test, "val n = clock.now()", "val d = NSDate(1.0)", "myTimeSource.Monotonic2"))
    }

    @Test
    fun `TH2 categories bound measure and producer`() {
        assertEquals(emptyList(), rules(test, "// timing-hygiene-ignore: TH2 bound hang deadline only", "val t = System.nanoTime()"))
        assertEquals(emptyList(), rules(test, "// timing-hygiene-ignore: TH2 producer stamped where produced", "val t = System.nanoTime()"))
        assertEquals(emptyList(), rules(test, "// timing-hygiene-ignore: TH2 measure R-3 FX thread stamps", "val t = System.nanoTime()"))
    }

    @Test
    fun `TH2 measure needs a ruling id and a text`() {
        assertEquals(
            listOf("TH-SUPPRESSION", "TH2"),
            rules(test, "// timing-hygiene-ignore: TH2 measure some text", "val t = System.nanoTime()"),
        )
        assertEquals(
            listOf("TH-SUPPRESSION", "TH2"),
            rules(test, "// timing-hygiene-ignore: TH2 measure R-3", "val t = System.nanoTime()"),
        )
    }

    // ---- TH3 ----

    @Test
    fun `TH3 flags unseeded randomness`() {
        val samples: List<String> = listOf(
            "val n = Random.nextInt(10)",
            "val r = Random.Default",
            "val x = ThreadLocalRandom.current().nextInt()",
            "val d = Math.random()",
            "val s = list.shuffled()",
            "val e = list.random()",
            "val id = UUID.randomUUID()",
        )
        for (sample in samples) assertEquals(listOf("TH3"), rules(test, sample), sample)
    }

    @Test
    fun `TH3 ignores seeded randomness`() {
        assertEquals(
            emptyList(),
            rules(test, "val r = Random(seed)", "val s = list.shuffled(r)", "val e = list.random(r)", "val n = r.nextInt(5)", "val m = Random(7).nextInt()"),
        )
    }

    @Test
    fun `TH3 name category`() {
        assertEquals(emptyList(), rules(test, "// timing-hygiene-ignore: TH3 name unique file name only", "val id = UUID.randomUUID()"))
        assertEquals(listOf("TH-SUPPRESSION", "TH3"), rules(test, "// timing-hygiene-ignore: TH3 bound x", "val id = UUID.randomUUID()"))
    }

    // ---- TH4 ----

    @Test
    fun `TH4 flags the shared temp directory`() {
        assertEquals(listOf("TH4"), rules(test, """val d = System.getProperty("java.io.tmpdir")"""))
        assertEquals(listOf("TH4"), rules(test, "val d = NSTemporaryDirectory()"))
        assertEquals(emptyList(), rules(test, """val d = System.getProperty("user.dir")"""))
    }

    @Test
    fun `TH4 categories private-dir and create`() {
        assertEquals(emptyList(), rules(test, "// timing-hygiene-ignore: TH4 private-dir build provided per task", """val d = System.getProperty("java.io.tmpdir")"""))
        assertEquals(emptyList(), rules(test, "// timing-hygiene-ignore: TH4 create makes the test's own directory", "val d = NSTemporaryDirectory()"))
    }

    // ---- TH5 ----

    @Test
    fun `TH5 flags environment reads`() {
        assertEquals(listOf("TH5"), rules(test, """val v = System.getenv("HOME")"""))
        assertEquals(listOf("TH5"), rules(iosTest, """val v = getenv("TZ")"""))
        assertEquals(listOf("TH5"), rules(iosTest, """val v = platform.posix.getenv("TZ")"""))
        assertEquals(emptyList(), rules(test, """val v = environment.getenv("HOME")""", "val g = getenv(\"x\")"))
        assertEquals(emptyList(), rules(test, """val v = System.getProperty("home")"""))
    }

    @Test
    fun `TH5 host category`() {
        assertEquals(emptyList(), rules(test, "// timing-hygiene-ignore: TH5 host restores TZ", """val v = System.getenv("TZ")"""))
    }

    // ---- TH6 ----

    @Test
    fun `TH6 flags a level outside the allowed list`() {
        assertEquals(listOf("TH6"), rules(test, "@Config(sdk = [36])"))
        assertEquals(listOf("TH6"), rules(test, "@Config(sdk = [34, 21], shadows = [X::class])"))
        assertEquals(emptyList(), rules(test, "@Config(sdk = [34])", "@Config(sdk = [24, 26, 29, 31, 33, 35])", "@Config(shadows = [X::class])"))
    }

    @Test
    fun `TH6 flags more than six levels on one annotation`() {
        assertEquals(listOf("TH6"), rules(test, "@Config(sdk = [24, 25, 26, 28, 29, 30, 31])"))
        assertEquals(emptyList(), rules(test, "@Config(sdk = [24, 25, 26, 28, 29, 30])"))
    }

    @Test
    fun `TH6 cannot be suppressed`() {
        assertEquals(
            listOf("TH-SUPPRESSION", "TH6"),
            rules(test, "// timing-hygiene-ignore: TH6 bound x y", "@Config(sdk = [36])"),
        )
    }

    // ---- suppression mechanics ----

    @Test
    fun `an ignore applies to the next line only`() {
        assertEquals(
            listOf("TH1"),
            rules(test, "// timing-hygiene-ignore: TH1 inject fake peer", "Thread.sleep(1)", "Thread.sleep(2)"),
        )
        assertEquals(
            listOf("TH1"),
            rules(test, "// timing-hygiene-ignore: TH1 inject fake peer", "", "Thread.sleep(2)"),
        )
    }

    @Test
    fun `an ignore covers its own rule only`() {
        assertEquals(
            listOf("TH2"),
            rules(test, "// timing-hygiene-ignore: TH1 inject fake peer", "Thread.sleep(System.nanoTime())"),
        )
    }

    @Test
    fun `stacked ignores cover two rules on one line`() {
        assertEquals(
            emptyList(),
            rules(
                test,
                "// timing-hygiene-ignore: TH1 inject fake peer",
                "// timing-hygiene-ignore: TH2 bound hang deadline",
                "Thread.sleep(System.nanoTime())",
            ),
        )
    }

    @Test
    fun `a trailing ignore after code is a violation and does not suppress`() {
        assertEquals(
            listOf("TH-SUPPRESSION", "TH1"),
            rules(test, "Thread.sleep(5) // timing-hygiene-ignore: TH1 inject fake peer"),
        )
    }

    @Test
    fun `malformed ignores are violations`() {
        assertEquals(listOf("TH-SUPPRESSION", "TH1"), rules(test, "// timing-hygiene-ignore: TH1", "Thread.sleep(5)"))
        assertEquals(listOf("TH-SUPPRESSION", "TH1"), rules(test, "// timing-hygiene-ignore: TH1 inject", "Thread.sleep(5)"))
        assertEquals(listOf("TH-SUPPRESSION", "TH1"), rules(test, "// timing-hygiene-ignore: TH1 whatever x", "Thread.sleep(5)"))
        assertEquals(listOf("TH-SUPPRESSION"), rules(test, "// timing-hygiene-ignore: TH9 inject x"))
        assertEquals(listOf("TH-SUPPRESSION"), rules(test, "// timing-hygiene-ignore:"))
    }

    @Test
    fun `file marker needs a kind and a reason`() {
        assertEquals(listOf("TH-SUPPRESSION"), rules(test, "// timing-hygiene-file: poll-helper"))
        assertEquals(listOf("TH-SUPPRESSION"), rules(test, "// timing-hygiene-file: whatever reason"))
        assertEquals(emptyList(), rules(test, poll))
    }

    @Test
    fun `the file marker alone suppresses nothing`() {
        assertEquals(listOf("TH1"), rules(test, poll, "Thread.sleep(5)"))
    }

    @Test
    fun `Windows line endings give the same findings and line numbers`() {
        val text: String = "val a = 1\r\n// timing-hygiene-ignore: TH1 inject fake peer\r\nThread.sleep(1)\r\nThread.sleep(2)\r\n"
        val hits: List<TimingHygieneViolation> = TimingHygieneScanner.scan(test, text)
        assertEquals(listOf("TH1"), hits.map { it.rule })
        assertEquals(listOf(4), hits.map { it.line })
    }

    @Test
    fun `one hit per line however many forms the line has`() {
        assertEquals(listOf("TH1"), rules(jvmTest, "Thread.sleep(1); TimeUnit.SECONDS.sleep(1)"))
    }

    // ---- baseline ratchet ----

    private fun problems(module: String, baselineText: String, path: String, vararg lines: String): List<String> {
        val hits: List<TimingHygieneViolation> = TimingHygieneScanner.scan(path, lines.joinToString("\n"))
        val (baseline: Map<String, Int>, bad: List<String>) = TimingHygieneScanner.parseBaseline(baselineText)
        return bad + TimingHygieneScanner.ratchet(module, TimingHygieneScanner.counts(module, hits), baseline, hits)
    }

    @Test
    fun `ratchet passes when counts equal the baseline`() {
        assertEquals(
            emptyList(),
            problems("m", "m/src/$test:TH1:2", test, "Thread.sleep(1)", "Thread.sleep(2)"),
        )
    }

    @Test
    fun `ratchet fails above the baseline and names the lines`() {
        val found: List<String> = problems("m", "m/src/$test:TH1:1", test, "Thread.sleep(1)", "Thread.sleep(2)")
        assertEquals(1, found.size)
        assertTrue("2 hit(s), baseline allows 1" in found.single() && "lines 1, 2" in found.single(), found.single())
    }

    @Test
    fun `a file or rule missing from the baseline counts as zero allowed`() {
        val found: List<String> = problems("m", "", test, "Thread.sleep(1)")
        assertTrue("baseline allows 0" in found.single(), found.single())
        val otherRule: List<String> = problems("m", "m/src/$test:TH1:1", test, "Thread.sleep(1)", "val n = System.nanoTime()")
        assertTrue("TH2" in otherRule.single() && "baseline allows 0" in otherRule.single(), otherRule.single())
    }

    @Test
    fun `ratchet fails below the baseline until the line is lowered`() {
        val lower: List<String> = problems("m", "m/src/$test:TH1:3", test, "Thread.sleep(1)")
        assertTrue("lower its baseline line to m/src/$test:TH1:1" in lower.single(), lower.single())
        val gone: List<String> = problems("m", "m/src/$test:TH1:1", test, "val x = 1")
        assertTrue("remove its baseline line" in gone.single(), gone.single())
    }

    @Test
    fun `ratchet reads only the modules own lines`() {
        assertEquals(emptyList(), problems("m", "other/src/$test:TH1:4", test, "val x = 1"))
        assertEquals(1, problems("m", "mm/src/$test:TH1:4\nm/src/$test:TH1:1", test, "val x = 1").size)
    }

    @Test
    fun `a suppressed hit is not counted against the baseline`() {
        assertEquals(
            emptyList(),
            problems("m", "m/src/$test:TH1:1", test, "Thread.sleep(1)", "// timing-hygiene-ignore: TH1 inject fake peer", "Thread.sleep(2)"),
        )
    }

    @Test
    fun `baseline text parsing accepts comments and blanks and rejects malformed lines`() {
        val (ok: Map<String, Int>, bad: List<String>) = TimingHygieneScanner.parseBaseline(
            "# comment\n\nm/src/commonTest/kotlin/A.kt:TH1:3\r\nm/src/commonTest/kotlin/B.kt:TH2:1\n",
        )
        assertEquals(emptyList(), bad)
        assertEquals(mapOf("m/src/commonTest/kotlin/A.kt:TH1" to 3, "m/src/commonTest/kotlin/B.kt:TH2" to 1), ok)
        val (_: Map<String, Int>, broken: List<String>) = TimingHygieneScanner.parseBaseline(
            "m/src/a.kt:TH1:0\nm/src/a.kt:TH7:1\nnonsense\nm/src/a.kt:TH1:x\nm/src/b.kt:TH1:1\nm/src/b.kt:TH1:2",
        )
        assertEquals(5, broken.size)
    }

    @Test
    fun `baseline lines round trip through counts`() {
        val hits: List<TimingHygieneViolation> =
            TimingHygieneScanner.scan(jvmTest, "Thread.sleep(1)\nThread.sleep(2)\nval n = System.nanoTime()")
        val lines: List<String> = TimingHygieneScanner.baselineLines(TimingHygieneScanner.counts("m", hits))
        assertEquals(listOf("m/src/$jvmTest:TH1:2", "m/src/$jvmTest:TH2:1"), lines)
    }
}
