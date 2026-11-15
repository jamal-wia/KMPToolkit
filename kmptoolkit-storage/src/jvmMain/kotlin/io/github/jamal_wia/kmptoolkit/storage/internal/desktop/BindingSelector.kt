package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.UnsupportedReason
import java.lang.reflect.InvocationTargetException
import java.util.Locale

/** The desktop operating systems a store can serve. */
internal enum class DesktopOs {
    MACOS,
    WINDOWS,
    LINUX;

    internal companion object {
        /** The OS named by the `os.name` property, or `null` for an OS other than macOS, Windows and Linux. */
        fun fromOsName(osName: String): DesktopOs? {
            val name: String = osName.lowercase(Locale.ROOT)
            return when {
                name.startsWith("mac") || name.contains("darwin") -> MACOS
                name.startsWith("windows") -> WINDOWS
                name.startsWith("linux") -> LINUX
                else -> null
            }
        }
    }
}

/** Which implementation of the seam serves. */
internal enum class BindingKind { FFM, JNA, FAKE }

/** What the selector decided: a binding, or the typed reason there is none. */
internal sealed interface BindingSelection {

    /** [binding] serves [os]. */
    class Selected(val binding: NativeKeyStoreBinding, val os: DesktopOs, val kind: BindingKind) : BindingSelection {
        override fun toString(): String = "Selected($os, $kind)"
    }

    /** No binding can serve; every secure call answers [error]. */
    class Unsupported(val error: StorageError.Unsupported) : BindingSelection {
        override fun toString(): String = "Unsupported(${error.reason})"
    }
}

/** What asking the seam for one OS's calls gave: the calls, or the typed reason the binding cannot serve. */
internal sealed interface Accessed<out T> {
    class Ready<out T>(val calls: T) : Accessed<T>

    class Unusable(val error: StorageError.Unsupported) : Accessed<Nothing>
}

/**
 * Calls [access], one of the OS accessors of the seam (`macOs()`, `windows()`, `linux()`). They sit outside the native
 * executor's backstop, so a `LinkageError` (a `NoSuchMethodError` of a version mix) or any exception they throw is
 * `Unsupported(NATIVE_ACCESS_UNAVAILABLE)` here, never thrown out of a store call or the probe (plan 4.2 step 5; the
 * selector treats a failing load the same way). A `VirtualMachineError` that is not a `LinkageError` propagates.
 */
internal fun <T> accessCalls(access: () -> T): Accessed<T> = try {
    Accessed.Ready(access())
} catch (error: Exception) {
    Accessed.Unusable(StorageError.Unsupported(UnsupportedReason.NATIVE_ACCESS_UNAVAILABLE, error))
} catch (error: LinkageError) {
    Accessed.Unusable(StorageError.Unsupported(UnsupportedReason.NATIVE_ACCESS_UNAVAILABLE, error))
}

/** The only way a store reaches a binding; tests install a fixed one, production uses [BindingSelector.process]. */
internal fun interface BindingSource {
    fun select(): BindingSelection
}

/** A source that always answers [selection]; how tests install the fake binding. */
internal class FixedBindingSource(private val selection: BindingSelection) : BindingSource {
    override fun select(): BindingSelection = selection
}

