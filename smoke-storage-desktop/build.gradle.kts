import java.util.concurrent.TimeUnit

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

// A secure run must leave NOTHING in the login keychain (the host app removes its own key item, see Cleanup.kt). This check does not
// trust that: after every macOS run that was not probe-only it asks `security` (a read of attributes, no secret) for an item with the
// smoke's label and fails the build if one is there. The label must equal ITEM_LABEL in Main.kt.
val keychainLabel: String = "KMPToolkit packaging smoke (safe to delete)"
// The exit status `security` gives for "The specified item could not be found in the keychain".
val ITEM_NOT_FOUND_EXIT: Int = 44
val probeOnly: Boolean = providers.gradleProperty("smoke.probeOnly").orNull == "true"
val verifyNoKeychainItem: TaskProvider<Task> = tasks.register("verifySmokeLeftNoKeychainItem") {
    group = "verification"
    description = "Fails if the smoke's secure run left an item labelled '$keychainLabel' in the login keychain (macOS, not probe-only)."
    onlyIf { System.getProperty("os.name").orEmpty().startsWith("Mac") && !probeOnly }
    doLast {
        val process: Process = ProcessBuilder("security", "find-generic-password", "-l", keychainLabel).redirectErrorStream(true).start()
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("could not check the login keychain: `security find-generic-password` did not answer within 30 s (a locked keychain waiting for a dialog?)")
        }
        val output: String = process.inputStream.bufferedReader().readText().trim()
        when (val code: Int = process.exitValue()) {
            0 -> error("the smoke left an item labelled '$keychainLabel' in the login keychain. Remove it with: security delete-generic-password -l '$keychainLabel'\n$output")
            ITEM_NOT_FOUND_EXIT -> logger.lifecycle("keychain check: no item labelled '$keychainLabel' is left in the login keychain")
            else -> error("could not check the login keychain: `security find-generic-password` exited with $code: $output")
        }
    }
}

for ((suffix: String, variant: String) in listOf("Ffm" to "ffm", "Jna" to "jna")) {
    tasks.register("smoke$suffix") {
        group = "verification"
        description = "Builds the $variant release distribution of the storage packaging smoke (ProGuard on), runs it and prints its verdict."
        // The module's own checks (the verdict rules and the key-item cleanup, against a fake binding: no key store is touched) come
        // first, so a wrong verdict rule fails the gate before it can judge a run.
        dependsOn(":smoke-storage-desktop:$variant:test")
        dependsOn(":smoke-storage-desktop:$variant:runReleaseDistributable")
        finalizedBy(verifyNoKeychainItem)
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
