package io.github.jamal_wia.kmptoolkit.core

@ToolkitInternalApi
public actual fun isAbsoluteFilePath(path: String): Boolean = path.startsWith('/')
