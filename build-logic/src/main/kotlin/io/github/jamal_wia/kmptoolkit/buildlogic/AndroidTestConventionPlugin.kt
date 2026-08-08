package io.github.jamal_wia.kmptoolkit.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/**
 * `kmptoolkit.androidtest` — JVM-side Android unit tests via Robolectric (`androidUnitTest/`, no
 * emulator). Required by any module whose Android-specific logic needs a real `Context`/resources
 * to test — see `docs/<module>/06-testing.md` for which modules apply it.
 *
 * `isIncludeAndroidResources` is the part that is easy to forget and expensive to debug without:
 * omit it and Robolectric cannot inflate Android resources, so a test fails at *runtime* with a
 * resource-not-found rather than at compile time (same rationale as DrLeoKMP's
 * `RobolectricConventionPlugin.kt`, which this mirrors).
 *
 * KMP modules put Robolectric-backed tests in `androidUnitTest`, not the classic `src/test/` —
 * there is no such directory in a `kotlin.multiplatform` module.
 */
class AndroidTestConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            var configured = false
            pluginManager.withPlugin("com.android.library") {
                configured = true
                extensions.configure<LibraryExtension> {
                    testOptions {
                        unitTests {
                            isIncludeAndroidResources = true
                        }
                    }
                }

                dependencies {
                    add("androidUnitTestImplementation", libs.library("robolectric"))
                    add("androidUnitTestImplementation", libs.library("androidx-test-ext-junit"))
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
