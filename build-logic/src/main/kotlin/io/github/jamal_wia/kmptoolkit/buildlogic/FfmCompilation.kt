package io.github.jamal_wia.kmptoolkit.buildlogic

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.testing.Test
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaLauncher
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinJvmCompilation
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

/** The JDK whose class library the `ffm` compilation is compiled and run against: FFM is final from JDK 22, and 25 is the current LTS. */
internal const val FFM_TOOLCHAIN_JDK: Int = 25

/** The oldest JDK whose API the `ffm` sources may use (java.lang.foreign is final in 22); enforced by the check-only compilation. */
internal const val FFM_API_RELEASE: Int = 22

/** Name of the second compilation of the `jvm` target: the code that calls the operating system through java.lang.foreign. */
internal const val FFM_COMPILATION: String = "ffm"

/** Name of the check-only compilation: the same sources against the JDK [FFM_API_RELEASE] API, output never packed. */
internal const val FFM_API_CHECK_COMPILATION: String = "ffmApiCheck"

/** Name of the compilation of the tests that call the FFM API themselves (they need the JDK 25 class library too). */
internal const val FFM_TEST_COMPILATION: String = "ffmTest"

/** The Gradle task that runs the tests of [FFM_TEST_COMPILATION] on a JDK [FFM_TOOLCHAIN_JDK] launcher. */
internal const val FFM_TEST_TASK: String = "jvmTestFfm"

/**
 * `kmptoolkit.ffm` — a runtime-gated, dependency-free native-call layer inside a module that is otherwise built for
 * JDK 21 ([DESKTOP_JVM_TARGET]). Applied after `kmptoolkit.library` (or `kmptoolkit.library.jvm`) to a module that
 * declares a `jvm()` target; the sources live in `src/jvmFfm/kotlin`, their tests in `src/jvmFfmTest/kotlin`.
 *
 * What it wires, and why (plan 3.9, decision D-6 of the storage plan):
 * - **`ffm` compilation.** A second Kotlin compilation of the `jvm` target, associated with `main` (it sees the
 *   module's `internal` declarations), compiled with a JDK [FFM_TOOLCHAIN_JDK] toolchain at the desktop bytecode
 *   level 21. `java.lang.foreign` is final only from JDK 22, so these classes must load on a JDK 22+ runtime and
 *   nowhere else; the module loads them by class name behind a `Runtime.version().feature() >= 22` gate. Its output
 *   is packed into the module's `jvm` jar, so the one artifact carries both halves and plain-store users load none of it.
 * - **Check-only API compilation.** The same sources compiled again with `-Xjdk-release=22` on the same toolchain
 *   (`ct.sym` of the JDK 25 install, no download). It fails the build on any API newer than JDK 22, which would
 *   otherwise compile against JDK 25 and fail with a `NoSuchMethodError` on a JDK 22-24 runtime. Its output goes to a
 *   directory nothing reads, so the shipped bytecode is the first compilation's. It is a dependency of `jvmJar` and
 *   of `check`, so every build runs it.
 * - **`ffmTest` compilation and [FFM_TEST_TASK].** Tests that call `java.lang.foreign` themselves (fake native
 *   functions as upcall stubs, the real-store suites) cannot compile against the JDK 21 class library of `jvmTest`,
 *   so they are a third compilation, associated with `ffm`, run by a task on a JDK [FFM_TOOLCHAIN_JDK] launcher (the
 *   pattern of `kmptoolkit-video-player-javafx`). The `jvmTest` classpath also receives the `ffm` classes, so a
 *   forked-JVM test can start a JDK 25 child that loads them.
 *
 * Test properties handed to the test JVMs: `kmptoolkit.test.javaHome21` and `kmptoolkit.test.javaHome25` (the
 * installation directories of the two toolchains, for tests that fork a child JVM) and `kmptoolkit.test.targetJar`
 * (the jvm jar, a dependency of the `jvmTest` task). The real-store suites are opt-in through `-Pkmptoolkit.secretstore=macos|windows|linux`
 * (forwarded as the system property `kmptoolkit.secretstore`), and `-Pkmptoolkit.secretstore.required=true` turns a
 * skip into a failure.
 */
class FfmConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
                extensions.configure<KotlinMultiplatformExtension> {
                    targets.withType<KotlinJvmTarget>().configureEach { wireFfm(this) }
                }
            }
        }
    }

    private fun Project.wireFfm(jvm: KotlinJvmTarget) {
        val toolchains: JavaToolchainService = extensions.getByType<JavaToolchainService>()
        val launcher25: org.gradle.api.provider.Provider<JavaLauncher> =
            toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(FFM_TOOLCHAIN_JDK)) }
        val launcher21: org.gradle.api.provider.Provider<JavaLauncher> =
            toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(DESKTOP_JVM_TARGET.target.toInt())) }

        val main: KotlinJvmCompilation = jvm.compilations.getByName(KotlinCompilation.MAIN_COMPILATION_NAME)

        // Not `associateWith(main)`: an associated custom compilation compiles against the target's jvmJar, and the jvm jar
        // carries the ffm classes, which would be a task cycle. Main's classes and compile classpath are added by hand instead.
        // The ffm sources therefore see only the module's public API (the seam is public + @ToolkitInternalApi), never its `internal`.
        val ffm: KotlinJvmCompilation = jvm.compilations.create(FFM_COMPILATION)
        val ffmCheck: KotlinJvmCompilation = jvm.compilations.create(FFM_API_CHECK_COMPILATION) {
            // The very same source files: the ffm source directory is a root of this source set too, no copy.
            defaultSourceSet.kotlin.srcDir(ffm.defaultSourceSet.kotlin.srcDirs)
        }
        val ffmTest: KotlinJvmCompilation = jvm.compilations.create(FFM_TEST_COMPILATION)
        for (compilation: KotlinJvmCompilation in listOf(ffm, ffmCheck)) {
            compilation.compileDependencyFiles += main.output.classesDirs + main.compileDependencyFiles
        }
        ffmTest.compileDependencyFiles += ffm.output.classesDirs + main.output.classesDirs + main.compileDependencyFiles
        // The tests see the `internal` declarations of the ffm classes they test (the real-store suites and the fake-library tests).
        ffmTest.compileTaskProvider.configure {
            (this as org.jetbrains.kotlin.gradle.tasks.BaseKotlinCompile).friendPaths.from(ffm.output.classesDirs)
        }

        listOf(ffm, ffmCheck, ffmTest).forEach { compilation: KotlinJvmCompilation ->
            compilation.compileTaskProvider.configure {
                compilerOptions.jvmTarget.set(DESKTOP_JVM_TARGET)
                (this as KotlinJvmCompile).kotlinJavaToolchain.toolchain.use(launcher25)
            }
        }
        ffmCheck.compileTaskProvider.configure {
            // -Xjdk-release forbids a -jvm-target of its own, and it needs the target to match the release; the output is never used.
            compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget(FFM_API_RELEASE.toString()))
            compilerOptions.freeCompilerArgs.add("-Xjdk-release=$FFM_API_RELEASE")
        }
        // The seam is public + @ToolkitInternalApi so that another artifact can implement it; the FFM binding implements it.
        listOf(ffm, ffmCheck, ffmTest).forEach { compilation: KotlinJvmCompilation ->
            compilation.defaultSourceSet.languageSettings.optIn("io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi")
        }

        // The Java compilation Kotlin creates beside each compilation must agree with the Kotlin target (KGP validates it).
        val targetName: String = jvm.name.replaceFirstChar(Char::uppercaseChar)
        tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
            if (name.startsWith("compile$targetName") || name.contains("Ffm", ignoreCase = false)) {
                options.release.set(DESKTOP_JVM_TARGET.target.toInt())
            }
        }

        // One artifact: the ffm classes ride in the jvm jar. The check compilation is never packed, only built.
        tasks.withType<Jar>().matching { it.name == "${jvm.name}Jar" }.configureEach {
            from(ffm.output.allOutputs)
            dependsOn(ffmCheck.compileTaskProvider)
        }
        tasks.matching { it.name == "check" }.configureEach { dependsOn(ffmCheck.compileTaskProvider) }

        val javaHome21: String = launcher21.get().metadata.installationPath.asFile.absolutePath
        val javaHome25: String = launcher25.get().metadata.installationPath.asFile.absolutePath
        val secretStore: String = providers.gradleProperty("kmptoolkit.secretstore").getOrElse("")
        val secretStoreRequired: String = providers.gradleProperty("kmptoolkit.secretstore.required").getOrElse("false")

        tasks.withType<Test>().configureEach {
            systemProperty("kmptoolkit.test.javaHome21", javaHome21)
            systemProperty("kmptoolkit.test.javaHome25", javaHome25)
            systemProperty("kmptoolkit.secretstore", secretStore)
            systemProperty("kmptoolkit.secretstore.required", secretStoreRequired)
        }

        // The JDK 21 test run sees the ffm classes (a child JVM of a JDK 25 test, and the structure test, load them from here).
        val targetJar: org.gradle.api.tasks.TaskProvider<Jar> = tasks.named<Jar>("${jvm.name}Jar")
        tasks.matching { it.name == "${jvm.name}Test" }.withType<Test>().configureEach {
            classpath += ffm.output.allOutputs
            // The packaged jvm jar, for the structure test (what is in the artifact, and what references what).
            dependsOn(targetJar)
            systemProperty("kmptoolkit.test.targetJar", targetJar.get().archiveFile.get().asFile.path)
        }

        tasks.register<Test>(FFM_TEST_TASK) {
            group = "verification"
            description = "The tests that call java.lang.foreign (src/jvmFfmTest), on a JDK $FFM_TOOLCHAIN_JDK launcher."
            testClassesDirs = ffmTest.output.classesDirs
            classpath = ffmTest.runtimeDependencyFiles + ffmTest.output.allOutputs + ffm.output.allOutputs + main.output.allOutputs
            javaLauncher.set(launcher25)
            // A real-store verdict depends on the machine (the keychain, the user session), which no task input records.
            doNotTrackState("Real-store suites: the verdict depends on the machine, never reuse an earlier result")
        }
    }
}
