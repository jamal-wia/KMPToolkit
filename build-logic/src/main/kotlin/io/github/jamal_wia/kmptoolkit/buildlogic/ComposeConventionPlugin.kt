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
 * `compose.runtime` + `compose.foundation`, `kmptoolkit-logging-overlay` needs `compose.ui` +
 * `compose.material3` — a uniform set would hand both modules dependencies they don't use, so
 * those stay explicit per module (same rationale as DrLeoKMP's `ComposeConventionPlugin.kt`).
 */
class ComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.compose")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")

            extensions.configure<ComposeCompilerGradlePluginExtension> {
                // Read after a release build to see which composables are skippable/restartable
                // and which parameters are inferred unstable.
                reportsDestination.set(layout.buildDirectory.dir("compose_compiler"))
                metricsDestination.set(layout.buildDirectory.dir("compose_compiler"))
                includeSourceInformation.set(true)
            }
        }
    }
}
