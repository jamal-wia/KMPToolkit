// Not published, never in the BOM (the BOM lists only projects named kmptoolkit-*; `kmptoolkit.publish` is not applied), and
// not a library: the host app of the packaging smoke of the desktop storage (storage plan 6.7 P).
//
// Two variants, one subproject each (`ffm`, `jna`), both built from the shared sources in src/main and the shared build
// script variant.gradle.kts (settings.gradle.kts points both projects at it): a Compose Desktop application has exactly one
// configuration per Gradle project, and the two variants differ in the packaging JDK, the artifacts and the launcher flags.
//
//   ./gradlew :smoke-storage-desktop:smokeFfm   JDK 25 packaging, the FFM binding, --enable-native-access=ALL-UNNAMED
//   ./gradlew :smoke-storage-desktop:smokeJna   JDK 21 packaging plus kmptoolkit-storage-jna
//
// Each task builds the release distribution (jlink runtime image, ProGuard with obfuscation) and RUNS it, then prints the
// run's verdict. The verdict says PASS, PASS-WITH-SKIP or FAIL; a skip (no usable key store in this session) is a pass of the
// plain round trip only. -Pkmptoolkit.secretstore.required=true turns the skip into a failure, like the real-store suites.
// -Psmoke.probeOnly=true runs the key-store probe (no prompt, no key read or written) instead of the secure round trip.
// -Psmoke.control.noLibraryRules=true and -Psmoke.control.noNativeAccessFlag=true are negative controls: each run must FAIL.
// Packaging JDKs come from the Gradle toolchains; -Psmoke.javaHome25=<dir> / -Psmoke.javaHome21=<dir> override them.
// Run on macOS (plan 6.7 P: Mac); the Gradle part (compile, ProGuard, jlink, jpackage) works on any OS with a full JDK.
for ((suffix: String, variant: String) in listOf("Ffm" to "ffm", "Jna" to "jna")) {
    tasks.register("smoke$suffix") {
        group = "verification"
        description = "Builds the $variant release distribution of the storage packaging smoke (ProGuard on), runs it and prints its verdict."
        dependsOn(":smoke-storage-desktop:$variant:runReleaseDistributable")
        val resultFile: RegularFile = layout.projectDirectory.file("$variant/build/smoke/result.txt")
        // Not an output of runReleaseDistributable, so the verdict is read here: a run that never reached main() leaves no file.
        doLast {
            val file: File = resultFile.asFile
            check(file.isFile) { "$variant: the distribution ran but wrote no verdict to $file (main() was not reached)" }
            val text: String = file.readText()
            logger.lifecycle("---- smoke $variant ----\n$text")
            check(text.lineSequence().any { it.startsWith("SMOKE-RESULT: PASS") }) { "$variant: no PASS verdict in $file" }
        }
    }
}
