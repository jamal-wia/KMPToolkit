# kmptoolkit-storage: rules for apps that shrink with ProGuard or R8 and ignore embedded rules never need to copy them by
# hand if their tool reads META-INF/proguard; otherwise copy this file.

# The desktop secure store loads its FFM key-store binding by its fixed class name (Class.forName), so nothing references
# it statically: keep the class and its no-argument constructor.
-keep class io.github.jamal_wia.kmptoolkit.storage.internal.desktop.ffm.FfmNativeKeyStoreBinding {
    <init>();
}

# The JNA bridge artifact (kmptoolkit-storage-jna) is loaded by name the same way; its own rules keep its class.
-dontwarn io.github.jamal_wia.kmptoolkit.storage.jna.**

# java.lang.foreign exists only on JDK 22+ (the classes are loaded only behind a runtime check), and a tool that analyses
# against an older JDK must not fail on it.
-dontwarn java.lang.foreign.**
