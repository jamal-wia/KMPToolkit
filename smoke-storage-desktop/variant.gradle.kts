// The build script of BOTH variant projects (:smoke-storage-desktop:ffm and :smoke-storage-desktop:jna); settings.gradle.kts
// sets it as their build file, so the variant is the project name. See build.gradle.kts of the parent for the overview.
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.compose.desktop.application.tasks.AbstractCheckNativeDistributionRuntime

plugins {
    kotlin("jvm")
    alias(libs.plugins.compose.multiplatform)
    // Required by the Compose Multiplatform plugin even for an app with no composable: it refuses to configure without it.
    alias(libs.plugins.kotlin.compose)
}

val variant: String = project.name
val isFfm: Boolean = when (variant) {
    "ffm" -> true
    "jna" -> false
    else -> error("unknown smoke variant '$variant'")
}

/** The JDK the distribution is packaged with: its jmods feed jlink, its class library is what ProGuard reads. */
val packagingJdk: Int = if (isFfm) 25 else 21

kotlin {
    // The host reaches the library's binding seam (public, @ToolkitInternalApi) to remove the key item its secure round trip creates.
    compilerOptions { optIn.add("io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi") }
    // The host is compiled at the library's own bytecode level; the packaging JDK above is what the app runs on.
    jvmToolchain(21)
    sourceSets.named("main") { kotlin.srcDir(layout.projectDirectory.dir("../src/main/kotlin")) }
    // The module's own checks (verdict rules, key-item cleanup), shared by both variants like the sources they test.
    sourceSets.named("test") { kotlin.srcDir(layout.projectDirectory.dir("../src/test/kotlin")) }
}

dependencies {
    implementation(project(":kmptoolkit-storage"))
    // @ToolkitInternalApi, the opt-in of the binding seam; kmptoolkit-storage does not expose it (implementation there).
    implementation(project(":kmptoolkit-core"))
    implementation(libs.kotlinx.coroutines.core)
    // The Compose compiler plugin the Compose Desktop plugin demands fails the build without the runtime on the class path, though
    // the host has no composable. The only Compose artifact here; the app has no UI.
    implementation(libs.compose.runtime)
    // JDK 21 cannot use the FFM binding of kmptoolkit-storage, so the jna variant adds the optional bridge artifact (its whole opt-in).
    // The ffm variant must NOT carry it: the host asserts that.
    if (!isFfm) runtimeOnly(project(":kmptoolkit-storage-jna"))
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach { useJUnit() }

/**
 * What a consumer's shrinker is given by the library artifacts: every .pro file under META-INF/proguard of every runtime jar, copied
 * unchanged, the way R8 reads them on Android. ProGuard does not discover them by itself, so a desktop consumer feeds them in
 * by hand (the library's .pro files say so). The smoke host adds no rule of its own for the libraries: whatever the smoke
 * needs beyond these files is a defect of the artifact, not something this build repairs.
 */
val consumerRules: TaskProvider<Sync> = tasks.register<Sync>("collectConsumerProguardRules") {
    val runtimeClasspath: Configuration = configurations.getByName("runtimeClasspath")
    dependsOn(runtimeClasspath)
    // Two jars shipping a file of the same name would silently drop one rule file: fail instead.
    duplicatesStrategy = DuplicatesStrategy.FAIL
    // Never up to date: the copy spec is only partly part of Gradle's up-to-date check (a filter closure is not, and a changed
    // exclude was seen to be missed), and the rules handed to ProGuard are what this release gate stands on, so they must always
    // mirror the jars as they are now. The copy costs a few small files.
    outputs.upToDateWhen { false }
    from({ runtimeClasspath.files.filter { it.name.endsWith(".jar") }.map { zipTree(it).matching { include("META-INF/proguard/*.pro") } } })
    eachFile { path = name }
    includeEmptyDirs = false
    into(layout.buildDirectory.dir("smoke/consumer-proguard"))
}

/** A negative control of the smoke itself (see the two uses below): never set in a real run. */
fun control(name: String): Boolean = providers.gradleProperty("smoke.control.$name").orNull == "true"

val resultFile: Provider<RegularFile> = layout.buildDirectory.file("smoke/result.txt")

compose.desktop {
    application {
        mainClass = "io.github.jamal_wia.kmptoolkit.smoke.storage.MainKt"
        args(
            "--variant=$variant",
            "--result-file=${resultFile.get().asFile.absolutePath}",
        )
        if (providers.gradleProperty("kmptoolkit.secretstore.required").orNull == "true") args("--secure-required")
        // -Psmoke.probeOnly=true: run the key-store probe (no prompt, no key read or written) but no data round trip.
        if (providers.gradleProperty("smoke.probeOnly").orNull == "true") args("--probe-only")
        // Plan 6.7 P: the flag that keeps the JDK's native-access warning (JEP 454) and a future deny from reaching the app.
        // The JNA variant does not need it on JDK 21 (JNA is not restricted there).
        // -Psmoke.control.noNativeAccessFlag=true leaves it out: a NEGATIVE CONTROL, the run must then FAIL (proves the smoke sees the flag).
        if (isFfm && !control("noNativeAccessFlag")) jvmArgs("--enable-native-access=ALL-UNNAMED")

        nativeDistributions {
            packageName = "kmptoolkit-smoke-storage-$variant"
            packageVersion = "1.0.0"
            // Not derived from the main class: its package has underscores, which a macOS bundle identifier does not allow.
            macOS { bundleID = "io.github.jamal-wia.kmptoolkit.smoke.storage.$variant" }
        }

        buildTypes.release.proguard {
            isEnabled.set(true)
            // A release build as consumers ship it: renaming is what breaks a binding that is found by name.
            obfuscate.set(true)
            // The collected files themselves (ProGuard takes files, not the directory the task fills).
            // -Psmoke.control.noLibraryRules=true feeds none of them: a NEGATIVE CONTROL, the build or the run must then FAIL.
            if (!control("noLibraryRules")) {
                configurationFiles.from(fileTree(layout.buildDirectory.dir("smoke/consumer-proguard")).builtBy(consumerRules))
            }
        }
    }
}

// The packaging JDK is resolved only when a task of this project is in the graph: resolving a toolchain at configuration
// would make every Gradle invocation of the repository need JDK 25 and JDK 21 installed (the ffm build logic is lazy for the same reason).
val packagingJavaHome: Provider<String> = providers.gradleProperty("smoke.javaHome$packagingJdk").orElse(
    javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(packagingJdk)) }
        .map { it.metadata.installationPath.asFile.absolutePath },
)
gradle.taskGraph.whenReady {
    if (allTasks.any { it.project === project }) compose.desktop.application.javaHome = packagingJavaHome.get()
}

