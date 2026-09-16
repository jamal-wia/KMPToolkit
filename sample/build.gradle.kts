plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.sample"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "io.github.jamal_wia.kmptoolkit.sample"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.compileSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

// The sample exists to prove the published artifacts actually resolve and work — see
// .claude/CLAUDE.md § 6.
//
// Two modes:
//   * default — project dependencies, so `./gradlew build` works on a fresh clone with nothing
//     published anywhere;
//   * `-PusePublishedArtifacts` — resolves the real thing from Maven Central through the BOM, AFTER a
//     release, to confirm that what consumers get is what was meant. Resolving by coordinate is the
//     only way to catch a broken POM, a missing variant, or a publication that omits a target — a
//     unit test cannot see any of those.
val usePublishedArtifacts: Boolean = providers.gradleProperty("usePublishedArtifacts").isPresent

dependencies {
    if (usePublishedArtifacts) {
        implementation(platform("io.github.jamal-wia:kmptoolkit-bom:${providers.gradleProperty("kmptoolkit.version").get()}"))
        implementation("io.github.jamal-wia:kmptoolkit-logging")
        implementation("io.github.jamal-wia:kmptoolkit-storage")
        implementation("io.github.jamal-wia:kmptoolkit-haptics")
    } else {
        implementation(project(":kmptoolkit-logging"))
        implementation(project(":kmptoolkit-storage"))
        implementation(project(":kmptoolkit-haptics"))
    }

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}