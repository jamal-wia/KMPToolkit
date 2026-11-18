// Breaker round 2 (sto-smoke), finding M1: the shape of the recommended fix, as an init script. It attaches the keychain check
// `verifySmokeLeftNoKeychainItem` to the packaged run itself (`runReleaseDistributable` of both variants), so it also runs when the
// packaged app FAILS. Today it finalizes only `smokeFfm` / `smokeJna`, which never execute when their dependency, the packaged run,
// fails, so Gradle skips the finalizer exactly on the failure path. Use with a negative control that fails in the app BEFORE any key
// store call, for example:
//   ./gradlew -I smoke-storage-desktop/breaker-r2/finalize-on-run-failure.init.gradle.kts :smoke-storage-desktop:smokeFfm \
//       -Psmoke.control.noNativeAccessFlag=true -x :smoke-storage-desktop:ffm:test
// Without this script the log has no `verifySmokeLeftNoKeychainItem` task; with it the task runs after the failed run.
gradle.projectsEvaluated {
    // Init scripts also reach included builds (build-logic): act only where the smoke project exists.
    val parent: Project = rootProject.findProject(":smoke-storage-desktop") ?: return@projectsEvaluated
    for (variant: String in listOf("ffm", "jna")) {
        parent.project(variant).tasks.matching { it.name == "runReleaseDistributable" }.configureEach {
            finalizedBy(parent.tasks.named("verifySmokeLeftNoKeychainItem"))
        }
    }
}
