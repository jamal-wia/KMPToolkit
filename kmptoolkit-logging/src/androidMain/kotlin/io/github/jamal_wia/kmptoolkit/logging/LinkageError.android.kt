package io.github.jamal_wia.kmptoolkit.logging

internal actual fun Throwable.isLinkageError(): Boolean = this is LinkageError
