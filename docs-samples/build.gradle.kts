// Not published, and deliberately not named kmptoolkit-*: kmptoolkit-bom adds every kmptoolkit-* subproject to the BOM.
// The consumer-side compile check of the documentation samples (plan 6.7 (a), docs/kmptoolkit-storage/06-testing.md): every
// Kotlin snippet of docs/kmptoolkit-storage that does not need a platform API is compiled here as a consumer of
// kmptoolkit-storage, through an ordinary project dependency, so Kotlin's cross-module rules apply to it (no smart cast on a
// public property of another module) and the return-value checker is on (a dropped StorageResult fails the build).
// kmptoolkit-storage compiles the same files into its own jvmTest (src/jvmMain/kotlin, see its build file), where the
// internal fake binding runs them (part (b)). The Android and iOS snippets are checked in the storage module's own
// androidHostTest and iosTest, because a platform API does not exist in a JVM module.
//
// Applies the Kotlin Multiplatform plugin directly, not kmptoolkit.library: no Android target, no explicit-API surface and no
// ABI dump to maintain for a module nobody depends on.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            allWarningsAsErrors.set(true)
            freeCompilerArgs.add("-Xreturn-value-checker=check")
        }
    }

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":kmptoolkit-storage"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
