package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.internal.FileEngine
import io.github.jamal_wia.kmptoolkit.files.internal.LegacyAndroidFileSystemOps
import io.github.jamal_wia.kmptoolkit.files.internal.NioFileSystemOps
import io.github.jamal_wia.kmptoolkit.files.spi.FileSystemOps

/**
 * The [FileAccess] of Android: `java.nio.file` where the runtime has it (devices from API 26, and host test
 * runtimes such as Robolectric at any SDK level), `android.system.Os` calls otherwise (API 24-25 devices). Both
 * behave the same.
 *
 * @since 5.0.0
 */
@OptIn(ToolkitInternalApi::class)
public fun createFileAccess(config: FileAccessConfig = FileAccessConfig()): FileAccess =
    FileEngine(selectAndroidOps(nioAvailable), config)

/** NIO ops when [nioAvailable], the `Os` based ops otherwise. */
@OptIn(ToolkitInternalApi::class)
internal fun selectAndroidOps(nioAvailable: Boolean): FileSystemOps =
    if (nioAvailable) NioFileSystemOps() else LegacyAndroidFileSystemOps()

/**
 * Whether the runtime has `java.nio.file.Files`, looked up once. Capability, not `SDK_INT`: Robolectric reports an
 * old SDK level but runs on a host JVM that has NIO and a stub `android.system.Os`. Only a missing class or a
 * linkage failure means absent.
 */
private val nioAvailable: Boolean by lazy {
    try {
        Class.forName("java.nio.file.Files", false, FileAccess::class.java.classLoader)
        true
    } catch (_: ClassNotFoundException) {
        false
    } catch (_: LinkageError) {
        false
    }
}
