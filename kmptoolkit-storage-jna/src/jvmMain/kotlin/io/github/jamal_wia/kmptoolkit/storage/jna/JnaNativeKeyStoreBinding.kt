package io.github.jamal_wia.kmptoolkit.storage.jna

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.DpapiCalls
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.MacKeychainCalls
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.NativeKeyStoreBinding
import io.github.jamal_wia.kmptoolkit.storage.internal.desktop.SecretServiceCalls

/**
 * The JNA implementation of `kmptoolkit-storage`'s internal native key-store seam, for applications that run on JDK 21
 * (JDK 22 and newer use the FFM binding inside `kmptoolkit-storage` and ignore this artifact). Adding the dependency is
 * the whole opt-in: `kmptoolkit-storage` finds this class by its fixed name, `Class.forName`, with no `ServiceLoader`.
 *
 * This artifact is a bridge. It is deprecated once the suite's desktop baseline reaches JDK 22. It has no public API
 * for apps: the class is public and [ToolkitInternalApi] only because the seam it implements lives in another module,
 * and it can change in any release.
 *
 * - **Loading is cheap and never calls the operating system.** The constructor and the three accessors touch nothing; JNA's
 *   native library and the system libraries load on the first call of the matching call object, on the native
 *   executor thread. A JNA that cannot load there (a `noexec` temporary directory without `jna.tmpdir`, a blocked native
 *   access) surfaces as a `LinkageError` from that call, which the caller maps to `NATIVE_ACCESS_UNAVAILABLE`.
 * - **Minimal JNA.** Only `jna-jpms` core, with hand-written interfaces over the system libraries
 *   (`/System/Library/Frameworks/Security.framework`, `%SystemRoot%\System32\crypt32.dll`, the sonames
 *   `libsecret-1.so.0`, `libglib-2.0.so.0`, `libgobject-2.0.so.0` and `libgio-2.0.so.0`); never `jna-platform`.
 * - **Every raw call frees every operating-system-allocated output and zeroes its native copy of a key** before it returns.
 * - **libsecret older than 0.19** (a missing library or symbol) answers every Linux call with `RawStatus.SYMBOL_MISSING`,
 *   never an exception.
 *
 * Calls are made one at a time, from the native-executor thread of `kmptoolkit-storage`; the three call objects keep no
 * state between calls except the loaded libraries.
 */
@ToolkitInternalApi
public class JnaNativeKeyStoreBinding internal constructor(
    private val macPlatform: () -> MacPlatform,
    private val windowsPlatform: () -> WindowsPlatform,
    private val linuxPlatform: () -> LinuxPlatform,
    private val scratch: ScratchMemory,
) : NativeKeyStoreBinding {

    /** Creates the binding; touches no library and no operating-system call. */
    public constructor() : this(MacPlatform::load, WindowsPlatform::load, LinuxPlatform::load, ScratchMemory.Default)

    private val mac: MacKeychainCalls = JnaMacKeychainCalls(lazy(LazyThreadSafetyMode.PUBLICATION, macPlatform), scratch)
    private val windows: DpapiCalls = JnaDpapiCalls(lazy(LazyThreadSafetyMode.PUBLICATION, windowsPlatform), scratch)
    private val linux: SecretServiceCalls = JnaSecretServiceCalls(lazy(LazyThreadSafetyMode.PUBLICATION, linuxPlatform), scratch)

    override fun macOs(): MacKeychainCalls = mac

    override fun windows(): DpapiCalls = windows

    override fun linux(): SecretServiceCalls = linux
}
