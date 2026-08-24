plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
    id("kmptoolkit.androidtest")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Haptics")
    pomDescription.set(
        "A haptic feedback seam: a HapticFeedback interface, six semantic HapticType intensities, " +
            "and a typed HapticResult that tells you whether the device actually buzzed. Pick " +
            "this module if your shared Kotlin code wants to fire a success or error tap without " +
            "writing the Vibrator/VibrationEffect API-level dance on Android and the " +
            "UIFeedbackGenerator main-thread dance on iOS — it declares no permission of its own " +
            "and degrades to a typed result instead of throwing."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.haptics"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    // No commonMain dependencies: the whole module is the Kotlin stdlib plus two platform APIs
    // that already sit on the platform classpath (android.os.Vibrator, UIKit's feedback
    // generators). Adding it to a consumer's graph pulls in nothing else.
}
