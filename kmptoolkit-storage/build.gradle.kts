plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
    // Robolectric for androidUnitTest: both Android stores need a real Context, a real
    // SharedPreferences file, and — for the encrypted one — a real AndroidKeyStore provider.
    id("kmptoolkit.androidtest")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Storage")
    pomDescription.set(
        "Key-value storage for Kotlin Multiplatform: one KeyValueStorage interface over " +
            "SharedPreferences and NSUserDefaults, a SecureKeyValueStorage backed by the Android " +
            "Keystore and the iOS Keychain, and a DeviceIdProvider for a stable per-install id. " +
            "Every store name, keystore alias and Keychain service is derived from your own " +
            "application id, so two apps — or two features of one app — never share a store. " +
            "Pick this module if shared code needs a handful of small values to survive a " +
            "process restart, with failures arriving as typed StorageError values rather than " +
            "platform exceptions."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.storage"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    // A desktop target, under the exception in docs/01-architecture.md § "Desktop targets".
    // KeyValueStorage is the type a consumer's shared settings and repository code takes as a
    // parameter; an app that shares that code with desktop cannot compile it for desktop at all if
    // the interface exists on only two of its three targets. Unlike the Compose no-op targets, the
    // jvm half is real: a properties-file store the app points at its own directory. There is no
    // secure store on desktop — see `docs/kmptoolkit-storage/05-platform-notes.md`.
    jvm()
}
