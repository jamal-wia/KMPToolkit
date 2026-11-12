package io.github.jamal_wia.kmptoolkit.files

import android.os.Build
import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.internal.FileEngine
import io.github.jamal_wia.kmptoolkit.files.internal.LegacyAndroidFileSystemOps
import io.github.jamal_wia.kmptoolkit.files.internal.NioFileSystemOps
import io.github.jamal_wia.kmptoolkit.files.spi.FileSystemOps

/**
 * The [FileAccess] of Android: java.nio.file from API 26, `android.system.Os` calls on API 24-25, where
 * java.nio.file does not exist. Both behave the same.
 *
 * @since 5.0.0
 */
@OptIn(ToolkitInternalApi::class)
public fun createFileAccess(config: FileAccessConfig = FileAccessConfig()): FileAccess =
    FileEngine(androidOpsFor(Build.VERSION.SDK_INT), config)

/** The ops of one SDK level: java.nio.file from API 26, the `Os` calls below. */
@OptIn(ToolkitInternalApi::class)
internal fun androidOpsFor(sdkInt: Int): FileSystemOps =
    if (sdkInt >= ANDROID_NIO_API) NioFileSystemOps() else LegacyAndroidFileSystemOps()

private const val ANDROID_NIO_API: Int = 26
