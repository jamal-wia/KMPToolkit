package io.github.jamal_wia.kmptoolkit.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

/**
 * `kmptoolkit.compose` — Compose Multiplatform for a module that has UI: applied *alongside*
 * `kmptoolkit.library`, never alone.
 *
 * Compose Multiplatform 1.11+ publishes no artifact for the `iosX64` target, so a Compose module
 * could never declare it. That is no longer a difference from the rest of the suite: no module in
 * this repository targets `iosX64`, which keeps the published artifact count down (see
 * `RELEASING.md`) and costs nothing — the Intel simulator is superseded by `iosSimulatorArm64` on
 * every Apple-silicon Mac.
 *
 * Deliberately adds **no** Compose dependencies: `kmptoolkit-systembars` needs only
 * `libs.compose.runtime` + `libs.compose.foundation`, `kmptoolkit-logging-overlay` needs
 * `libs.compose.ui` + `libs.compose.material3` — a uniform set would hand both modules dependencies
 * they don't use, so those stay explicit per module, from the version catalog (same rationale as
 * DrLeoKMP's `ComposeConventionPlugin.kt`).
 *
 * Compose compiler reports and metrics are a diagnostic, so they are written only when the build is
 * run with `-Pkmptoolkit.composeReports=true` (files land in `build/compose_compiler`). They are off
 * by default because the compiler names the files after the Kotlin module name, which contains a
 * `:` (`io_github_jamal-wia:kmptoolkit-...`), and NTFS rejects `:` in a file name: on Windows every
 * Compose module failed to compile with `IOException: Invalid file path`. The module name itself is
 * not changed, because that would change the published ABI.
 */
class ComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.compose")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            val composeReports: Boolean =
                providers.gradleProperty("kmptoolkit.composeReports").orNull == "true"

            extensions.configure<ComposeCompilerGradlePluginExtension> {
                if (composeReports) {
                    // Read after a release build to see which composables are skippable/restartable
                    // and which parameters are inferred unstable.
                    reportsDestination.set(layout.buildDirectory.dir("compose_compiler"))
                    metricsDestination.set(layout.buildDirectory.dir("compose_compiler"))
                }
                includeSourceInformation.set(true)
            }
        }
    }
}
