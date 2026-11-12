package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.internal.FileEngine
import io.github.jamal_wia.kmptoolkit.files.internal.PosixFileSystemOps

/**
 * The [FileAccess] of iOS, over POSIX calls and Foundation (data protection, free space).
 *
 * @since 5.0.0
 */
@OptIn(ToolkitInternalApi::class)
public fun createFileAccess(config: FileAccessConfig = FileAccessConfig()): FileAccess =
    FileEngine(PosixFileSystemOps(), config)
