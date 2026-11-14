plugins {
    // JVM only: JavaFX Media is a desktop runtime. See build-logic's JvmLibraryConventionPlugin.
    id("kmptoolkit.library.jvm")
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Video Player — JavaFX engine")
    pomDescription.set(
        "A desktop (JVM) playback engine for kmptoolkit-video-player built on JavaFX Media: " +
            "createJavaFxVideoPlayer() returns the same VideoPlayer as on Android and iOS, and its " +
            "frames render through kmptoolkit-video-player-compose. OpenJFX is licensed GPL-2.0 " +
            "with the Classpath Exception and is a compileOnly dependency — the app adds the " +
            "OpenJFX runtime jars for each OS it ships to. The alternative desktop engine is " +
            "kmptoolkit-video-player-vlcj."
    )
}

/**
 * OpenJFX publishes its classes only in OS-specific jars (`-mac`, `-mac-aarch64`, `-linux`,
 * `-linux-aarch64`, `-win`) selected by a Maven profile Gradle cannot evaluate, so the
 * classifier-less coordinate resolves to an empty jar. The API is identical in every one of them,
 * so this module compiles against the build host's jars — as compileOnly, which never reaches the
 * published POM. What runs is whatever the consuming app puts on its classpath for its own target
 * OS; see docs/kmptoolkit-video-player-javafx/02-getting-started.md.
 */
val hostJavaFxClassifier: String = run {
    val os: String = System.getProperty("os.name").lowercase()
    val arm: Boolean = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
    when {
        os.contains("mac") -> if (arm) "mac-aarch64" else "mac"
        os.contains("win") -> "win"
        else -> if (arm) "linux-aarch64" else "linux"
    }
}

fun openJfx(library: Provider<MinimalExternalModuleDependency>): String {
    val dependency: MinimalExternalModuleDependency = library.get()
    return "${dependency.module}:${dependency.versionConstraint.requiredVersion}:$hostJavaFxClassifier"
}

kotlin {
    // No Android, no iOS: this artifact exists only to put a desktop engine behind the core
    // module's jvm target. Its licence (GPL-2 + Classpath Exception) therefore reaches only an app
    // that adds this artifact, never one that uses the core module alone.
    sourceSets {
        jvmMain.dependencies {
            // api: createJavaFxVideoPlayer() returns VideoPlayer, and the engine implements the
            // core module's VideoPlaybackEngine and VideoFrameSource.
            api(project(":kmptoolkit-video-player"))
            implementation(libs.kotlinx.coroutines.core)
            // Not transitive: the POMs name a `${javafx.platform}` classifier that only a Maven OS
            // profile fills in. The three jars below are the whole of what this module touches.
            compileOnly(openJfx(libs.openjfx.base)) { isTransitive = false }
            compileOnly(openJfx(libs.openjfx.graphics)) { isTransitive = false }
            compileOnly(openJfx(libs.openjfx.media)) { isTransitive = false }
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            // Not published; descriptor counting that also answers "cannot tell" on Windows.
            implementation(project(":test-support"))
            implementation(openJfx(libs.openjfx.base)) { isTransitive = false }
            implementation(openJfx(libs.openjfx.graphics)) { isTransitive = false }
            implementation(openJfx(libs.openjfx.media)) { isTransitive = false }
        }
    }
}

// `-Pjavafx.required=true` turns the real-engine tests' skip (no display, no OpenJFX) into a
// failure; see assumeJavaFxMedia() in the tests.
tasks.withType<Test>().configureEach {
    // `-Pkmptoolkit.test.hazards=true` enables the opt-in native hazard probes (JavaFxNativeHazardCharacterizationTest);
    // without it the class is skipped, so the default gate never plays them. Like every other `-Pkmptoolkit.test.*`
    // knob (trial counts, seeds, soak lengths) it is forwarded to the test JVM by the kmptoolkit.library convention
    // plugin (forwardTestKnobs) and printed once per test task; nothing is needed here.
    // OpenJFX 27 is compiled with --release 25 and cannot load on the build's own, older JDK.
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    systemProperty(
        "kmptoolkit.javafx.required",
        providers.gradleProperty("javafx.required").getOrElse("false"),
    )
    // A native crash inside OpenJFX's macOS media backend (CoreVideo display link, see
    // docs/kmptoolkit-video-player-javafx/05-platform-notes.md, "Known issue") must end the worker without
    // the macOS crash dialog: OnError runs after hs_err is written and before abort(), and SIGKILL is
    // not reported by ReportCrash. The hs_err file goes to build/hs_err, not the module directory.
    val hsErrDir: String = File(layout.buildDirectory.get().asFile, "hs_err").absolutePath
    systemProperty("kmptoolkit.test.hsErrDir", hsErrDir)
    // A private temporary directory per task: the tests that count files in java.io.tmpdir (temp files,
    // soak) must not see the files of other builds running on the same machine at the same time.
    val tmpDir: String = File(layout.buildDirectory.get().asFile, "tmp/${name}").absolutePath
    jvmArgs("-Djava.io.tmpdir=$tmpDir")
    // HotSpot falls back to the working directory when the ErrorFile directory does not exist.
    doFirst {
        File(hsErrDir).mkdirs()
        File(tmpDir).mkdirs()
    }
    jvmArgs(
        "-XX:OnError=kill -9 %p",
        "-XX:ErrorFile=" + File(hsErrDir, "hs_err_pid%p.log").path,
    )
}

