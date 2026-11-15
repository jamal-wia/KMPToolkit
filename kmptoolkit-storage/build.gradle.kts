import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
    // Robolectric for androidHostTest: both Android stores need a real Context, a real
    // SharedPreferences file, and — for the encrypted one — a real AndroidKeyStore provider.
    id("kmptoolkit.androidtest")
    // The `ffm` compilation of the jvm target: the desktop key-store binding that calls the OS through java.lang.foreign.
    id("kmptoolkit.ffm")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Storage")
    pomDescription.set(
        "Key-value storage for Kotlin Multiplatform: one KeyValueStorage interface over " +
            "SharedPreferences, NSUserDefaults and, on desktop, a properties file, typed Int/Long/Boolean " +
            "accessors, a SecureKeyValueStorage backed by the Android Keystore and the iOS Keychain, " +
            "desktop secure storage whose key is held by the macOS Keychain, Windows DPAPI or the Linux " +
            "Secret Service (called through the JDK's Foreign Function and Memory API on JDK 22+, no " +
            "third-party dependency), an app-key encrypted store for desktop, and a DeviceIdProvider " +
            "for a stable per-install id. Every store is named by the StorageConfig the app passes, " +
            "which is also what the keystore alias, Keychain service or OS key item derives from, so " +
            "two features of one app never share a store. " +
            "Pick this module if shared code needs a handful of small values to survive a " +
            "process restart, with failures arriving as typed StorageError values rather than " +
            "platform exceptions."
    )
}

// @MustUseReturnValues (on KeyValueStorage and the helper files) cannot be declared at all while the compiler's
// return-value checker is disabled: "Ignorability-related annotations are experimental and cannot be used with
// -Xreturn-value-checker in disabled state". `check` reports only the declarations that carry the annotation, so the
// module's own sources are warned about an ignored StorageResult. Tests are left out on purpose: they drop results
// deliberately (a failing put whose effect is the thing under test), and the annotation reaches consumers through the
// published metadata, so no consumer needs the flag.
tasks.withType<KotlinCompilationTask<*>>().configureEach {
    if (!name.contains("Test")) {
        compilerOptions.freeCompilerArgs.add("-Xreturn-value-checker=check")
    }
}

kotlin {
    android {
        namespace = "io.github.jamal_wia.kmptoolkit.storage"
    }

    iosArm64()
    iosSimulatorArm64()

    // A desktop target, under the exception in docs/01-architecture.md § "Desktop targets".
    // KeyValueStorage is the type a consumer's shared settings and repository code takes as a
    // parameter; an app that shares that code with desktop cannot compile it for desktop at all if
    // the interface exists on only two of its three targets. Unlike the Compose no-op targets, the
    // jvm half is real: a properties-file store the app points at its own directory. There is no
    // secure store on desktop — see `docs/kmptoolkit-storage/05-platform-notes.md`.
    jvm()

    sourceSets {
        commonMain.dependencies {
            // implementation, not api: the public surface is suspend functions and this module's
            // own types. Coroutines are how the backends move their blocking I/O off the caller's
            // thread and how the get-or-create paths — the Android key, the device id — stay
            // atomic; no coroutines type appears in a public signature.
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmMain.dependencies {
            // The desktop store's atomic write and its per-file lock are kmptoolkit-files' writeAtomically and
            // KeyedMutex; Android and iOS keep their own stores. implementation: no files type is public here.
            implementation(project(":kmptoolkit-files"))
            // @ToolkitInternalApi marks the internal seam of the desktop secure store (internal/desktop), which
            // kmptoolkit-storage-jna implements across a module boundary. implementation: no core type is public here.
            implementation(project(":kmptoolkit-core"))
        }
        // The seam is public + @ToolkitInternalApi so another artifact can implement it; this module uses it freely.
        jvmMain { languageSettings { optIn("io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi") } }
        jvmTest { languageSettings { optIn("io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi") } }
        // The tests that call java.lang.foreign themselves (src/jvmFfmTest, run by jvmTestFfm on a JDK 25 launcher).
        getByName("jvmFfmTest").dependencies {
            // This is not a KGP test compilation, so kotlin-test is not mapped to its JUnit variant on its own.
            implementation(kotlin("test-junit"))
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.core)
            implementation(project(":kmptoolkit-core"))
            implementation(project(":kmptoolkit-files"))
            implementation(project(":test-support"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            // Not published; host path rules and the unwritable-directory helper for the file tests.
            implementation(project(":test-support"))
        }
    }
}
