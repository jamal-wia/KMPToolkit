package io.github.jamal_wia.kmptoolkit.buildlogic

import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.withType

/** The prefix of every test knob: a Gradle property `-Pkmptoolkit.test.<knob>=<value>` becomes a system property of the same name. */
internal const val TEST_KNOB_PREFIX: String = "kmptoolkit.test."

/** One line naming every knob in [knobs] with its value, sorted by name; `null` when no knob is set. */
internal fun describeTestKnobs(knobs: Map<String, String>): String? =
    if (knobs.isEmpty()) null else knobs.toSortedMap().entries.joinToString(", ") { (key: String, value: String) -> "$key=$value" }

/**
 * Hands every Gradle property that starts with `kmptoolkit.test.` to the test JVM of every test task of this
 * module as a system property of the same name, and prints the knobs once when the task starts.
 *
 * Why: test knobs (trial counts, soak lengths, seeds, trace files) used to be read from environment variables,
 * and a test JVM inherits the environment of the Gradle daemon that started it, so a value exported in the shell
 * once changed every later run without anyone seeing it. A knob is set only through a Gradle property (the command
 * line, `~/.gradle/gradle.properties`, the project's `gradle.properties` or an `ORG_GRADLE_PROJECT_` environment
 * variable), and the printed line shows which ones that run used, wherever they came from. A knob that is not given keeps the
 * default written in the test, so nothing changes without the property. Test JVMs only; no main source set,
 * no published artifact and no `api/` dump is touched.
 */
internal fun Project.forwardTestKnobs() {
    val knobs: Map<String, String> = providers.gradlePropertiesPrefixedBy(TEST_KNOB_PREFIX).get()
    val line: String? = describeTestKnobs(knobs)
    tasks.withType<Test>().configureEach {
        knobs.forEach { (key: String, value: String) -> systemProperty(key, value) }
        if (line != null) doFirst { logger.lifecycle("kmptoolkit test knobs for $path: $line") }
    }
}