// Test tiers (docs/kmptoolkit-video-player-javafx/06-testing.md): `jvmTest` is the default gate and runs everything
// except the real-media soak classes below and the opt-in hazard probes; `jvmRealMediaTest` runs exactly those.
// Both lists are explicit class names, never a pattern, so a new class cannot vanish from the gate by accident:
// it is in `jvmTest` until somebody writes its name into the soak list.
val testPackage: String = "io.github.jamal_wia.kmptoolkit.video.player.javafx"

// Real-media sequencing classes. Each one has a deterministic twin in the `model` package (virtual time, no media)
// that asserts the same contract exactly, so the real class is a soak, not a gate. The evidence per class (twin,
// family mutation, alone failure rate) is in the J15 report; a class whose evidence is incomplete stays in the gate.
val realMediaSoakClasses: List<String> = listOf(
    "JavaFxFxThreadCallerR7AdversarialTest",
    "JavaFxFxThreadNudgeR8FixerTest",
    "JavaFxLoopSpeedR5AdversarialTest",
    "JavaFxOperationSequenceFuzzR6AdversarialTest",
    "JavaFxRateGivingR8AdversarialTest",
    "JavaFxResumeAtSpeedR5AdversarialTest",
    "JavaFxResumeAtWrapD2Boss1B6RealMediaTest",
    "JavaFxResumeKeepsPositionR10AdversarialTest",
    "JavaFxResumeSettlesAtSpeedR5FixerTest",
    "JavaFxSeekAtSpeedFreezeR6AdversarialTest",
    "JavaFxSeekAtTheEndR5AdversarialTest",
    "JavaFxSpeedAfterSeekFreezeAdversarialTest",
    "JavaFxSpeedInReadyThroughCoreR6AdversarialTest",
    "JavaFxSpeedThroughCoreR6AdversarialTest",
    "JavaFxStaleReadUnderLoadR9AdversarialTest",
).map { "$testPackage.$it" }

// Statistical probes of the native player (40 rounds each). They test the OS against the model's assumptions, not
// this code, and skip unless `-Pkmptoolkit.test.hazards=true`. Never in `jvmTest`; `jvmRealMediaTest` can run them.
val hazardProbeClasses: List<String> = listOf(
    "JavaFxNativeHazardCharacterizationTest",
).map { "$testPackage.$it" }

/**
 * Fails the task before it starts when a name of [classNames] has no compiled class: a stale or misspelled name
 * would otherwise exclude nothing (jvmTest) or select nothing (jvmRealMediaTest) without a word, and Gradle's own
 * `isFailOnNoMatchingTests` only fires when no filter matches at all.
 */
fun Test.failOnStaleClassNames(classNames: List<String>) {
    val classDirs: FileCollection = testClassesDirs
    val taskPath: String = path
    doFirst {
        val stale: List<String> = classNames.filter { name: String ->
            val relative: String = name.replace('.', '/') + ".class"
            classDirs.files.none { dir: File -> File(dir, relative).isFile }
        }
        if (stale.isNotEmpty()) {
            throw GradleException("$taskPath: no compiled test class for $stale: fix the lists in the build file")
        }
    }
}

tasks.named<Test>("jvmTest") {
    description = "The default gate: every JVM test except the real-media soak classes and the hazard probes."
    filter {
        (realMediaSoakClasses + hazardProbeClasses).forEach { name: String -> excludeTestsMatching(name) }
    }
    failOnStaleClassNames(realMediaSoakClasses + hazardProbeClasses)
}

// Same classes and classpath as jvmTest; the JVM settings and the forwarded -P properties come from the
// `withType<Test>` blocks above and from the kmptoolkit.library.jvm convention. One fork, never wired into `check`:
// run it before a release and after a change to FxPlayback, JavaFxNativePlayer, FxThread or FxQueue.
tasks.register<Test>("jvmRealMediaTest") {
    group = "verification"
    description = "Real-media soak: the sequencing classes that have a deterministic twin, plus the opt-in hazard probes."
    val gate: Test = tasks.named<Test>("jvmTest").get()
    testClassesDirs = gate.testClassesDirs
    classpath = gate.classpath
    maxParallelForks = 1
    // Its verdict depends on the machine (the OS media stack), which no task input records: a cached or up-to-date result would stand in for a run.
    doNotTrackState("Real-media soak: its verdict depends on the machine, never reuse an earlier result")
    filter {
        (realMediaSoakClasses + hazardProbeClasses).forEach { name: String -> includeTestsMatching(name) }
        isFailOnNoMatchingTests = true
    }
    failOnStaleClassNames(realMediaSoakClasses + hazardProbeClasses)
}
