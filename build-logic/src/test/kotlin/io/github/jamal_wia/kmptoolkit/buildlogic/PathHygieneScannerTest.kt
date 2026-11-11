package io.github.jamal_wia.kmptoolkit.buildlogic

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Positive and negative samples for every rule of [PathHygieneScanner], and for both kinds of
 * suppression with and without a reason. The samples are the contract of the rules in the plan
 * (plans/windows-paths-advice.md, Guard, Extra 1), not a copy of what the regexes happen to match.
 */
class PathHygieneScannerTest {

    private fun rules(path: String, vararg lines: String): List<String> =
        PathHygieneScanner.scan(path, lines.joinToString("\n")).map { it.rule }

    private val test: String = "commonTest/kotlin/p/A.kt"
    private val main: String = "commonMain/kotlin/p/A.kt"

    // ---- PH1 ----

    @Test
    fun `PH1 flags File separator and separatorChar in main and test`() {
        assertEquals(listOf("PH1"), rules(main, "val s = File.separator"))
        assertEquals(listOf("PH1"), rules(test, "val c = java.io.File.separatorChar"))
        assertEquals(listOf("PH1"), rules("jvmMain/kotlin/A.kt", "x + File.separator + y"))
    }

    @Test
    fun `PH1 ignores lookalikes`() {
        assertEquals(emptyList(), rules(main, "val s = MyFile.separator", "val p = File.pathSeparatorLike"))
        assertEquals(emptyList(), rules(main, "val s = file.separator"))
    }

    // ---- PH2 ----

    @Test
    fun `PH2 flags every POSIX only API`() {
        val samples: List<String> = listOf(
            "Files.setPosixFilePermissions(p, perms)",
            "val x: Set<PosixFilePermission> = emptySet()",
            "f.setReadable(false)",
            "f.setWritable(false, false)",
            "f.setExecutable(true)",
            "f.setReadOnly()",
            "ManagementFactory.getOperatingSystemMXBean() as UnixOperatingSystemMXBean",
            "File(\"/proc/self/status\")",
            "File(\"/dev/fd\").list()",
        )
        for (sample in samples) assertEquals(listOf("PH2"), rules(test, sample), sample)
    }

    @Test
    fun `PH2 applies in a shared jvm source set and ignores lookalikes`() {
        assertEquals(listOf("PH2"), rules("jvmShared/kotlin/A.kt", "f.setReadable(true)"))
        assertEquals(emptyList(), rules(test, "val setReadableFlag = 1", "File(\"/process/x\")", "File(\"/dev/null\")"))
    }

    // ---- PH3 ----

    @Test
    fun `PH3 flags taking a path apart on a slash in test sets`() {
        val samples: List<String> = listOf(
            "p.substringBeforeLast('/')",
            "p.substringAfterLast(\"/\")",
            "p.split('/')",
            "p.split(\"/\")",
            "p.lastIndexOf('/')",
            "p.lastIndexOf( \"/\" )",
        )
        for (sample in samples) assertEquals(listOf("PH3"), rules(test, sample), sample)
        assertEquals(listOf("PH3"), rules("androidHostTest/kotlin/A.kt", "p.split('/')"))
    }

    @Test
    fun `PH3 does not apply to main code and ignores other separators`() {
        assertEquals(emptyList(), rules(main, "p.substringAfterLast('/')"))
        assertEquals(emptyList(), rules(test, "p.split(',')", "p.substringAfterLast('.')", "p.lastIndexOf('\\\\')"))
    }

    // ---- PH4 ----

    @Test
    fun `PH4 flags a path like name joined with a slash in test sets`() {
        val samples: List<String> = listOf(
            "val f = \"\$base/x\"",
            "val f = \"\${dir}/x\"",
            "val f = \"\$tempDir/x\"",
            "val f = \"\${root()}/x\"",
            "val f = \"\$ROOT_PATH/x\"",
            "val f = base + \"/x\"",
            "val f = dir.absolutePath + \"/x\"",
            "val f = canonicalPath + \"/x\"",
        )
        for (sample in samples) assertEquals(listOf("PH4"), rules(test, sample), sample)
    }

    @Test
    fun `PH4 ignores non path names and main code`() {
        assertEquals(emptyList(), rules(test, "val f = \"\$name/x\"", "val u = \"\$host/api\"", "val a = name + \"/x\""))
        assertEquals(emptyList(), rules(main, "val f = \"\$base/x\""))
    }

    // ---- PH5 ----

    @Test
    fun `PH5 flags startsWith slash in main source sets only`() {
        assertEquals(listOf("PH5"), rules(main, "if (p.startsWith('/')) {"))
        assertEquals(listOf("PH5"), rules("androidMain/kotlin/A.kt", "p.startsWith(\"/\")"))
        assertEquals(listOf("PH5"), rules("jvmMain/kotlin/A.kt", "p.startsWith( '/' )"))
        assertEquals(emptyList(), rules(test, "p.startsWith('/')"))
    }

