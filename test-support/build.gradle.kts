// Not published, and deliberately not named kmptoolkit-*: kmptoolkit-bom adds every kmptoolkit-*
// subproject to the BOM. Applies kmptoolkit.library only (no kmptoolkit.publish), and is used solely
// as a test dependency of other modules, so it never appears in a published POM.
plugins {
    id("kmptoolkit.library")
}

kotlin {
    android {
        namespace = "io.github.jamal_wia.kmptoolkit.testsupport"
    }

    iosArm64()
    iosSimulatorArm64()
    jvm()

    sourceSets {
        // `src/jvmShared` holds the java.io.File based code that Android host tests and desktop tests
        // share. It is compiled into both targets by adding it as a source directory of each, rather
        // than as an intermediate source set: the Android KMP library target does not join a custom
        // `group { withAndroidTarget() }` of the hierarchy template.
        val jvmShared: String = "src/jvmShared/kotlin"
        androidMain {
            kotlin.srcDir(jvmShared)
            dependencies {
                // The same JUnit 4 that kotlin("test") puts on every JVM test classpath; used for Assume.
                implementation(libs.junit)
            }
        }
        jvmMain {
            kotlin.srcDir(jvmShared)
            dependencies {
                implementation(libs.junit)
            }
        }
    }
}
