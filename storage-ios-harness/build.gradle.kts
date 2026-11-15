// Not published, and deliberately not named kmptoolkit-*: kmptoolkit-bom adds every kmptoolkit-* subproject to
// the BOM. The Kotlin half of the iOS secure-storage host app (see ios-host/README.md): a static framework,
// `StorageHarness`, that runs real Keychain round trips against the library's public API, which a Kotlin/Native
// test binary cannot do (it has no usable Keychain). Built only for the iOS simulator and device targets; it has
// no tests of its own, because its whole job is to be run by a person.
//
// Applies the Kotlin Multiplatform plugin directly, not kmptoolkit.library: there is no Android target, no
// explicit-API surface and no ABI dump to maintain for a module nobody depends on.
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "StorageHarness"
            isStatic = true
        }
        target.compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions.optIn.addAll(
                    "kotlinx.cinterop.ExperimentalForeignApi",
                    "kotlinx.cinterop.BetaInteropApi",
                )
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":kmptoolkit-storage"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
