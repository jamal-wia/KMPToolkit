// Breaker r1 (group sto-smoke) negative control: the jna variant's ProGuard is fed every consumer rule file EXCEPT
// kmptoolkit-storage-jna.pro, as if that artifact shipped no rules. The smoke MUST fail (exit != 0, no PASS line).
//   ./gradlew -I smoke-storage-desktop/breaker-r1/drop-jna-rules.init.gradle.kts :smoke-storage-desktop:smokeJna -Psmoke.probeOnly=true
gradle.beforeProject {
    if (path == ":smoke-storage-desktop:jna") {
        tasks.withType(Sync::class.java).configureEach {
            if (name == "collectConsumerProguardRules") {
                // The copy spec change alone does not always re-run the Sync (observed): never reuse a previous output.
                outputs.upToDateWhen { false }
                exclude("**/kmptoolkit-storage-jna.pro")
            }
        }
    }
}
