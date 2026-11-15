# ProGuard / R8 rules shipped inside kmptoolkit-storage-jna. A consumer's shrinker picks them up from the jar; copy them
# by hand only if your tool ignores META-INF/proguard.

# The binding is found by its fixed name (Class.forName from kmptoolkit-storage), so nothing else references it.
-keep class io.github.jamal_wia.kmptoolkit.storage.jna.JnaNativeKeyStoreBinding { public <init>(); *; }

# A JNA interface is bound to the C library by method name, through reflection: renaming or removing a method breaks it.
-keep interface io.github.jamal_wia.kmptoolkit.storage.jna.** extends com.sun.jna.Library { *; }

# JNA itself looks up its own classes, fields and callbacks by name from the native side.
-keep class com.sun.jna.** { *; }
-keep interface com.sun.jna.** { *; }
-dontwarn com.sun.jna.**
-dontwarn java.awt.**