val verifyPackagingJdk: TaskProvider<Task> = tasks.register("verifyPackagingJdk") {
    description = "Fails with a readable message when the packaging JDK cannot build a runtime image."
    val home: Provider<String> = packagingJavaHome
    doLast {
        val dir: File = File(home.get())
        val release: String = File(dir, "release").takeIf { it.isFile }?.readText().orEmpty()
        val problems: List<String> = buildList {
            if (!File(dir, "jmods").isDirectory) add("it has no jmods directory (a JDK without jmods cannot be jlinked)")
            if (dir.resolve("bin").listFiles { f: File -> f.name == "jpackage" || f.name == "jpackage.exe" }.isNullOrEmpty()) add("it has no jpackage")
            if (!release.contains("JAVA_VERSION=\"$packagingJdk")) add("its release file does not say JAVA_VERSION=\"$packagingJdk...\"")
        }
        check(problems.isEmpty()) {
            "smoke variant $variant needs a full JDK $packagingJdk but $dir is not one: ${problems.joinToString("; ")}. " +
                "Pass -Psmoke.javaHome$packagingJdk=<a full JDK $packagingJdk>."
        }
        logger.lifecycle("smoke $variant: packaging with JDK $packagingJdk at $dir")
    }
}

// The Compose tasks are registered by the plugin after evaluation, so these are lazy matches.
tasks.matching { it.name == "createReleaseDistributable" || it.name == "checkRuntime" }.configureEach { dependsOn(verifyPackagingJdk) }
// The plugin refuses a Homebrew JDK on macOS (compose-multiplatform issue 3107) unless compose.desktop.packaging.checkJdkVendor=false
// is a Gradle property, and the only JDK 25 on the maintainer's Mac is Homebrew's. The refusal is switched off for this smoke only,
// on the task (the repository's gradle.properties stays untouched); a packaging problem that really comes from the vendor shows up
// as a jpackage/jlink failure in the smoke's log, which is the point of running it.
// Set when the graph is ready, not with configureEach: the plugin sets the property from the Gradle property when it creates the
// task, after this script has run, so an earlier action would be overwritten.
gradle.taskGraph.whenReady {
    allTasks.filterIsInstance<AbstractCheckNativeDistributionRuntime>().filter { it.project === project }.forEach { it.checkJdkVendor.set(false) }
}
tasks.matching { it.name == "runReleaseDistributable" }.configureEach {
    // A stale verdict of an earlier run must never stand in for this run.
    doFirst { delete(resultFile) }
}
