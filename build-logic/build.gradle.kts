plugins {
    `kotlin-dsl`
}

group = "io.github.jamal_wia.kmptoolkit.buildlogic"

dependencies {
    // compileOnly, not implementation: these plugins are applied to the *consuming* project, which
    // brings its own copy via the root build's classpath. Leaking them as `implementation` would
    // put two copies of AGP/KGP on the same classpath and fail with a duplicate-class error.
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.kotlin.serialization.gradlePlugin)
    compileOnly(libs.compose.gradlePlugin)
    compileOnly(libs.composeCompiler.gradlePlugin)
    compileOnly(libs.mavenPublish.gradlePlugin)
}

// Plugin ids are `kmptoolkit.<capability>`, so a module's plugins block states what the module
// *is* (a published library, a Compose UI module, an Android-tested module) instead of restating
// target lists, SDK levels, and a ~150-line publishing block per module.
gradlePlugin {
    plugins {
        register("library") {
            id = "kmptoolkit.library"
            implementationClass = "io.github.jamal_wia.kmptoolkit.buildlogic.LibraryConventionPlugin"
        }
        register("compose") {
            id = "kmptoolkit.compose"
            implementationClass = "io.github.jamal_wia.kmptoolkit.buildlogic.ComposeConventionPlugin"
        }
        register("publish") {
            id = "kmptoolkit.publish"
            implementationClass = "io.github.jamal_wia.kmptoolkit.buildlogic.PublishConventionPlugin"
        }
        register("androidtest") {
            id = "kmptoolkit.androidtest"
            implementationClass = "io.github.jamal_wia.kmptoolkit.buildlogic.AndroidTestConventionPlugin"
        }
    }
}
