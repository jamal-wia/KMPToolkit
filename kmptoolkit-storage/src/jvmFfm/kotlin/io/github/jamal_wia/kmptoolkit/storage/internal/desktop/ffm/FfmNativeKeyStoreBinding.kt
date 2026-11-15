package io.github.jamal_wia.kmptoolkit.storage.internal.desktop.ffm

import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.DpapiCalls
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.MacKeychainCalls
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.NativeKeyStoreBinding
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.SecretServiceCalls

/**
 * The FFM implementation of the key-store seam: the raw macOS, Windows and Linux calls through the JDK's Foreign Function
 * and Memory API, no third-party dependency. It is loaded by its fixed class name, and only when the runtime is 22 or newer
 * (`java.lang.foreign` is final in 22); it is compiled by the `ffm` compilation of the `jvm` target and packed into the
 * module's jvm jar.
 *
 * Only the section of the running operating system is built, and it is built when this object is: the libraries are opened
 * and the functions bound then (no key, item or prompt is touched), so a denied native access
 * (`--illegal-native-access=deny`, [IllegalCallerException]) or a missing system symbol ([UnsatisfiedLinkError]) fails the
 * load and is reported as `Unsupported(NATIVE_ACCESS_UNAVAILABLE)` by the selector. libsecret is the exception: a Linux
 * without it (or with one older than 0.19) is not a native-access problem, so that section loads and answers
 * `SYMBOL_MISSING` per call.
 *
 * Every raw call is blocking, runs on the one native-executor thread, frees every OS-allocated output before it returns and
 * zeroes the native copy of the key it made; see [NativeScope].
 */
internal class FfmNativeKeyStoreBinding internal constructor(
    private val foreign: Foreign,
    private val ledger: NativeLedger,
    os: FfmOs?,
) : NativeKeyStoreBinding {

    /** The binding of the running process, created by the selector by class name: system libraries, the running OS. */
    constructor() : this(Foreign.system(), NativeLedger(), FfmOs.fromOsName(System.getProperty("os.name").orEmpty()))

    private val mac: FfmMacKeychain? = if (os == FfmOs.MACOS) FfmMacKeychain(foreign, ledger) else null
    private val windows: FfmDpapi? = if (os == FfmOs.WINDOWS) FfmDpapi(foreign, ledger) else null
    private val linux: FfmSecretService? = if (os == FfmOs.LINUX) FfmSecretService(foreign, ledger) else null

    override fun macOs(): MacKeychainCalls = mac ?: wrongOs("macOS")

    override fun windows(): DpapiCalls = windows ?: wrongOs("Windows")

    override fun linux(): SecretServiceCalls = linux ?: wrongOs("Linux")

    private fun wrongOs(name: String): Nothing = throw UnsupportedOperationException("The FFM binding of this process does not serve $name")
}

/**
 * Which section of the binding the running operating system needs. The `ffm` compilation sees only the module's public API,
 * so this mirrors the selector's `os.name` mapping (macOS, Windows, Linux) instead of reaching for its internal enum; the
 * selector has already refused every other OS before it loads this class.
 */
internal enum class FfmOs {
    MACOS,
    WINDOWS,
    LINUX;

    internal companion object {
        /** The OS named by the `os.name` property, or `null` for any other. */
        fun fromOsName(osName: String): FfmOs? {
            val name: String = osName.lowercase(java.util.Locale.ROOT)
            return when {
                name.startsWith("mac") || name.contains("darwin") -> MACOS
                name.startsWith("windows") -> WINDOWS
                name.startsWith("linux") -> LINUX
                else -> null
            }
        }
    }
}
