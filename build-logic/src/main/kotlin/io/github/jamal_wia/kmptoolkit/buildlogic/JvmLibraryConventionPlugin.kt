package io.github.jamal_wia.kmptoolkit.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

/**
 * `kmptoolkit.library.jvm` — the desktop-only counterpart of `kmptoolkit.library`, for an artifact
 * that exists only on the JVM because the native library it wraps exists only there (today:
 * `kmptoolkit-video-player-vlcj`, over VLC).
 *
 * `kmptoolkit.library` always adds an Android target, and an Android target on a module whose whole
 * content is a desktop engine would publish an empty, misleading variant. This plugin keeps every
 * other rule of the suite identical — `explicitApi()` in strict mode, ABI validation dumped to
 * `<module>/api/`, `kotlin("test")` for tests — and stays a Kotlin Multiplatform module with a
 * single `jvm()` target rather than a plain `kotlin("jvm")` one, so `kmptoolkit.publish` publishes it
 * through the same vanniktech `KotlinMultiplatform` shape and the Gradle module metadata a consumer's
 * `jvmMain` resolves looks like every other kmptoolkit-* artifact's.
 *
 * The target is a bare `jvm()`, configured exactly like the `jvm()` targets the multiplatform
 * modules with a desktop exception declare, so a JVM-only artifact and the multiplatform module it
 * builds on (`kmptoolkit-video-player`) always share one bytecode level.
 *
 * This is not a way around docs/01-architecture.md § "Desktop targets": that rule is about *adding*
 * a desktop target to a multiplatform module. A JVM-only module is by definition something only a
 * desktop build asks for.
 */
@OptIn(ExperimentalAbiValidation::class)
class JvmLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("org.jetbrains.kotlin.multiplatform")

            extensions.configure<KotlinMultiplatformExtension> {
                explicitApi()

                jvm()

                abiValidation {
                    referenceDumpDir.set(layout.projectDirectory.dir("api"))
                }

                sourceSets.commonTest.dependencies {
                    implementation(kotlin("test"))
                }
            }
        }
    }
}
