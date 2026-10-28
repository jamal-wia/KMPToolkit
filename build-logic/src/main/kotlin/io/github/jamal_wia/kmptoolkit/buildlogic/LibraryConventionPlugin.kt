package io.github.jamal_wia.kmptoolkit.buildlogic

import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

/**
 * `kmptoolkit.library` — everything shared by every published kmptoolkit-* module: Kotlin
 * Multiplatform with the Android Gradle Plugin's KMP library plugin
 * (`com.android.kotlin.multiplatform.library`), the Android target's SDK levels and JVM level,
 * Android host tests, the desktop bytecode level of any `jvm()` target a module declares
 * ([DESKTOP_JVM_TARGET]), `explicitApi()` in strict mode, and ABI validation
 * (`./gradlew checkKotlinAbi` / `updateKotlinAbi`), dumped to `<module>/api/`.
 *
 * The KMP library plugin, not `com.android.library`: AGP 9 no longer supports
 * `org.jetbrains.kotlin.multiplatform` next to `com.android.library` with its new DSL and built-in
 * Kotlin, and AGP 10 removes the opt-outs that kept that pairing alive. The Android target it adds
 * has a single variant — no build types, no flavours — so there is no release variant to select
 * for publication: what is built is what is published.
 *
 * Deliberately does **not** declare `iosArm64()` / `iosSimulatorArm64()`. Every module now declares
 * exactly those two Apple targets — `iosX64`, the legacy Intel simulator, is published by no module
 * in this repository — but target declaration stays in each module's own `kotlin { }` block: it is
 * the one thing about a module's shape worth seeing directly in its build file rather than behind a
 * plugin, exactly as `Paginator/paginator-core/build.gradle.kts` already does.
 *
 * `namespace` also stays in each module's own build file (`kotlin { android { namespace = … } }`):
 * it is not mechanically derivable from the Gradle path, and guessing wrong produces a
 * resource-merging failure that is tedious to trace back here (same rationale as DrLeoKMP's
 * `KmpConventionPlugins.kt`).
 */
@OptIn(ExperimentalAbiValidation::class)
class LibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.multiplatform")
            pluginManager.apply("com.android.kotlin.multiplatform.library")

            extensions.configure<KotlinMultiplatformExtension> {
                explicitApi()

                // The plugin creates exactly one Android target, named `android`, as it is applied;
                // the module's own `kotlin { android { } }` block then only adds the namespace.
                targets.withType<KotlinMultiplatformAndroidLibraryTarget>().configureEach {
                    compileSdk = libs.intVersion("compileSdk")
                    minSdk = libs.intVersion("minSdk")
                    // One level for Kotlin and Java: this plugin has no separate compileOptions.
                    compilerOptions {
                        jvmTarget.set(JvmTarget.JVM_11)
                    }

                    // Host (JVM) tests are off by default in this plugin, which would silently stop
                    // every module's commonTest from running on the Android JVM — something
                    // com.android.library did for every module. Enabled here, once, for all of them:
                    // `withHostTest` replaces its options on each call, so a second call elsewhere
                    // would discard these. Android resources are included for every module rather
                    // than only the Robolectric ones (kmptoolkit.androidtest): without them a
                    // Robolectric test cannot read the merged manifest, and fails at *runtime* with a
                    // resource-not-found rather than at compile time.
                    withHostTest {
                        isIncludeAndroidResources = true
                    }
                }

                // Only modules under the desktop exception declare `jvm()`, in their own build
                // file; this fixes the level whichever of them does.
                pinDesktopBytecode(this)

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
        }
    }
}
