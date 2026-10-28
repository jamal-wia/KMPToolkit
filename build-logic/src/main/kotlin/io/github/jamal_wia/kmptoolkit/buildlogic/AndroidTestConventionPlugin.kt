package io.github.jamal_wia.kmptoolkit.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies

/**
 * `kmptoolkit.androidtest` — JVM-side Android tests via Robolectric (`androidHostTest/`, no
 * emulator). Required by any module whose Android-specific logic needs a real `Context`/resources
 * to test — see `docs/<module>/06-testing.md` for which modules apply it.
 *
 * Adds only the Robolectric dependencies. The host-test compilation itself, including
 * `isIncludeAndroidResources`, is enabled by `kmptoolkit.library` for every module, because the
 * KMP library plugin's `withHostTest` can be configured from one place only (see
 * [LibraryConventionPlugin]). Robolectric depends on that flag: without it, it cannot read Android
 * resources or the merged manifest, so a test fails at *runtime* with a resource-not-found rather
 * than at compile time (same rationale as DrLeoKMP's `RobolectricConventionPlugin.kt`).
 *
 * KMP modules put Robolectric-backed tests in `androidHostTest`, not the classic `src/test/` —
 * there is no such directory in a `kotlin.multiplatform` module. They run under
 * `testAndroidHostTest` (which `allTests` also includes).
 */
class AndroidTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            var configured = false
            pluginManager.withPlugin("com.android.kotlin.multiplatform.library") {
                configured = true
                dependencies {
                    add("androidHostTestImplementation", libs.library("robolectric"))
                    add("androidHostTestImplementation", libs.library("androidx-test-ext-junit"))
                }
            }

            afterEvaluate {
                check(configured) {
                    "$path applies kmptoolkit.androidtest without an Android library plugin, so " +
                        "it did nothing. Apply kmptoolkit.library as well."
                }
            }
        }
    }
}
