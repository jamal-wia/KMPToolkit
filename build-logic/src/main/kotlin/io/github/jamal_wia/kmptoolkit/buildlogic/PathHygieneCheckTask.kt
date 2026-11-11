package io.github.jamal_wia.kmptoolkit.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register

/**
 * `checkPathHygiene` — runs [PathHygieneScanner] over every `.kt` file under a module's
 * `src/<sourceSet>/kotlin` folders (iOS and other native source sets are left out of the input, so editing them never re-runs it) and
 * fails the build with one line per finding. Registered by `kmptoolkit.library` and
 * `kmptoolkit.library.jvm` and attached to `check`, so it runs inside `./gradlew build`.
 *
 * Cacheable: the input is the source text (relative-path sensitive — the first path segment is the
 * source set, which decides which rules apply) and the output is a small report file that is written
 * whether or not the scan passes.
 */
@CacheableTask
internal abstract class PathHygieneCheckTask : DefaultTask() {

    /** The module's Kotlin sources, a tree rooted at the module's `src/` folder. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** The module's Gradle path, for the failure message (read here, not from `Task.project` at execution time). */
    @get:Input
    abstract val projectPath: Property<String>

    /** A one-line-per-finding report; empty when the scan is clean. */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun scan() {
        val violations: MutableList<PathHygieneViolation> = mutableListOf()
        sources.asFileTree.visit {
            if (!isDirectory && name.endsWith(".kt")) {
                violations += PathHygieneScanner.scan(relativePath.pathString, file.readText())
            }
        }
        val sorted: List<PathHygieneViolation> = violations.sortedWith(compareBy({ it.file }, { it.line }))
        report.get().asFile.apply {
            parentFile.mkdirs()
            writeText(sorted.joinToString(separator = "\n", postfix = if (sorted.isEmpty()) "" else "\n"))
        }
        if (sorted.isNotEmpty()) {
            throw GradleException(
                "checkPathHygiene found ${sorted.size} path-hygiene problem(s) in ${projectPath.get()} " +
                    "(paths relative to src/):\n" + sorted.joinToString("\n") { "  $it" } +
                    "\nFix the code, or suppress a legitimate case with a reason: " +
                    "'// path-hygiene-ignore: <rule> <reason>'. See CONTRIBUTING.md, " +
                    "\"Paths and the path-hygiene check\".",
            )
        }
    }
}

/** Registers `checkPathHygiene` on this module and makes `check` depend on it. */
internal fun Project.registerPathHygieneCheck() {
    val modulePath: String = path
    val scan = tasks.register<PathHygieneCheckTask>("checkPathHygiene") {
        group = "verification"
        description = "Scans Kotlin sources for separator and POSIX assumptions that break on Windows."
        sources.from(
            fileTree(layout.projectDirectory.dir("src")) {
                include("*/kotlin/**/*.kt")
                // The names PathHygieneScanner.isScanned skips, as patterns, so that a change in an
                // iOS source set does not invalidate the task.
                PathHygieneScanner.SKIPPED_PREFIXES.forEach { exclude("$it*/**") }
            },
        )
        projectPath.set(modulePath)
        report.set(layout.buildDirectory.file("reports/path-hygiene/report.txt"))
    }
    tasks.matching { it.name == "check" }.configureEach { dependsOn(scan) }
}
