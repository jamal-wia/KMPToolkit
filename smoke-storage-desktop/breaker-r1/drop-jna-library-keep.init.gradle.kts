// Breaker r1 (group sto-smoke) negative control: kmptoolkit-storage-jna.pro is fed WITHOUT its one rule that keeps the
// JNA Library interfaces (method names are bound to C symbols by reflection). Simulates a single rule missing from the jar.
// The smoke MUST fail (exit != 0, no PASS line).
//   ./gradlew -I smoke-storage-desktop/breaker-r1/drop-jna-library-keep.init.gradle.kts :smoke-storage-desktop:smokeJna -Psmoke.probeOnly=true
gradle.beforeProject {
    if (path == ":smoke-storage-desktop:jna") {
        tasks.withType(Sync::class.java).configureEach {
            if (name == "collectConsumerProguardRules") {
                // A filter closure is not part of the up-to-date check: never reuse a previous output.
                outputs.upToDateWhen { false }
                filter { line: String -> if (line.contains("extends com.sun.jna.Library")) "# dropped by breaker r1" else line }
            }
        }
    }
}
