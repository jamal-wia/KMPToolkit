plugins {
    `java-platform`
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit BOM")
    pomDescription.set(
        "Bill of Materials for the KMPToolkit suite. Import it with " +
            "implementation(platform(\"io.github.jamal-wia:kmptoolkit-bom:<version>\")) and then " +
            "declare any kmptoolkit-* artifact without a version — the BOM keeps them aligned so " +
            "a classpath cannot end up with two modules from different releases. Pick this if you " +
            "use more than one KMPToolkit module."
    )
}

// The constraint list is derived from the projects that actually exist, not maintained by hand.
// A hand-written list is one edit away from disagreeing with settings.gradle.kts, and the symptom
// — a consumer resolving one artifact at a stale version — appears at their build, not ours.
//
// Filtering by name needs no project evaluation, so this stays free of configuration-order
// coupling. `-testing` artifacts are included deliberately: a test fixture compiled against a
// different version of its own production module is exactly the mismatch a BOM exists to prevent.
dependencies {
    constraints {
        rootProject.subprojects
            .map { it.name }
            .filter { it.startsWith("kmptoolkit-") && it != project.name }
            .sorted()
            .forEach { module -> api("${project.group}:$module:${project.version}") }
    }
}
