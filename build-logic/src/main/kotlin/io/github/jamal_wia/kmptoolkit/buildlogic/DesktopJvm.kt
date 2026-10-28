package io.github.jamal_wia.kmptoolkit.buildlogic

import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget

/**
 * The bytecode level of every desktop (`jvm()`) artifact in the suite: the oldest JDK a desktop
 * consumer can run any kmptoolkit-* module on, so it is part of the published contract.
 *
 * Stated here rather than left to the default, because the default is "whatever JDK the Gradle
 * daemon runs on" (`gradle/gradle-daemon-jvm.properties`): raising the daemon JDK — for instance to
 * run the OpenJFX tests without a separate launcher — would otherwise silently raise every
 * consumer's minimum with it. The Android target is unaffected; it keeps its own level in
 * [LibraryConventionPlugin].
 */
internal val DESKTOP_JVM_TARGET: JvmTarget = JvmTarget.JVM_21

/**
 * Pins every `jvm()` target of this module — present now or declared later in the module's own
 * build file — to [DESKTOP_JVM_TARGET], for Kotlin and for the Java compilation Kotlin 2.1.20+
 * always creates alongside it. The two must agree, or KGP's JVM-target validation fails the build.
 *
 * One function used by both `kmptoolkit.library` and `kmptoolkit.library.jvm`, so a JVM-only engine
 * and the multiplatform module it builds on can never drift to different levels.
 */
internal fun Project.pinDesktopBytecode(kotlin: KotlinMultiplatformExtension) {
    kotlin.targets.withType<KotlinJvmTarget>().configureEach {
        compilerOptions {
            jvmTarget.set(DESKTOP_JVM_TARGET)
        }
        val targetName: String = name
        tasks.withType<JavaCompile>().configureEach {
            if (name.startsWith("compile${targetName.replaceFirstChar(Char::uppercaseChar)}")) {
                options.release.set(DESKTOP_JVM_TARGET.target.toInt())
            }
        }
    }
}
