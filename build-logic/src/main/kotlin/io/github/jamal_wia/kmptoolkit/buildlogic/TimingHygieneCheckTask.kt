package io.github.jamal_wia.kmptoolkit.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register

/**
 * `checkTimingHygiene` — runs [TimingHygieneScanner] over every `.kt` file under a module's
 * `src/<*Test>/kotlin` folders and compares the per-file counts with the ratchet baseline
 * `build-logic/timing-hygiene-baseline.txt`; it fails the build with one line per difference.
 * Registered by `kmptoolkit.library` and `kmptoolkit.library.jvm` and attached to `check`, so it runs
 * inside `./gradlew build`.
 *
 * Cacheable: the inputs are the test source text (relative-path sensitive: the first path segment is
 * the source set) and the baseline file; the output is a report file, written whether or not the
 * check passes, whose lines are the module's CURRENT counts in baseline format.
 */
@CacheableTask
internal abstract class TimingHygieneCheckTask : DefaultTask() {

    /** The module's test Kotlin sources, a tree rooted at the module's `src/` folder. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** The shared ratchet baseline (every module's lines; the task reads only its own). */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val baseline: RegularFileProperty

    /** The module's Gradle path, for the failure message. */
    @get:Input
    abstract val projectPath: Property<String>

    /** The module's folder relative to the repository root with `/` separators: the baseline key prefix. */
    @get:Input
    abstract val modulePath: Property<String>

    /** The module's current counts in baseline format, one line per file and rule; empty when there are no hits. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun scan() {
        val violations: MutableList<TimingHygieneViolation> = mutableListOf()
        sources.asFileTree.visit {
            if (!isDirectory && name.endsWith(".kt")) {
                violations += TimingHygieneScanner.scan(relativePath.pathString, file.readText())
            }
        }
        val module: String = modulePath.get()
        val counts: Map<String, Int> = TimingHygieneScanner.counts(module, violations)
        report.get().asFile.apply {
            parentFile.mkdirs()
            val lines: List<String> = TimingHygieneScanner.baselineLines(counts)
            writeText(lines.joinToString(separator = "\n", postfix = if (lines.isEmpty()) "" else "\n"))
        }
        val (parsed: Map<String, Int>, malformed: List<String>) =
            TimingHygieneScanner.parseBaseline(baseline.get().asFile.readText())
        val problems: List<String> =
            violations.filter { it.rule == TimingHygieneScanner.SUPPRESSION_RULE }.map { it.toString() } +
                malformed +
                TimingHygieneScanner.ratchet(module, counts, parsed, violations)
        if (problems.isNotEmpty()) {
            throw GradleException(
                "checkTimingHygiene found ${problems.size} problem(s) in ${projectPath.get()} " +
                    "(paths relative to src/ unless they start with the module folder):\n" +
                    problems.joinToString("\n") { "  $it" } +
                    "\nFix the test, or suppress a legitimate case with a reason: " +
                    "'// timing-hygiene-ignore: <rule> <category> <text>' on the line above. " +
                    "See CONTRIBUTING.md, \"Timing and determinism in tests\".",
            )
        }
    }
}

/** Registers `checkTimingHygiene` on this module and makes `check` depend on it. */
internal fun Project.registerTimingHygieneCheck() {
    val path: String = this.path
    val module: String = projectDir.relativeTo(rootDir).invariantSeparatorsPath
    val scan = tasks.register<TimingHygieneCheckTask>("checkTimingHygiene") {
        group = "verification"
        description = "Scans test sources for sleeps, wall-clock reads, unseeded randomness and other flaky-test inputs."
        sources.from(
            fileTree(layout.projectDirectory.dir("src")) {
                include("*Test/kotlin/**/*.kt")
            },
        )
        baseline.set(isolated.rootProject.projectDirectory.file("build-logic/timing-hygiene-baseline.txt"))
        projectPath.set(path)
        modulePath.set(module)
        report.set(layout.buildDirectory.file("reports/timing-hygiene/report.txt"))
    }
    tasks.matching { it.name == "check" }.configureEach { dependsOn(scan) }
}
