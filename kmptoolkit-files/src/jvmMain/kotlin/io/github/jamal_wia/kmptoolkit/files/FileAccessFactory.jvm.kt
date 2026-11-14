package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.internal.FileEngine
import io.github.jamal_wia.kmptoolkit.files.internal.NioFileSystemOps

/**
 * The [FileAccess] of the desktop JVM, over java.nio.file.
 *
 * @since 5.0.0
 */
@OptIn(ToolkitInternalApi::class)
public fun createFileAccess(config: FileAccessConfig = FileAccessConfig()): FileAccess =
    FileEngine(NioFileSystemOps(), config)
