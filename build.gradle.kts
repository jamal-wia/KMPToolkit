// Top-level build file.
//
// Every plugin that a `kmptoolkit.*` convention plugin applies must appear here with
// `apply false`: build-logic declares them `compileOnly` (so two copies of AGP/KGP never land on
// one classpath), which compiles the conventions but does not put the plugins on the *consuming*
// build's classpath. This block is what does that. Omit one and the module fails at configuration
// time with "Plugin with id '...' not found".
plugins {
    // Gives the root project the `check` / `build` lifecycle, which `checkBuildLogic` below hangs on.
    base
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.sqldelight) apply false
}

// The unit tests of the build-logic convention code (today: the path-hygiene scanner). build-logic
// is an included build, so its `test` task is not part of any module's `check`; this root lifecycle
// hooks it into `./gradlew build` and `./gradlew check`.
tasks.register("checkBuildLogic") {
    group = "verification"
    description = "Runs the unit tests of the build-logic included build."
    dependsOn(gradle.includedBuild("build-logic").task(":test"))
}

tasks.named("check") { dependsOn("checkBuildLogic") }
