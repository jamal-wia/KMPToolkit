// Breaker round 3 (sto-smoke): does ONE run of the keychain check cover BOTH packaged runs when both smoke tasks are in one invocation
// (org.gradle.parallel=true in the repository)? Prints a monotonic stamp when each packaged run starts and ends and when the check starts,
// and lets the check run in a probe-only invocation too (it is a read of attributes only: `security find-generic-password -l <label>`).
// Probe only, touches no key:
//   ./gradlew -I smoke-storage-desktop/breaker-r3/finalizer-order.init.gradle.kts \
//       :smoke-storage-desktop:smokeFfm :smoke-storage-desktop:smokeJna -Psmoke.probeOnly=true
// Expected for a sound gate: `ORDER verify-start` comes after BOTH `ORDER run-end` lines.
gradle.projectsEvaluated {
    val parent: Project = rootProject.findProject(":smoke-storage-desktop") ?: return@projectsEvaluated
    for (variant: String in listOf("ffm", "jna")) {
        parent.project(variant).tasks.matching { it.name == "runReleaseDistributable" }.configureEach {
            doFirst { println("ORDER run-start $path ${System.nanoTime()}") }
            doLast { println("ORDER run-end $path ${System.nanoTime()}") }
        }
    }
    parent.tasks.named("verifySmokeLeftNoKeychainItem") {
        setOnlyIf { true }
        doFirst { println("ORDER verify-start $path ${System.nanoTime()}") }
    }
}