    @Test
    fun `PH5 ignores other prefixes`() {
        assertEquals(emptyList(), rules(main, "p.startsWith('.')", "p.startsWith(\"ab\")", "p.endsWith('/')"))
    }

    // ---- scope ----

    @Test
    fun `iOS and other native source sets are never scanned`() {
        val line: String = "val s = File.separator + base + \"/x\" + p.startsWith('/')"
        for (set in listOf("iosMain", "iosTest", "iosArm64Main", "iosSimulatorArm64Test", "appleMain", "nativeTest")) {
            assertEquals(emptyList(), rules("$set/kotlin/A.kt", line), set)
        }
    }

    @Test
    fun `comment only lines are skipped`() {
        assertEquals(emptyList(), rules(main, "// File.separator", " * uses File.separatorChar", "/* f.setReadable(true) */"))
    }

    @Test
    fun `import and package lines are skipped`() {
        assertEquals(
            emptyList(),
            rules(test, "package a.File.separator", "import java.nio.file.attribute.PosixFilePermission"),
        )
        assertEquals(listOf("PH2"), rules(test, "val p: PosixFilePermission = x"))
    }

    @Test
    fun `violations carry file, one based line and the rule`() {
        val found: List<PathHygieneViolation> =
            PathHygieneScanner.scan(test, "ok\nval f = \"\$base/x\"\n")
        assertEquals(1, found.size)
        assertEquals(test, found[0].file)
        assertEquals(2, found[0].line)
        assertEquals("PH4", found[0].rule)
    }

    // ---- line suppression ----

    @Test
    fun `line suppression with a reason silences the named rule on the same line`() {
        assertEquals(
            emptyList(),
            rules(main, "val s = File.separator // path-hygiene-ignore: PH1 java.nio.file is missing below API 26"),
        )
    }

    @Test
    fun `line suppression on the line above applies to the next line only`() {
        val found: List<String> = rules(
            main,
            "// path-hygiene-ignore: PH1 java.nio.file is missing below API 26",
            "val s = File.separator",
            "val t = File.separator",
        )
        assertEquals(listOf("PH1"), found)
        assertEquals(3, PathHygieneScanner.scan(main, "// path-hygiene-ignore: PH1 r\nval s = File.separator\nval t = File.separator").single().line)
    }

    @Test
    fun `line suppression only silences the rule it names`() {
        assertEquals(
            listOf("PH2"),
            rules(main, "File.separator; f.setReadable(true) // path-hygiene-ignore: PH1 reason"),
        )
        assertEquals(
            emptyList(),
            rules(main, "File.separator; f.setReadable(true) // path-hygiene-ignore: PH1,PH2 both are host API on purpose"),
        )
    }

    @Test
    fun `a bare line suppression is itself a violation and silences nothing`() {
        val found: List<PathHygieneViolation> =
            PathHygieneScanner.scan(main, "val s = File.separator // path-hygiene-ignore: PH1")
        assertEquals(setOf("PH1", PathHygieneScanner.SUPPRESSION_RULE), found.map { it.rule }.toSet())
        assertTrue(found.first { it.rule == PathHygieneScanner.SUPPRESSION_RULE }.message.contains("reason"))
    }

    @Test
    fun `a line suppression without rules or with an unknown rule is a violation`() {
        assertEquals(
            listOf(PathHygieneScanner.SUPPRESSION_RULE),
            rules(main, "val x = 1 // path-hygiene-ignore:"),
        )
        assertEquals(
            listOf(PathHygieneScanner.SUPPRESSION_RULE),
            rules(main, "val x = 1 // path-hygiene-ignore: PH9 some reason"),
        )
        // A reason alone, no rule id, must not be read as a rule id plus reason.
        assertEquals(
            setOf("PH1", PathHygieneScanner.SUPPRESSION_RULE),
            rules(main, "val s = File.separator // path-hygiene-ignore: because I said so").toSet(),
        )
    }

    // ---- file suppression ----

    @Test
    fun `file suppression with a reason exempts PH3, PH4 and PH5 anywhere in the file`() {
        val text: String = listOf(
            "val a = \"\$base/x\"",
            "val b = p.substringAfterLast('/')",
            "// path-hygiene-file: logical-paths fake file system, every path is a logical name",
        ).joinToString("\n")
        assertEquals(emptyList(), PathHygieneScanner.scan(test, text))
    }

    @Test
    fun `file suppression does not exempt the host API rules`() {
        val text: String = "val s = File.separator\nf.setReadable(true)\n// path-hygiene-file: logical-paths fake fs"
        assertEquals(listOf("PH1", "PH2"), PathHygieneScanner.scan(test, text).map { it.rule })
    }

