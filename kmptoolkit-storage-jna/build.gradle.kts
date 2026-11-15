plugins {
    // JVM only: a JNA binding to the operating system's key stores exists only where the JVM does. See build-logic's
    // JvmLibraryConventionPlugin.
    id("kmptoolkit.library.jvm")
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Storage JNA")
    pomDescription.set(
        "Optional JDK 21 bridge for the desktop secure storage of kmptoolkit-storage: it implements the " +
            "module's internal native key-store seam (macOS Security.framework, Windows DPAPI, the Linux " +
            "Secret Service through libsecret) with JNA, for applications that cannot run on JDK 22 or " +
            "newer, where kmptoolkit-storage uses the Foreign Function & Memory API on its own. Adding " +
            "the dependency is the whole opt-in; it has no public API and ships its own ProGuard rules. " +
            "It is a bridge, to be deprecated when the suite's desktop baseline reaches JDK 22."
    )
}

kotlin {
    sourceSets {
        jvmMain.dependencies {
            // api: this artifact only makes sense next to the module whose seam it implements, and the consumer's
            // classpath must carry the same lockstep version of it.
            api(project(":kmptoolkit-storage"))
            // @ToolkitInternalApi and the seam (internal.desktop) live in these two modules. implementation: no core
            // type is public here.
            implementation(project(":kmptoolkit-core"))
            // implementation: no JNA type appears in this module's API, so only the consumer's runtime sees it.
            // Core only; jna-platform is never used.
            implementation(libs.jna.jpms)
        }
        // The seam is public + @ToolkitInternalApi so this module can implement it across the module boundary.
        jvmMain { languageSettings { optIn("io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi") } }
        jvmTest { languageSettings { optIn("io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi") } }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            // Not published; the open-descriptor count of the leak loops of the real-store suites.
            implementation(project(":test-support"))
        }
    }
}

// The real-store suites are opt-in: -Pkmptoolkit.secretstore=macos|windows|linux enables the one for the operating
// system the build runs on, and -Pkmptoolkit.secretstore.required=true turns a skip into a failure, so a broken key
// store cannot pass as a green run (plan 3.9, 6.4).
tasks.withType<Test>().configureEach {
    systemProperty(
        "kmptoolkit.secretstore",
        providers.gradleProperty("kmptoolkit.secretstore").getOrElse(""),
    )
    systemProperty(
        "kmptoolkit.secretstore.required",
        providers.gradleProperty("kmptoolkit.secretstore.required").getOrElse("false"),
    )
    // Linux suite only: which environment the run was prepared as (unlocked, nobus, noservice); see the suite's KDoc.
    systemProperty(
        "kmptoolkit.secretstore.scenario",
        providers.gradleProperty("kmptoolkit.secretstore.scenario").getOrElse("unlocked"),
    )
}
