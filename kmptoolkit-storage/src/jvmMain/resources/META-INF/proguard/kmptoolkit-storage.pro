# ProGuard / R8 rules shipped inside kmptoolkit-storage. R8 reads them from META-INF/proguard in the jar; ProGuard does not,
# so an app that shrinks with ProGuard (a Compose Desktop release build does) must pass this file to it itself, for example
# in buildTypes.release.proguard.configurationFiles of the Compose Desktop plugin. Do the same for any other tool that
# ignores META-INF/proguard.

# The desktop secure store loads its FFM key-store binding by its fixed class name (Class.forName), so nothing references
# it statically: keep the class and its no-argument constructor.
-keep class io.github.jamal_wia.kmptoolkit.storage.internal.desktop.ffm.FfmNativeKeyStoreBinding {
    <init>();
}

# The libsecret backend question hands libsecret a callback whose target is found by NAME (MethodHandles.findVirtual), so a
# shrinker that sees no caller would remove or rename it and the call would fail at run time.
-keep class io.github.jamal_wia.kmptoolkit.storage.internal.desktop.ffm.FfmSecretService$BackendWait {
    *;
}

# The JNA bridge artifact (kmptoolkit-storage-jna) is loaded by name the same way; its own rules keep its class.
-dontwarn io.github.jamal_wia.kmptoolkit.storage.jna.**

# java.lang.foreign exists only on JDK 22+ (the classes are loaded only behind a runtime check), and a tool that analyses
# against an older JDK must not fail on it.
-dontwarn java.lang.foreign.**
