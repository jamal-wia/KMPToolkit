package io.github.jamal_wia.kmptoolkit.logging

internal actual fun Throwable.isLinkageError(): Boolean = false

internal actual fun Throwable.isInterruptSignal(): Boolean = false

internal actual fun restoreInterruptFlag() = Unit