/** What the selector reads from the process: injectable, so every branch is testable on any machine. */
internal class SelectorEnvironment(
    /** The `os.name` property. */
    val osName: String,
    /** `Runtime.version().feature()`. */
    val runtimeFeature: Int,
    /** Loads the FFM binding by its fixed name; `null` when its class is absent; throws what loading throws. */
    val loadFfm: () -> NativeKeyStoreBinding?,
    /** Loads the JNA binding by its fixed name; `null` when its class is absent (the artifact is not there); throws what loading throws. */
    val loadJna: () -> NativeKeyStoreBinding?,
) {
    internal companion object {
        const val FFM_CLASS: String = "io.github.jamal_wia.kmptoolkit.storage.internal.desktop.ffm.FfmNativeKeyStoreBinding"
        const val JNA_CLASS: String = "io.github.jamal_wia.kmptoolkit.storage.jna.JnaNativeKeyStoreBinding"

        /** The environment of the running process. */
        fun system(): SelectorEnvironment = SelectorEnvironment(
            osName = System.getProperty("os.name").orEmpty(),
            runtimeFeature = Runtime.version().feature(),
            loadFfm = { loadByName(FFM_CLASS) },
            loadJna = { loadByName(JNA_CLASS) },
        )

        /**
         * A fixed class name, no `ServiceLoader` (no open plug-in point for data keys). `null` only when the class itself
         * is absent; every other failure is thrown, with an [InvocationTargetException] unwrapped.
         */
        fun loadByName(name: String): NativeKeyStoreBinding? {
            val type: Class<*> = try {
                Class.forName(name, true, SelectorEnvironment::class.java.classLoader)
            } catch (_: ClassNotFoundException) {
                return null
            }
            try {
                return type.getDeclaredConstructor().newInstance() as NativeKeyStoreBinding
            } catch (error: InvocationTargetException) {
                throw error.targetException ?: error
            }
        }
    }
}

/**
 * Chooses a store's binding once per process (plan 4.2), at the first secure call or probe, never when the plain
 * store loads:
 * 1. `os.name` is not macOS, Windows or Linux: `Unsupported(UNSUPPORTED_OS)`.
 * 2. Runtime 22 or newer: the FFM binding; a JNA artifact on the classpath is ignored.
 * 3. Otherwise the JNA binding, if its class loads.
 * 4. Otherwise `Unsupported(UNSUPPORTED_RUNTIME)`.
 * 5. A load failure (`IllegalCallerException` under `--illegal-native-access=deny`, an `UnsatisfiedLinkError`, a
 *    `SecurityException`, any other `LinkageError`, a constructor that throws): `Unsupported(NATIVE_ACCESS_UNAVAILABLE)`.
 *
 * The decision is made on the first [select] and kept, success or failure, so a binding that failed to load is not
 * loaded again by every call. The backstop for a `LinkageError` thrown later, around a call, is [NativeExecutor].
 */
internal class BindingSelector(private val environment: SelectorEnvironment) : BindingSource {

    private val decision: BindingSelection by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { decide() }

    override fun select(): BindingSelection = decision

    private fun decide(): BindingSelection {
        val os: DesktopOs = DesktopOs.fromOsName(environment.osName)
            ?: return unsupported(UnsupportedReason.UNSUPPORTED_OS, null)
        if (environment.runtimeFeature >= FFM_MINIMUM_RUNTIME) {
            return load(os, BindingKind.FFM, environment.loadFfm)
        }
        return load(os, BindingKind.JNA, environment.loadJna, absent = UnsupportedReason.UNSUPPORTED_RUNTIME)
    }

    private fun load(
        os: DesktopOs,
        kind: BindingKind,
        loader: () -> NativeKeyStoreBinding?,
        absent: UnsupportedReason = UnsupportedReason.NATIVE_ACCESS_UNAVAILABLE,
    ): BindingSelection {
        val binding: NativeKeyStoreBinding? = try {
            loader()
        } catch (error: Exception) {
            return unsupported(UnsupportedReason.NATIVE_ACCESS_UNAVAILABLE, error)
        } catch (error: LinkageError) {
            return unsupported(UnsupportedReason.NATIVE_ACCESS_UNAVAILABLE, error)
        }
        return if (binding == null) unsupported(absent, null) else BindingSelection.Selected(binding, os, kind)
    }

    private fun unsupported(reason: UnsupportedReason, cause: Throwable?): BindingSelection =
        BindingSelection.Unsupported(StorageError.Unsupported(reason, cause))

    internal companion object {
        /** FFM is final in JDK 22. */
        const val FFM_MINIMUM_RUNTIME: Int = 22

        /** The selector of the process; nothing touches it until a secure call or a probe. */
        val process: BindingSelector by lazy { BindingSelector(SelectorEnvironment.system()) }
    }
}
