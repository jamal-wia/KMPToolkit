package io.github.jamal_wia.kmptoolkit.buildlogic

import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

/**
 * `kmptoolkit.library` — everything shared by every published kmptoolkit-* module: the KMP +
 * Android library plugin pair, the Android-target JVM level, `explicitApi()` in strict mode, and
 * ABI validation (`./gradlew checkKotlinAbi` / `updateKotlinAbi`), dumped to `<module>/api/`.
 *
 * Deliberately does **not** declare `iosArm64()` / `iosSimulatorArm64()`. Every module now declares
 * exactly those two Apple targets — `iosX64`, the legacy Intel simulator, is published by no module
 * in this repository — but target declaration stays in each module's own `kotlin { }` block: it is
 * the one thing about a module's shape worth seeing directly in its build file rather than behind a
 * plugin, exactly as `Paginator/paginator-core/build.gradle.kts` already does.
 *
 * `namespace` also stays in each module's own build file: it is not mechanically derivable from
 * the Gradle path, and guessing wrong produces a resource-merging failure that is tedious to trace
 * back here (same rationale as DrLeoKMP's `KmpConventionPlugins.kt`).
 */
@OptIn(ExperimentalAbiValidation::class)
class LibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.multiplatform")
            pluginManager.apply("com.android.library")

            extensions.configure<KotlinMultiplatformExtension> {
                explicitApi()

                androidTarget {
                    publishLibraryVariants("release")
                    compilerOptions {
                        jvmTarget.set(JvmTarget.JVM_11)
                    }
                }

                // Declaring the block is what enables ABI validation — Kotlin 2.4 removed the
                // `enabled` property.
                abiValidation {
                    // CI publishes from macOS and can build every target, but a contributor's
                    // machine might not (e.g. no Xcode) — don't fail their build over a target
                    // they can't compile locally; CI still catches a real ABI break.
                    keepLocallyUnsupportedTargets.set(true)
                    referenceDumpDir.set(layout.projectDirectory.dir("api"))
                }

                sourceSets.commonTest.dependencies {
                    implementation(kotlin("test"))
                }
            }

            extensions.configure<LibraryExtension> {
                compileSdk = libs.intVersion("compileSdk")
                defaultConfig {
                    minSdk = libs.intVersion("minSdk")
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_11
                    targetCompatibility = JavaVersion.VERSION_11
                }
            }
        }
    }
}
