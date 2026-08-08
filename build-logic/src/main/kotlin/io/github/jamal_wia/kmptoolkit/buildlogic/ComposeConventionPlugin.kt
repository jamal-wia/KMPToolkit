package io.github.jamal_wia.kmptoolkit.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension

/**
 * `kmptoolkit.compose` — Compose Multiplatform for a module that has UI: applied *alongside*
 * `kmptoolkit.library`, never alone.
 *
 * Compose Multiplatform 1.11+ does not publish an artifact for the `iosX64` target (documented in
 * `Paginator/paginator-compose-offset/build.gradle.kts`, the precedent this project follows). A
 * Compose module's own `kotlin { }` block must therefore declare only `iosArm64()` and
 * `iosSimulatorArm64()` — no `iosX64()` — which is exactly why target declaration lives in each
 * module's build file rather than in `kmptoolkit.library` (see that plugin's doc comment).
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
