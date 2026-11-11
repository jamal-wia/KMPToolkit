package io.github.jamal_wia.kmptoolkit.core

import java.io.File

@ToolkitInternalApi
public actual fun isAbsoluteFilePath(path: String): Boolean = File(path).isAbsolute