    @Test
    fun `a bare file suppression is a violation and exempts nothing`() {
        val found: List<PathHygieneViolation> = PathHygieneScanner.scan(
            test,
            "val a = \"\$base/x\"\n// path-hygiene-file: logical-paths",
        )
        assertEquals(setOf("PH4", PathHygieneScanner.SUPPRESSION_RULE), found.map { it.rule }.toSet())
    }

    @Test
    fun `an unknown file suppression kind is a violation`() {
        assertEquals(
            listOf(PathHygieneScanner.SUPPRESSION_RULE),
            rules(test, "// path-hygiene-file: everything because"),
        )
    }

    @Test
    fun `a suppression in an iOS source set is not even parsed`() {
        assertEquals(emptyList(), rules("iosMain/kotlin/A.kt", "// path-hygiene-ignore: PH1"))
    }

    // ---- review round 1: file exemption in production, PH5 forms, PH4 templates, regex gaps ----

    @Test
    fun `a file suppression in a production source set is a violation and exempts nothing`() {
        for (set in listOf("commonMain", "androidMain", "jvmMain", "jvmShared")) {
            val found: List<String> = rules(
                "$set/kotlin/A.kt",
                "if (p.startsWith('/')) {}",
                "// path-hygiene-file: logical-paths logical names",
            )
            assertEquals(setOf("PH5", PathHygieneScanner.SUPPRESSION_RULE), found.toSet(), set)
        }
    }

    @Test
    fun `PH5 flags a named separator constant, a lone slash declaration and the index forms`() {
        val samples: List<String> = listOf(
            "if (!path.startsWith(PATH_SEPARATOR)) return",
            "path.startsWith(SLASH)",
            "path.startsWith(UNIX_PATH_SEPARATOR_CHAR)",
            "const val PATH_SEPARATOR = \"/\"",
            "private val sep: Char = '/'",
            "val separator = \"/\"",
            "if (path[0] == '/') {}",
            "if (path.first() == '/') {}",
        )
        for (sample in samples) assertEquals(listOf("PH5"), rules(main, sample), sample)
        assertEquals(listOf("PH5"), rules("jvmShared/kotlin/A.kt", "path.startsWith(PATH_SEPARATOR)"))
    }

    @Test
    fun `PH5 ignores near misses of the new forms`() {
        assertEquals(
            emptyList(),
            rules(
                main,
                "path.startsWith(PREFIX)",
                "val sep = \"//\"",
                "val url = \"/a\"",
                "if (path[0] == 'a') {}",
                "path.startsWith(separatorOf(x))",
            ),
        )
        assertEquals(emptyList(), rules(test, "const val PATH_SEPARATOR = \"/\""))
    }

    @Test
    fun `PH4 flags a dotted expression joined with a slash in a template`() {
        val samples: List<String> = listOf(
            "val f = \"\${tempDir.path}/x\"",
            "val f = \"\${root.absolutePath}/x\"",
            "val f = \"\${storage.getTempFilePath(unit)}/child\"",
            "val f = \"\${UnitFileLayout.INTERNAL_DIRECTORY}/x\"",
        )
        for (sample in samples) assertEquals(listOf("PH4"), rules(test, sample), sample)
        assertEquals(emptyList(), rules(test, "val u = \"http://h:\${server.localPort}/u\"", "val n = \"\${item.name}/x\""))
    }

    @Test
    fun `PH3 flags the two argument forms`() {
        assertEquals(listOf("PH3"), rules(test, "p.substringBeforeLast('/', \"\")"))
        assertEquals(listOf("PH3"), rules(test, "p.lastIndexOf('/', i)"))
        assertEquals(listOf("PH3"), rules(test, "p.split(\"/\", limit = 2)"))
        assertEquals(emptyList(), rules(test, "p.substringBeforeLast('/x', \"\")"))
    }

    @Test
    fun `PH2 flags the permission setters without a receiver but not a helper that shares the name`() {
        assertEquals(listOf("PH2"), rules(test, "file.apply { setWritable(false) }"))
        assertEquals(listOf("PH2"), rules(test, "run { setReadOnly() }"))
        // A call of a local helper that merely shares the name is not the java.io setter.
        assertEquals(emptyList(), rules(test, "setReadOnly(dir)", "override fun setWritable(writable: Boolean): Boolean = true"))
    }

    @Test
    fun `PH1 flags the system property, the file system separator and the static import`() {
        assertEquals(listOf("PH1"), rules(main, "val s = System.getProperty(\"file.separator\")"))
        assertEquals(listOf("PH1"), rules(main, "val s = FileSystems.getDefault().separator"))
        assertEquals(listOf("PH1"), rules(main, "import java.io.File.separator"))
        assertEquals(listOf("PH1"), rules(main, "import java.io.File.separatorChar"))
        assertEquals(emptyList(), rules(main, "import java.io.File"))
        assertEquals(emptyList(), rules(main, "import java.io.File.createTempFile"))
    }
}
